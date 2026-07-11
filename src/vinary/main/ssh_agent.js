'use strict';
// ssh_agent.js — add a decrypted private key to the running ssh-agent (the AddKeysToAgent directive).
//
// ssh2's agent classes expose only getIdentities/sign — there is no client-side "add" — so this implements the
// SSH agent protocol's SSH_AGENTC_ADD_IDENTITY / ADD_ID_CONSTRAINED message directly (draft-miller-ssh-agent),
// for the three key families in real use: Ed25519, RSA, and ECDSA (nistp256/384/521). The private key material
// is obtained from the parsed key's PEM via Node's crypto JWK export, so no third-party crypto is needed.
//
// Pure protocol construction + a single unix-socket round-trip. Node builtins only (net + crypto).

const net = require('net');
const crypto = require('crypto');

const SSH_AGENTC_ADD_IDENTITY = 17;
const SSH_AGENTC_ADD_ID_CONSTRAINED = 25;
const SSH_AGENT_SUCCESS = 6;
const SSH_AGENT_CONSTRAIN_LIFETIME = 1;
const SSH_AGENT_CONSTRAIN_CONFIRM = 2;

function u32(n) { const b = Buffer.alloc(4); b.writeUInt32BE(n >>> 0, 0); return b; }
function sshBytes(buf) { return Buffer.concat([u32(buf.length), buf]); }
function sshStr(s) { return sshBytes(Buffer.from(s, 'utf8')); }

// SSH mpint: minimal big-endian two's-complement, with a leading 0x00 when the high bit is set.
function mpint(buf) {
  let i = 0;
  while (i < buf.length - 1 && buf[i] === 0) i++;
  let b = buf.slice(i);
  if (b.length === 0) b = Buffer.from([0]);
  if (b[0] & 0x80) b = Buffer.concat([Buffer.from([0]), b]);
  return sshBytes(b);
}

function b64u(s) { return Buffer.from(String(s), 'base64url'); }

// (jwk, keyType) → the type-specific portion of an ADD_IDENTITY key blob (everything after the leading
// key-type string, up to but excluding the comment). Also returns the canonical ssh key-type name.
function privateKeyFields(jwk) {
  if (jwk.kty === 'OKP' && jwk.crv === 'Ed25519') {
    const pub = b64u(jwk.x);          // 32-byte public key A
    const seed = b64u(jwk.d);         // 32-byte private seed k
    return {
      keyType: 'ssh-ed25519',
      body: Buffer.concat([sshStr('ssh-ed25519'), sshBytes(pub), sshBytes(Buffer.concat([seed, pub]))]),
    };
  }
  if (jwk.kty === 'RSA') {
    const n = b64u(jwk.n), e = b64u(jwk.e), d = b64u(jwk.d), p = b64u(jwk.p), q = b64u(jwk.q), qi = b64u(jwk.qi);
    return {
      keyType: 'ssh-rsa',
      // ssh-rsa private order: n e d iqmp p q  (iqmp = q^-1 mod p = the JWK 'qi' coefficient)
      body: Buffer.concat([sshStr('ssh-rsa'), mpint(n), mpint(e), mpint(d), mpint(qi), mpint(p), mpint(q)]),
    };
  }
  if (jwk.kty === 'EC') {
    const curve = { 'P-256': 'nistp256', 'P-384': 'nistp384', 'P-521': 'nistp521' }[jwk.crv];
    if (!curve) throw new Error('unsupported EC curve: ' + jwk.crv);
    const keyType = 'ecdsa-sha2-' + curve;
    const Q = Buffer.concat([Buffer.from([0x04]), b64u(jwk.x), b64u(jwk.y)]);   // uncompressed point
    return {
      keyType,
      body: Buffer.concat([sshStr(keyType), sshStr(curve), sshBytes(Q), mpint(b64u(jwk.d))]),
    };
  }
  throw new Error('unsupported key type for agent add: ' + jwk.kty + '/' + (jwk.crv || ''));
}

function constraintBytes({ lifetime, confirm }) {
  const parts = [];
  if (lifetime && lifetime > 0) parts.push(Buffer.concat([Buffer.from([SSH_AGENT_CONSTRAIN_LIFETIME]), u32(lifetime)]));
  if (confirm) parts.push(Buffer.from([SSH_AGENT_CONSTRAIN_CONFIRM]));
  return Buffer.concat(parts);
}

// Build the full framed agent request (uint32 length || type || payload) for adding `pem`'s key.
function buildAddIdentity(pem, comment, constraints) {
  const jwk = crypto.createPrivateKey(pem).export({ format: 'jwk' });
  const { body } = privateKeyFields(jwk);
  const cons = constraintBytes(constraints || {});
  const type = cons.length ? SSH_AGENTC_ADD_ID_CONSTRAINED : SSH_AGENTC_ADD_IDENTITY;
  const payload = Buffer.concat([Buffer.from([type]), body, sshStr(comment || ''), cons]);
  return Buffer.concat([u32(payload.length), payload]);
}

// addKey(sockPath, parsedKeyOrPem, comment, {lifetime, confirm}) → Promise<void>
// resolves on SSH_AGENT_SUCCESS, rejects otherwise. `parsedKeyOrPem` is an ssh2 ParsedKey (private) or a PEM.
function addKey(sockPath, parsedKeyOrPem, comment, constraints) {
  return new Promise((resolve, reject) => {
    let request;
    try {
      const pem = (parsedKeyOrPem && typeof parsedKeyOrPem.getPrivatePEM === 'function')
        ? parsedKeyOrPem.getPrivatePEM()
        : parsedKeyOrPem;
      request = buildAddIdentity(pem, comment, constraints);
    } catch (e) { return reject(e); }

    const sock = net.connect(sockPath);
    let reply = Buffer.alloc(0);
    let done = false;
    const finish = (err) => { if (done) return; done = true; try { sock.destroy(); } catch (_e) {} err ? reject(err) : resolve(); };

    sock.on('error', finish);
    sock.on('connect', () => sock.write(request));
    sock.on('data', (chunk) => {
      reply = Buffer.concat([reply, chunk]);
      if (reply.length < 5) return;                       // need length(4) + type(1)
      const len = reply.readUInt32BE(0);
      if (reply.length < 4 + len) return;
      finish(reply[4] === SSH_AGENT_SUCCESS ? null : new Error('ssh-agent rejected the key (reply ' + reply[4] + ')'));
    });
    sock.on('end', () => finish(new Error('ssh-agent closed the connection before replying')));
  });
}

module.exports = { addKey, buildAddIdentity, privateKeyFields, mpint, sshStr, sshBytes };
