'use strict';
// A TCP latency proxy for hermetic network experiments (docs/scientific/11).
//
// Why a proxy and not a delay inside the SFTP server's READ handler: a server-side setTimeout models server
// PROCESSING time, not network distance — the SSH channel-window adjusts, OPEN, STAT and every ack would still be
// instant, so a pipelined reader would look exactly as slow as a sequential one. Delaying every TCP chunk in each
// direction by RTT/2 delays everything the real network would: the SSH handshake, window adjusts, and each
// request/response pair alike.
//
//   const proxy = await startLatencyProxy({ targetHost: '127.0.0.1', targetPort: srv.port, rttMs: 50 });
//   // connect clients to proxy.host:proxy.port; proxy.close() when done
//
// Model per direction: a FIFO of chunks, each released at max(arrival + rtt/2, previous release + serialization)
// where serialization = bytes / bandwidthBps (0 → unlimited). One timer per direction preserves byte order.
// rttMs = 0 and no bandwidth limit degenerates to a plain pipe (no timers at all), so the 0-RTT baseline
// measures the reader, not the proxy.
//
// The proxy is a model, not a network emulator: it has no loss, no jitter and no TCP slow start of its own. The
// experiment cross-checks one cell against Linux netem (`unshare -rn` + `tc qdisc … netem delay`) to bound the
// modelling error (docs/scientific/11 §Instruments).

const net = require('net');

function delayedPipe(from, to, oneWayMs, bandwidthBps, stats, key) {
  if (!oneWayMs && !bandwidthBps) {
    from.on('data', (chunk) => { stats[key] += chunk.length; });
    from.pipe(to);
    return;
  }
  const queue = [];               // [{ chunk, due }]
  let timer = null;
  let lastRelease = 0;
  let ended = false;
  const pump = () => {
    timer = null;
    const now = Date.now();
    while (queue.length && queue[0].due <= now) {
      const { chunk } = queue.shift();
      stats[key] += chunk.length;
      to.write(chunk);
    }
    if (queue.length) timer = setTimeout(pump, Math.max(0, queue[0].due - Date.now()));
    else if (ended) to.end();
  };
  from.on('data', (chunk) => {
    const arrival = Date.now();
    const serialization = bandwidthBps ? (chunk.length / bandwidthBps) * 1000 : 0;
    const due = Math.max(arrival + oneWayMs, lastRelease + serialization);
    lastRelease = due;
    queue.push({ chunk, due });
    if (!timer) timer = setTimeout(pump, Math.max(0, due - arrival));
  });
  from.on('end', () => { ended = true; if (!timer && !queue.length) to.end(); });
}

/**
 * Start the proxy. opts: { targetHost, targetPort, rttMs = 0, bandwidthBps = 0 (unlimited), host = '127.0.0.1' }.
 * Resolves to { host, port, close(), stats: { up, down, connections } } where up/down count bytes forwarded
 * client→server and server→client.
 */
function startLatencyProxy(opts) {
  const rtt = Math.max(0, Number(opts.rttMs) || 0);
  const oneWay = rtt / 2;
  const bandwidth = Math.max(0, Number(opts.bandwidthBps) || 0);
  const stats = { up: 0, down: 0, connections: 0 };
  const sockets = new Set();
  const server = net.createServer((client) => {
    stats.connections++;
    const upstream = net.connect(opts.targetPort, opts.targetHost || '127.0.0.1');
    for (const s of [client, upstream]) {
      s.setNoDelay(true);          // Nagle would add its own, unmodelled delay to small SFTP requests
      sockets.add(s);
      s.on('close', () => sockets.delete(s));
      s.on('error', () => { client.destroy(); upstream.destroy(); });
    }
    delayedPipe(client, upstream, oneWay, bandwidth, stats, 'up');
    delayedPipe(upstream, client, oneWay, bandwidth, stats, 'down');
  });
  return new Promise((resolve) => {
    server.listen(0, opts.host || '127.0.0.1', () => {
      resolve({
        host: opts.host || '127.0.0.1',
        port: server.address().port,
        rttMs: rtt,
        stats,
        close: () => new Promise((res) => {
          for (const s of sockets) s.destroy();
          server.close(() => res());
        }),
      });
    });
  });
}

module.exports = { startLatencyProxy };
