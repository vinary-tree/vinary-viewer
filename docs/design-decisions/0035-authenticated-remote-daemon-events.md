# 0035 — Authenticate SSH-forwarded daemon events for remote files and trees

- **Status:** Accepted
- **Date:** 2026-08-03
- **Deciders:** vinary-viewer maintainers

## Context

ADR-0027 made `ssh://` and `sftp://` documents readable over SFTP, but live content refresh required
opt-in polling and remote opens deliberately offered no Files tree. ADR-0034 subsequently established a
bounded tree-refresh protocol: the renderer reports only visible roots and effectively expanded
directories, and main owns one shared shallow watcher per expanded scope.

When a compatible vinary-viewer daemon is already running on the target host, it can observe its local
filesystem accurately and reuse ADR-0034. The remaining problem is a secure, cross-platform event channel.
Unix-domain forwarding is not portable to Windows targets, and SSH transport authentication alone does not
prove that a process which acquired a stale advertised loopback port is the intended target daemon.

## Decision

Every running GUI daemon publishes an ephemeral **loopback TCP** endpoint and an owner-readable descriptor
at `~/.vinary-viewer/runtime/daemon-events.json`. The source reads that descriptor through its existing,
host-key-verified SFTP connection and reaches the endpoint with SSH `direct-tcpip` forwarding. The listener
is never bound to a LAN interface.

The descriptor contains a process-lifetime random 256-bit secret, daemon identity, endpoint port, path
codec, version metadata, and capabilities. It is atomically replaced with mode `0600` under a mode `0700`
runtime directory on POSIX; Windows relies on the user's inherited profile ACL. It is removed only by the
daemon which wrote the matching identity and secret.

Before accepting requests, both daemons perform the `vv-events/2` nonce-bound HMAC-SHA-256
challenge/response:

1. source sends its identity, nonce, and the descriptor's canonical SFTP path;
2. target maps that path with its declared codec and verifies that it resolves to the descriptor file it
   actually published; a chrooted or virtual SFTP namespace therefore fails closed;
3. target returns its descriptor identity and a fresh nonce;
4. source proves possession of the descriptor secret over the complete transcript, including the canonical
   descriptor path; and
5. target returns a distinct proof bound to both nonces, that path, and the new session id.

Proof comparison is constant-time. Failed, oversized, malformed, or timed-out handshakes are closed. The
secret never crosses renderer IPC and never persists past the target process lifetime. SSH still supplies
confidentiality, host authentication, and user authentication; the HMAC layer authenticates the advertised
application endpoint.

After authentication, one newline-framed request/event channel carries:

- retained-content subscribe/unsubscribe and content invalidations;
- tree open, visible-root sync, effective-expanded-scope sync, scoped/full refresh, and owner release;
- scoped/full tree payloads produced after target-side watcher events; and
- heartbeat/reconnect state.

File bytes do **not** travel on this channel. A content invalidation makes the source re-read the URI through
SFTP, preserving the content service's existing size, streaming, parsing, and error bounds.

After the descriptor identity check proves that SFTP and the daemon share a filesystem namespace, the target
maps SFTP-visible absolute paths to platform-native paths using a declared POSIX or Windows OpenSSH codec.
Tree roots, relative files, and scopes are mapped back into encoded `ssh://`/`sftp://` URIs before they reach
the renderer. Distinct source authorities (including `ssh://` and `sftp://` spellings of one connection) have
distinct remote owners, so every pushed tree retains its originating URI namespace while the target still
shares the underlying shallow watcher. Refresh paths are checked against roots already offered to that
authenticated session, just as local renderer requests are checked against roots offered to that window.

Remote sessions are ordinary owners of ADR-0034's existing tree state. The target creates exactly the same
shallow watcher for each effectively expanded directory; collapsed, hidden, released, disconnected, or dead
sessions release ownership. The source never tries to Chokidar-watch a URI locally.

Connections reconnect with bounded exponential backoff and restore desired content/tree ownership. Existing
`:remote {:poll-seconds …}` polling remains an opt-in compatibility fallback for document content while target
daemon discovery or the channel is unavailable. Without a compatible target daemon, remote documents still
open over SFTP but no project tree is synthesized from client-side recursive listing.

## Consequences

- Opening a remote file against a compatible target daemon now offers its target-side git repository or
  synthetic containing-directory tree in Files.
- Remote tree expansion, project refresh, Refresh All, add/delete/rename, and `.gitignore` membership changes
  have the same semantics and bounded watcher count as local trees.
- Content refresh is event-driven when possible and retains the prior opt-in polling fallback.
- The target gains a loopback listener and a private capability descriptor; compromise of the target user
  account remains sufficient to read that user's files and descriptor, which matches the SSH trust model.
- A daemon version without this protocol remains interoperable for ordinary SFTP reads but cannot supply a
  remote Files tree or event-driven invalidations.
- An SFTP chroot or virtual namespace whose canonical descriptor path does not identify the daemon's actual
  local descriptor fails the handshake. Ordinary SFTP opens and optional polling continue, but target-side
  events and trees stay disabled instead of guessing at a filesystem mapping.

## Testing

`test/daemon-events-smoke.js` exercises the real loopback endpoint, descriptor permissions and cleanup,
both negative sides of the mutual proof transcript, SFTP-namespace rejection, framed requests, POSIX/Windows
path codecs, reconnect state restoration, cancellation during discovery, no-target fallback, and an in-process
SSH2 SFTP fixture. The fixture verifies descriptor discovery
over SFTP, direct-TCP forwarding, authenticated invalidation delivery, tree conversion, expansion sync, and
owner release without an external host.

`test/remote-daemon-events-e2e.js` boots the real Electron main and renderer around that SSH2 fixture. It proves
content re-rendering, `ssh://` and `sftp://` project arrival and independent updates, target-side add/rename/
delete delivery, manual project refresh, Refresh All, collapsed-scope watcher release, and refresh on
re-expansion through the full production path.

## Alternatives considered

- **Unix-domain/stream-local forwarding.** Rejected because Windows OpenSSH targets need a portable endpoint.
- **Trust the forwarded port without an application handshake.** Rejected because a stale descriptor/port
  reuse could connect the source to the wrong target process.
- **Assume SFTP absolute paths equal target-local paths.** Rejected because a chroot or virtual SFTP namespace
  could make a valid SFTP path name an unrelated local file when interpreted by the daemon.
- **Send file contents over the event channel.** Rejected because SFTP already owns authenticated reads and
  the content service already enforces the required bounds.
- **Recursively list/watch remote trees from the source.** Rejected because it duplicates the target's local
  filesystem knowledge and violates ADR-0034's expansion-scoped watcher policy.
- **Make polling mandatory.** Rejected because it creates idle network work and cannot provide atomic tree
  refresh semantics.
