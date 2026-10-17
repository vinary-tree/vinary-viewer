'use strict';
// A hermetic, in-process SSH+SFTP server for tests. Because ssh2 is also an SSH *server*, this backs a genuine
// SFTP endpoint on 127.0.0.1:<ephemeral> whose "remote filesystem" is a real temp directory — so remote-file
// tests exercise the true SFTP wire protocol with no network and no external host. Also answers `exec` for
// `echo ~user` (used by ~user home resolution) so the transport's full path handling is covered.
//
//   const srv = startSftpServer({ password: 'pw', files: { 'notes.md': '# hi', 'sub/a.txt': 'x' } });
//   // → { host:'127.0.0.1', port, user:'tester', password, dir, url(p), close() }
//
// Experiment / fault-injection knobs (all OFF by default, so existing tests see the historical behaviour):
//   config.ident        the server's SSH software-version string (e.g. 'OpenSSH_9.6'). ssh2 clients size their
//                       SFTP reads from it: an OpenSSH/dropbear ident unlocks 260 096-byte reads, anything else
//                       (incl. ssh2's own default ident) caps a read at 31 952 bytes — docs/scientific/11.
//   config.maxReadLen   answer READs with at most this many bytes (a server that caps reads; short reads)
//   config.noDelay      true → TCP_NODELAY on the server side of every connection (Nagle off). Default: Nagle ON,
//                       which is what ssh2's Server does unless told otherwise — docs/scientific/11 H0
//   srv.setDelay(fn)    fn(req) → ms to hold each request's reply (req = {op, path, offset?, length?})
//   srv.onRequest(fn)   fn(req) → (Promise) run BEFORE a request is served — e.g. rewrite a file mid-read
//   srv.setFault(fn)    fn(req) → an SFTP STATUS_CODE to answer with instead of serving (null → serve)
//   srv.stats           { byPath: {path: {opens, reads, readBytes, stats}}, requests, inFlightReads,
//                         maxInFlightReads, inFlightStats, maxInFlightStats }; srv.resetStats()
//   srv.openHandles()   live SFTP handles across every session (a leak check)
//   srv.liveConnections()  SSH connections the server still holds open (a connection-leak check)
//
// Requires ssh-keygen (openssh) to mint an OpenSSH-format host key (the format ssh2.utils.parseKey accepts).

const fs = require('fs');
const os = require('os');
const net = require('net');
const path = require('path');
const cp = require('child_process');
const { Server, utils } = require('ssh2');
const { STATUS_CODE, OPEN_MODE, flagsToString } = utils.sftp;

function writeTree(root, files) {
  for (const [rel, content] of Object.entries(files || {})) {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content);
  }
}

function statToAttrs(st) {
  return { mode: st.mode, uid: st.uid, gid: st.gid, size: st.size, atime: Math.floor(st.atimeMs / 1000), mtime: Math.floor(st.mtimeMs / 1000) };
}

function longname(name, st) {
  const type = st ? (st.isDirectory() ? 'd' : (st.isSymbolicLink && st.isSymbolicLink() ? 'l' : '-')) : '-';
  const size = st ? st.size : 0;
  return `${type}rw-r--r-- 1 tester tester ${String(size).padStart(8)} Jan  1 00:00 ${name}`;
}

function errStatus(err) {
  if (err && err.code === 'ENOENT') return STATUS_CODE.NO_SUCH_FILE;
  if (err && err.code === 'EACCES') return STATUS_CODE.PERMISSION_DENIED;
  return STATUS_CODE.FAILURE;
}

function startSftpServer(config) {
  config = config || {};
  const dir = config.dir || fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sftp-'));
  writeTree(dir, config.files);

  // Host key (OpenSSH format via ssh-keygen — the format ssh2.parseKey reads). Kept OUTSIDE the served
  // directory so it never appears in remote directory listings.
  const metaDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sftp-meta-'));
  const hostKeyPath = path.join(metaDir, 'host_ed25519');
  if (!fs.existsSync(hostKeyPath)) {
    cp.execFileSync('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-C', 'vv-test-host', '-f', hostKeyPath]);
  }
  const hostKey = fs.readFileSync(hostKeyPath);
  const hostParsed = utils.parseKey(hostKey);
  const hk = Array.isArray(hostParsed) ? hostParsed[0] : hostParsed;

  const user = config.user || 'tester';
  const password = config.password !== undefined ? config.password : 'secret-pw';
  const authorized = (config.authorizedKeys || []).map((k) => {
    const parsed = utils.parseKey(k);
    const key = Array.isArray(parsed) ? parsed[0] : parsed;
    return { type: key.type, blob: key.getPublicSSH() };
  });

  // Map a client-supplied remote path into the temp root, clamped so `..` cannot escape.  A small number of
  // integration tests opt into an identity namespace rooted at `exposeAbsoluteRoot`: remote absolute paths below
  // that root map to the same local absolute paths. This models a normal (non-chrooted) OpenSSH SFTP subsystem,
  // while the containment check keeps the hermetic server from exposing anything outside its fixture directory.
  const exposedRoot = config.exposeAbsoluteRoot ? fs.realpathSync(config.exposeAbsoluteRoot) : null;
  const realHome = exposedRoot ? fs.realpathSync(config.realHome || exposedRoot) : '/';
  const deniedPath = path.join(metaDir, 'sftp-path-outside-fixture');
  const contained = (root, candidate) => {
    const rel = path.relative(root, candidate);
    return rel === '' || (!rel.startsWith('..' + path.sep) && rel !== '..' && !path.isAbsolute(rel));
  };
  const remoteAbsolute = (p) => {
    const value = String(p == null ? '' : p);
    if (value === '' || value === '.') return realHome;
    return path.posix.isAbsolute(value)
      ? path.posix.normalize(value)
      : path.posix.resolve(realHome.replace(/\\/g, '/'), value);
  };
  const mapPath = (p) => {
    if (exposedRoot) {
      const candidate = path.resolve(remoteAbsolute(p));
      return contained(exposedRoot, candidate) ? candidate : deniedPath;
    }
    const norm = path.posix.normalize('/' + String(p == null ? '' : p));
    return path.join(dir, '.' + norm);
  };

  // ---- instrumentation (see the header) ----
  const hooks = { delay: null, onRequest: null, fault: null };
  const freshStats = () => ({ byPath: {}, requests: 0, inFlightReads: 0, maxInFlightReads: 0,
                              inFlightStats: 0, maxInFlightStats: 0 });
  let stats = freshStats();
  const liveHandles = new Set();
  const pathStats = (p) => (stats.byPath[p] = stats.byPath[p] || { opens: 0, reads: 0, readBytes: 0, stats: 0 });
  const remoteKey = (p) => path.posix.normalize('/' + String(p == null ? '' : p));
  // Serve one request through the hooks: count it, run onRequest, then answer with an injected fault or run
  // `serve(done)` after the configured delay. `done` must be called exactly once when the reply has been sent;
  // it balances the in-flight counters the experiment reads (max concurrent READs = the reader's pipeline depth).
  const instrumented = (sftp, reqid, req, serve) => {
    stats.requests++;
    const isRead = req.op === 'READ';
    const isStat = req.op === 'STAT' || req.op === 'LSTAT' || req.op === 'FSTAT';
    if (isRead) { stats.inFlightReads++; stats.maxInFlightReads = Math.max(stats.maxInFlightReads, stats.inFlightReads); }
    if (isStat) { stats.inFlightStats++; stats.maxInFlightStats = Math.max(stats.maxInFlightStats, stats.inFlightStats); }
    let finished = false;
    const done = () => {
      if (finished) return;
      finished = true;
      if (isRead) stats.inFlightReads--;
      if (isStat) stats.inFlightStats--;
    };
    const go = () => {
      const fault = hooks.fault ? hooks.fault(req) : null;
      if (fault !== null && fault !== undefined) { done(); return sftp.status(reqid, fault); }
      serve(done);
    };
    Promise.resolve(hooks.onRequest ? hooks.onRequest(req) : null).catch(() => null).then(() => {
      const ms = hooks.delay ? Number(hooks.delay(req)) || 0 : 0;
      if (ms > 0) setTimeout(go, ms); else go();
    });
  };

  const clients = new Set();
  const serverOpts = { hostKeys: [hostKey] };
  if (config.ident) serverOpts.ident = config.ident;
  const server = new Server(serverOpts, (client) => {
    clients.add(client);
    if (config.noDelay) client.setNoDelay(true);
    client.on('close', () => clients.delete(client));
    client.on('authentication', (ctx) => {
      const allow = ['password', 'publickey'];
      if (config.keyboard) allow.push('keyboard-interactive');
      if (ctx.method === 'none') return config.allowNone ? ctx.accept() : ctx.reject(allow, false);
      if (ctx.method === 'password') {
        return (ctx.username === user && ctx.password === password) ? ctx.accept() : ctx.reject();
      }
      if (ctx.method === 'publickey') {
        const match = authorized.find((a) => a.type === ctx.key.algo && a.blob.equals(ctx.key.data));
        if (!match || ctx.username !== user) return ctx.reject();
        if (ctx.signature) {
          const parsed = utils.parseKey(config.authorizedKeys[authorized.indexOf(match)]);
          const key = Array.isArray(parsed) ? parsed[0] : parsed;
          return key.verify(ctx.blob, ctx.signature, ctx.hashAlgo) ? ctx.accept() : ctx.reject();
        }
        return ctx.accept();   // query phase — the key is acceptable
      }
      if (ctx.method === 'keyboard-interactive' && config.keyboard) {
        return ctx.prompt(config.keyboard.prompts, config.keyboard.title || 'MFA', '', (answers) => {
          config.keyboard.verify(answers) ? ctx.accept() : ctx.reject();
        });
      }
      return ctx.reject();
    });

    client.on('ready', () => {
      // direct-tcpip forwarding — lets this server act as a ProxyJump host (forwardOut → the next hop/target).
      client.on('tcpip', (accept, reject, info) => {
        const socket = net.connect(info.destPort, info.destIP, () => {
          const channel = accept();
          socket.pipe(channel).pipe(socket);
        });
        socket.on('error', () => { try { reject(); } catch (_e) {} });
      });
      client.on('session', (accept) => {
        const session = accept();

        session.on('exec', (acc, _rej, info) => {
          const stream = acc();
          const cmd = String(info.command || '');
          const m = /^echo\s+~([A-Za-z_][A-Za-z0-9_-]*)\s*$/.exec(cmd);
          if (m) {
            const home = (config.userHomes && config.userHomes[m[1]]) || `/home/${m[1]}`;
            stream.stdout.write(home + '\n'); stream.exit(0); return stream.end();
          }
          const em = /^echo\s+(.*)$/.exec(cmd);
          if (em) { stream.stdout.write(em[1] + '\n'); stream.exit(0); return stream.end(); }
          stream.stderr.write('unsupported command\n'); stream.exit(127); stream.end();
        });

        session.on('sftp', (acceptSftp) => {
          const sftp = acceptSftp();
          const handles = new Map();
          let nextHandle = 0;
          const alloc = (obj) => { const id = nextHandle++; handles.set(id, obj); liveHandles.add(obj); const h = Buffer.alloc(4); h.writeUInt32BE(id, 0); return h; };
          const get = (h) => handles.get(h.readUInt32BE(0));
          const drop = (h) => { liveHandles.delete(handles.get(h.readUInt32BE(0))); handles.delete(h.readUInt32BE(0)); };
          // a session that dies with handles open (dropped connection) must not leak them in openHandles()
          // (ssh2's server-side SFTP emits 'end' when the channel closes; 'close' is listened for too, defensively)
          const reapHandles = () => { for (const obj of handles.values()) { liveHandles.delete(obj); if (obj.fd !== undefined) fs.close(obj.fd, () => {}); } handles.clear(); };
          sftp.on('end', reapHandles);
          sftp.on('close', reapHandles);

          sftp.on('REALPATH', (reqid, p) => {
            const abs = exposedRoot ? remoteAbsolute(p)
              : ((p === '.' || p === '' || p == null) ? '/' : path.posix.resolve('/', p));
            sftp.name(reqid, [{ filename: abs, longname: abs, attrs: {} }]);
          });
          const onStat = (useLstat) => (reqid, p) => {
            const key = remoteKey(p);
            pathStats(key).stats++;
            instrumented(sftp, reqid, { op: useLstat ? 'LSTAT' : 'STAT', path: key }, (done) => {
              const fn = useLstat ? fs.lstat : fs.stat;
              fn(mapPath(p), (err, st) => { done(); err ? sftp.status(reqid, errStatus(err)) : sftp.attrs(reqid, statToAttrs(st)); });
            });
          };
          sftp.on('STAT', onStat(false));
          sftp.on('LSTAT', onStat(true));
          sftp.on('FSTAT', (reqid, h) => {
            const st = get(h);
            if (st && st.remote) pathStats(st.remote).stats++;
            instrumented(sftp, reqid, { op: 'FSTAT', path: st && st.remote }, (done) => {
              const reply = (err, s) => { done(); err ? sftp.status(reqid, errStatus(err)) : sftp.attrs(reqid, statToAttrs(s)); };
              if (st && st.type === 'file') return fs.fstat(st.fd, reply);
              if (st && st.type === 'dir') return fs.stat(st.path, reply);
              done(); sftp.status(reqid, STATUS_CODE.FAILURE);
            });
          });
          sftp.on('OPEN', (reqid, filename, flags, _attrs) => {
            const key = remoteKey(filename);
            pathStats(key).opens++;
            instrumented(sftp, reqid, { op: 'OPEN', path: key }, (done) => {
              fs.open(mapPath(filename), flagsToString(flags) || 'r', (err, fd) => {
                done();
                if (err) return sftp.status(reqid, errStatus(err));
                sftp.handle(reqid, alloc({ type: 'file', fd, path: mapPath(filename), remote: key }));
              });
            });
          });
          sftp.on('READ', (reqid, h, offset, length) => {
            const st = get(h);
            if (!st || st.type !== 'file') return sftp.status(reqid, STATUS_CODE.FAILURE);
            const ps = pathStats(st.remote);
            ps.reads++;
            instrumented(sftp, reqid, { op: 'READ', path: st.remote, offset, length }, (done) => {
              const want = config.maxReadLen ? Math.min(length, config.maxReadLen) : length;
              const buf = Buffer.alloc(want);
              fs.read(st.fd, buf, 0, want, offset, (err, n) => {
                done();
                if (err) return sftp.status(reqid, errStatus(err));
                if (n === 0) return sftp.status(reqid, STATUS_CODE.EOF);
                ps.readBytes += n;
                sftp.data(reqid, buf.slice(0, n));
              });
            });
          });
          sftp.on('WRITE', (reqid, h, offset, data) => {
            const st = get(h);
            if (!st || st.type !== 'file') return sftp.status(reqid, STATUS_CODE.FAILURE);
            fs.write(st.fd, data, 0, data.length, offset, (err) => sftp.status(reqid, err ? errStatus(err) : STATUS_CODE.OK));
          });
          sftp.on('OPENDIR', (reqid, p) => {
            const real = mapPath(p);
            fs.readdir(real, { withFileTypes: true }, (err, ents) => {
              if (err) return sftp.status(reqid, errStatus(err));
              sftp.handle(reqid, alloc({ type: 'dir', path: real, entries: ents, idx: 0 }));
            });
          });
          sftp.on('READDIR', (reqid, h) => {
            const st = get(h);
            if (!st || st.type !== 'dir') return sftp.status(reqid, STATUS_CODE.FAILURE);
            if (st.idx >= st.entries.length) return sftp.status(reqid, STATUS_CODE.EOF);
            const batch = st.entries.slice(st.idx);
            st.idx = st.entries.length;
            const names = batch.map((d) => {
              let s = null;
              try { s = fs.lstatSync(path.join(st.path, d.name)); } catch (_e) { /* race */ }
              return { filename: d.name, longname: longname(d.name, s), attrs: s ? statToAttrs(s) : {} };
            });
            sftp.name(reqid, names);
          });
          sftp.on('MKDIR', (reqid, p) => fs.mkdir(mapPath(p), (err) => sftp.status(reqid, err ? errStatus(err) : STATUS_CODE.OK)));
          sftp.on('CLOSE', (reqid, h) => {
            const st = get(h);
            if (st && st.type === 'file' && st.fd !== undefined) fs.close(st.fd, () => {});
            drop(h);
            sftp.status(reqid, STATUS_CODE.OK);
          });
        });
      });
    });
    client.on('error', () => {});
  });

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      resolve({
        host: '127.0.0.1', port, user, password, dir, hostKeyPath,
        hostKeyType: hk.type, hostKeyB64: hk.getPublicSSH().toString('base64'),
        url: (p) => `ssh://${user}@127.0.0.1:${port}${p.startsWith('/') ? '' : '/'}${p}`,
        close: () => new Promise((res) => server.close(() => res())),
        // Abruptly destroy every live connection's socket (ECONNRESET) — simulates a mid-stream network drop so
        // an in-flight SFTP read errors deterministically (server.close() alone leaves active connections open).
        destroyConnections: () => { for (const c of clients) { try { c._sock.destroy(); } catch (_e) {} } },
        setDelay: (fn) => { hooks.delay = fn || null; },
        onRequest: (fn) => { hooks.onRequest = fn || null; },
        setFault: (fn) => { hooks.fault = fn || null; },
        get stats() { return stats; },
        resetStats: () => { stats = freshStats(); },
        openHandles: () => liveHandles.size,
        liveConnections: () => clients.size,
        STATUS_CODE,
        server,
      });
    });
  });
}

module.exports = { startSftpServer };
