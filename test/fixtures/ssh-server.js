'use strict';
// A hermetic, in-process SSH+SFTP server for tests. Because ssh2 is also an SSH *server*, this backs a genuine
// SFTP endpoint on 127.0.0.1:<ephemeral> whose "remote filesystem" is a real temp directory — so remote-file
// tests exercise the true SFTP wire protocol with no network and no external host. Also answers `exec` for
// `echo ~user` (used by ~user home resolution) so the transport's full path handling is covered.
//
//   const srv = startSftpServer({ password: 'pw', files: { 'notes.md': '# hi', 'sub/a.txt': 'x' } });
//   // → { host:'127.0.0.1', port, user:'tester', password, dir, url(p), close() }
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

  // Map a client-supplied remote path into the temp root, clamped so `..` cannot escape.
  const mapPath = (p) => {
    const norm = path.posix.normalize('/' + String(p == null ? '' : p));
    return path.join(dir, '.' + norm);
  };

  const clients = new Set();
  const server = new Server({ hostKeys: [hostKey] }, (client) => {
    clients.add(client);
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
          const alloc = (obj) => { const id = nextHandle++; handles.set(id, obj); const h = Buffer.alloc(4); h.writeUInt32BE(id, 0); return h; };
          const get = (h) => handles.get(h.readUInt32BE(0));
          const drop = (h) => handles.delete(h.readUInt32BE(0));

          sftp.on('REALPATH', (reqid, p) => {
            const abs = (p === '.' || p === '' || p == null) ? '/' : path.posix.resolve('/', p);
            sftp.name(reqid, [{ filename: abs, longname: abs, attrs: {} }]);
          });
          const onStat = (useLstat) => (reqid, p) => {
            const fn = useLstat ? fs.lstat : fs.stat;
            fn(mapPath(p), (err, st) => err ? sftp.status(reqid, errStatus(err)) : sftp.attrs(reqid, statToAttrs(st)));
          };
          sftp.on('STAT', onStat(false));
          sftp.on('LSTAT', onStat(true));
          sftp.on('FSTAT', (reqid, h) => {
            const st = get(h);
            if (st && st.type === 'file') return fs.fstat(st.fd, (err, s) => err ? sftp.status(reqid, errStatus(err)) : sftp.attrs(reqid, statToAttrs(s)));
            if (st && st.type === 'dir') return fs.stat(st.path, (err, s) => err ? sftp.status(reqid, errStatus(err)) : sftp.attrs(reqid, statToAttrs(s)));
            sftp.status(reqid, STATUS_CODE.FAILURE);
          });
          sftp.on('OPEN', (reqid, filename, flags, _attrs) => {
            fs.open(mapPath(filename), flagsToString(flags) || 'r', (err, fd) => {
              if (err) return sftp.status(reqid, errStatus(err));
              sftp.handle(reqid, alloc({ type: 'file', fd, path: mapPath(filename) }));
            });
          });
          sftp.on('READ', (reqid, h, offset, length) => {
            const st = get(h);
            if (!st || st.type !== 'file') return sftp.status(reqid, STATUS_CODE.FAILURE);
            const buf = Buffer.alloc(length);
            fs.read(st.fd, buf, 0, length, offset, (err, n) => {
              if (err) return sftp.status(reqid, errStatus(err));
              if (n === 0) return sftp.status(reqid, STATUS_CODE.EOF);
              sftp.data(reqid, buf.slice(0, n));
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
        server,
      });
    });
  });
}

module.exports = { startSftpServer };
