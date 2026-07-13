# 29 — Remote files & directories over SSH (`ssh://` / `sftp://`)

vinary-viewer can open a **file or a directory on a remote host** over SSH — `ssh://[user@]host[:port]/path`
and its `sftp://` alias — and preview it through the **same** pipeline, renderers, streaming, paging, and
live-refresh as a local path. A remote URI is a *virtual backend*, modeled exactly on the `vv-archive://`
scheme: the address is opened, never mirrored to a temp directory, so browsing a remote repo feels identical to
browsing a local one. Everything runs in the **trusted main process** — the sandboxed renderer never touches a
socket, a private key, or a passphrase. See
[ADR-0027](../design-decisions/0027-remote-files-over-ssh.md) for the full design.

![The sandboxed renderer sends an ssh:// URI to main, whose content service opens it through the ssh2 transport (pool, auth, host-key, SFTP); only non-secret metadata crosses the window.vv seam](../diagrams/component-remote-backend.svg)

*Diagram source: [`../diagrams/component-remote-backend.puml`](../diagrams/component-remote-backend.puml).*

## What you get

| Capability | Behaviour |
|---|---|
| **Open a remote file** | `vv ssh://user@host/path/report.md`, the address bar, a directory-listing click, or a link. The file classifies off its basename extension and renders through its usual renderer — Markdown, PDF, source, office, table, log, image, diff. |
| **Browse a remote directory** | A remote directory lists in the in-pane [directory browser](16-directory-browser.md); each entry carries a **child `ssh://` URI**, so click-to-open, breadcrumbs, and `Alt+Up` parent navigation all work unchanged. |
| **Authentication** | ssh-agent, key files (with **passphrase prompts** + optional `AddKeysToAgent`), full `~/.ssh/config` (`Host` / `Match` / `Include` / `ProxyJump` / `%`-tokens), and **keyboard-interactive** (MFA) / password prompts. |
| **Host-key trust** | Verified against `~/.ssh/known_hosts` with a **trust-on-first-use** prompt on an unknown key (a native dialog in the GUI, a `yes/no` in the terminal) and a **hard reject** of a *changed* key. |
| **Large remote logs stream** | A big remote log / text file streams in **bounded memory** over SFTP; a mid-stream connection drop keeps the partial content (never a silent truncation). |
| **Live refresh (polling)** | SFTP has no inotify, so a remote doc is kept fresh by an **opt-in poller** (`settings.edn` `:remote :poll-seconds`) with exponential backoff + jitter. |
| **Everything a local path does** | Relative image assets (inlined as `data:` URLs over SFTP), Document↔PDF siblings, side-by-side diff enrichment, remote archive browsing, and **live-rendered remote HTML** (via a privileged `vv-remote://` scheme). |
| **Terminal parity** | `vv --cli ssh://…` and `vv --tui ssh://…` open remote URIs too, with TTY-gated auth prompts. |

Both `ssh://` and `sftp://` are accepted as **equivalent aliases** — both drive the SFTP subsystem; the scheme
is retained only for display. There is no IETF-standardized SSH-file URI scheme; these are the widely-supported
de-facto ones (Git/GIO use `ssh://`; GVfs/KDE/WinSCP/libcurl use `sftp://`).

## How it works

### A remote URI is a virtual backend

An `ssh://` URI is **addressed, never mirrored** (the same anti-path-traversal rationale as archives), and
flows through the *same* open pipeline as a local path. The renderer changes are minimal:

- `vinary.app.uri` gains `ssh?` / `sftp?` / `remote?` predicates; `file-path` **preserves** a remote URI (like
  `archive?`), and `http?` is false, so it renders as **document content**, not the web view. Authority-aware
  `display` / `basename` / `dirname` / `segments` keep the `user@host:port` authority intact, so breadcrumbs,
  tab labels, and parent navigation behave exactly as on a local path.
- `vinary.main.file-kind` gains a `remote-uri?` predicate; `kind-of` needs no new arm — it classifies off the
  basename extension, already correct on the ssh tail.
- A remote directory's listing entries carry **child `ssh://` URIs**, so `dir-view` and `:doc/open` reuse work
  with zero component change.

### One remote reader — `openRemoteUri`

The pure router's `directory?` predicate is a synchronous `statSync`, but a remote stat is a network
round-trip. So the async is confined to one seam: `content_service.js/openRemoteUri(uri, kind)` — a parallel of
`openArchiveUri` — does the `remoteStat` **internally** to decide list-vs-read and to fill `meta.size`, and
`service.cljs`'s remote arm is just an async `.then` / `.catch` delegation (`send-remote-content!`). No async
ever leaks into the pure router.

`openRemoteUri` produces the **identical payload contract** and reuses every existing parser (image → dataUrl,
pdf → bytes, office / table / log parsers, text sniff) plus the archive machinery. The grammar-aware `kind` is
threaded from CLJS so a remote `.rs` renders as highlighted source, not sniffed text.

![Opening a remote file: main resolves ~/.ssh/config, connects and authenticates through the pooled ssh2 client, verifies the host key, then stats and reads over SFTP and returns the same payload a local read would](../diagrams/seq-ssh-open.svg)

*Diagram source: [`../diagrams/seq-ssh-open.puml`](../diagrams/seq-ssh-open.puml).*

> **`meta.size` is load-bearing, not decorative.** Streaming is gated on `(>= size threshold)`; a remote
> payload that omitted `meta.size` would make large remote logs / markdown **silently never stream** — the
> same class of bug the local `:text` route's `:meta {:size}` fixed ([ADR-0018](../design-decisions/0018-document-streaming-pipeline.md)).

`open!` skips the git-tree sidebar and the chokidar watch for a remote URI (a remote path is not a local repo
and cannot be inotify-watched); URI-bar completion gains an async remote-directory branch; and
`vv:load-pdf-bytes` / `vv:load-diff-sources` gain remote branches over SFTP.

### Authentication, host-key trust, and `~/.ssh/config`

The transport (`ssh_transport.js`) drives ssh2's async `authHandler` — first success wins — over a candidate
chain: a `none` probe (to learn the server's methods), then **agent**, then **key files** (config
`IdentityFile`, then `~/.ssh/id_ed25519|ecdsa|rsa|dsa`), then full multi-prompt **keyboard-interactive**, then
**password** (up to three prompts). An encrypted key lazily prompts a passphrase and, if `AddKeysToAgent` asks,
is added to the running agent via the SSH-agent `ADD_IDENTITY` wire protocol (`ssh_agent.js`, covering
ed25519 / RSA / ECDSA — ssh2 has no client-side agent-add).

![Host-key verification (exact-match accept, changed-key hard reject, unknown-key trust-on-first-use) followed by the auth chain — none probe, agent, key files with passphrase prompts, keyboard-interactive, then password](../diagrams/activity-ssh-auth.svg)

*Diagram source: [`../diagrams/activity-ssh-auth.puml`](../diagrams/activity-ssh-auth.puml).*

- **`~/.ssh/config`** is parsed by a hand-rolled resolver (`ssh_config.js`) honoring `HostName` / `User` /
  `Port` / `IdentityFile` / `IdentitiesOnly` / `ProxyJump` / `AddKeysToAgent`, `Match`
  (`host` / `user` / `originalhost` / `exec` / `all` / `final` / `canonical`), and `Include` (glob-expanded,
  recursive, depth-bounded), with `%h` / `%p` / `%r` / `%n` / `%u` token expansion — resolved **two-pass**
  (OpenSSH-style), first-value-wins.
- **ProxyJump / multi-hop**: each hop is its own pooled connection; the chain is built with `client.forwardOut`
  and the resulting stream is passed as the next target's `sock`.
- **Host-key verification** consults `~/.ssh/known_hosts` (plain, `[host]:port`, and `|1|` hashed entries) via
  the pure `checkHostKey`, which returns `ok` (exact match → accept), `changed` (same type, different key →
  **hard reject**, `REMOTE HOST IDENTIFICATION … HAS CHANGED`), `revoked`, or `unknown` (→ the TOFU prompt,
  showing the OpenSSH **SHA256 fingerprint**; on accept, the key is appended to `~/.ssh/known_hosts`).

### Streaming, paging, and polling live-refresh

- **Streaming** substitutes an SFTP read-stream for `fs.createReadStream` — a drop-in `Readable`, so the
  credit-1 pull cursor and the whole session are otherwise identical. Only the **transport engine** (log / text)
  needs this; the **progressive engine** (markdown / org / latex) re-parses the already-delivered `:doc/text`
  and opens no stream.
- **Mid-stream drop → partial, never silent truncation.** A dropped connection destroys in-flight streams
  **with an error**, so the read surfaces the drop rather than a clean EOF (which would look like a complete
  read and truncate content undetected). The scheduler keeps every committed block and shows a non-fatal
  `:doc/stream-note`; it **never** sets `:doc/error` (which would blank the streamed DOM).
- **Paging** reads only up to the requested window over SFTP; the LRU page cache keys on the `ssh://` URI
  verbatim.
- **Polling live-refresh.** A per-doc poller re-stats the URI and, on a size/mtime change, re-sends it (a fresh
  stamp → the renderer remounts / re-streams). It is **opt-in** via `settings.edn` `:remote {:poll-seconds …}`,
  with exponential backoff (to 60 s) and ±25 % jitter so a downed host is not hammered; directory listings poll
  slower or not at all. The schedule, seeded at the configured base `$`d_0`$`:

```math
d_{n+1} = \min\!\left(2\,d_n,\ 60\,\text{s}\right), \qquad \text{next fire} \sim d_{n+1}\,\bigl(1 \pm 0.25\bigr)
```

  The poller's lifecycle is tied to `unwatch-file!`, so closing a tab stops the poll — the same guarantee
  local watchers give ([feature 01](01-live-refresh.md)) — and it bails if the tab closes mid-stat so a stale
  update cannot resurrect a zombie poller (and leak a connection).

### Remote assets, Document↔PDF, diffs, archives, and live HTML

Everything the local path does, a remote path now does:

- **Relative image assets** in a remote Markdown / Office doc are fetched over SFTP and inlined as `data:` URLs
  (`renderer.media/resolve-remote-media!`, over the `vv:load-remote-asset` bridge), for both the batch and the
  progressive-streamed render — neither the sandboxed renderer nor `file://` can reach the host.
- **Live-rendered remote HTML** via a privileged **`vv-remote://`** scheme registered on the web view's
  session (`vinary.main.web`): the whole ssh tree is remapped 1:1, so the page's relative CSS/JS/images resolve
  to `vv-remote://` URLs that main serves over SFTP — the SSH analog of loading an `http(s)` page. The scheme
  is registered `standard` + `secure` before app-`ready` in `vinary.main.core`.
- **Document↔PDF** siblings over remote (a remote `paper.tex` ↔ `paper.pdf`, both directions), the side-by-side
  **diff** enrichment ([feature 28](28-diff-rendering.md), resolved over SFTP by walking ancestors), and
  **archive browsing** (a remote `.zip` / `.tar` read whole into a buffer, then the existing archive seam).
- **Terminal parity**: `vv --cli` / `vv --tui` route remote URIs through `openRemoteUri`, with TTY-gated
  terminal auth prompts (a non-interactive / piped run relies on the agent / keys and declines a prompt).

### Secrets stay main-side

The transport is Electron-free: its two prompts and its error / status sinks are injected via `configure()`.
`vinary.main.ssh` wires them to Electron — the host-key trust prompt is a **native, secretless** dialog, and
the password / passphrase / MFA prompt round-trips a **non-secret** request to the renderer over
`vv:ssh-prompt`; the typed secret comes back on `vv:ssh-prompt-reply`, the **only** secret-bearing channel
(one-shot, resolved into a main-memory promise, never persisted or placed in app-db). The renderer's
`vinary.ui.ssh` prompt keeps the typed value in component-local reagent state only. This mirrors the
password-bridge doctrine.

## Key modules

| File | Role |
|---|---|
| `vinary.main.ssh_config` (JS, **pure**) | URI parsing, `~/.ssh/config` (`Host` / `Match` / `Include` + `%`-tokens), `known_hosts` matching, SHA256 fingerprints. No `fs` / `net`. |
| `vinary.main.ssh_transport` (JS) | ssh2 `Client` **pool**, the auth chain, `hostVerifier`, ProxyJump, `~user` resolution, the idle reaper, and the async SFTP API. Electron-free (injected callbacks). |
| `vinary.main.ssh_agent` (JS) | the SSH-agent `ADD_IDENTITY` wire protocol for `AddKeysToAgent` (ed25519 / RSA / ECDSA). |
| `vinary.main.ssh` | wires the transport's prompts to a native host-key dialog + a renderer secret modal; owns the `vv:ssh-*` channels. |
| `vinary.main.connections` | `connections.edn` — non-secret host metadata + a recent-remote MRU (a clone of `recent.cljs`). |
| `vinary.main.content_service` (`openRemoteUri`) | the single async reader: `remoteStat` → list-vs-read → the shared payload contract. |
| `vinary.main.service` (`send-remote-content!`, poller) | the async remote arm + the opt-in polling live-refresh. |
| `vinary.main.web` (`vv-remote://`) | serves remote HTML's assets over SFTP for the web view. |
| `vinary.app.uri` | `ssh?` / `sftp?` / `remote?` + authority-aware path helpers (renderer). |
| `vinary.ui.ssh` | the renderer auth-prompt modal + a connection-error toast (secret stays component-local). |
| `vinary.renderer.media` (`resolve-remote-media!`) | inlines a remote doc's relative images as `data:` URLs. |

**IPC channels** (confirmed in `resources/preload.js`): `vv:ssh-prompt` (main→renderer, non-secret request),
`vv:ssh-prompt-reply` (renderer→main, the one secret channel), `vv:ssh-error`, `vv:ssh-status`,
`vv:ssh-close-connection`, `vv:connections-request` / `vv:connections-save` / `vv:connections`, and
`vv:load-remote-asset`; plus the reused `vv:content`, `vv:content-page`, `vv:stream-open` / `vv:stream-pull` /
`vv:stream-close`, `vv:load-pdf-bytes`, and `vv:load-diff-sources`.

## Configuration

- **Live-refresh polling** — off by default. Enable in `settings.edn`:

  ```clojure
  {:remote {:poll-seconds 10        ; re-stat every ~10 s (backoff to 60 s + jitter); absent / ≤ 0 → no polling
            :poll-dirs? false}}      ; whether directory listings poll too
  ```

- **`~/.ssh/config`, keys, and `known_hosts`** are the user's existing OpenSSH files — the app reads them; it
  never writes its own copy. Accepted host keys append to `~/.ssh/known_hosts` (the standard location).
- **`connections.edn`** (`~/.config/vinary-viewer/connections.edn`) holds **addresses and preferences only** —
  aliases, hostnames, users, ports, last-used, per-host prefs — surfaced in **File ▸ Open Recent**. Secrets are
  never written here.
- **Terminal auth** — a piped / non-interactive `vv --cli ssh://…` cannot prompt, so it relies on the agent /
  keys; an interactive TTY prompts for a passphrase / password / MFA response.

## Security

The threat model ([threat-model.md](../security/threat-model.md)) is amended: a remote host is a **new
untrusted input source** — SFTP bytes and directory listings are a trust boundary like the local parsers, and
remote streamed content rides the **same** per-block GitHub-allowlist sanitizer as local content. The key
protections:

- **Secrets stay main-side** — no persistence; the only secret-bearing IPC is the one-shot
  `vv:ssh-prompt-reply`, resolved into a main-memory promise.
- **Host-key TOFU with change-rejection** — a first-connect MITM window, closed thereafter; a *changed* key is
  a hard reject.
- **URI authorities are validated at parse time** — a remote URI's host is restricted to `[A-Za-z0-9._:-]` and
  its user to `[A-Za-z0-9._-]`, and a `~user` path is validated to a strict POSIX username **before** any
  remote `echo ~user`. So **no URI-derived value can reach a shell** (a `Match exec` `%h` / `%r` token →
  `cp.execSync`) or any `~/.ssh/config` directive, even though a URI may be document-supplied. (`Match exec`
  itself runs a shell command *by design* — it is the user's own config file, the same file OpenSSH executes —
  bounded by a 5 s timeout.)
- **`vv-remote://` serves only file bytes over SFTP** — no arbitrary command execution.

## Edge cases & limitations

- **No inotify.** Remote live-refresh is polling, not push — it is opt-in and rate-limited; a change is seen on
  the next poll, not instantly.
- **`ssh2` crypto is Node's built-in `crypto`.** The optional native accelerator (`sshcrypto.node`) is never
  built, so there is no mandatory native build; pure-JS crypto is used where no accelerator is present.
- **Idle connections are reaped.** A pooled connection with no open streams is closed after ~5 minutes idle;
  the CLI closes pooled connections on exit so the process can terminate.
- **A whole-file read is capped** at 1 GiB; larger logs / text stream instead (they are not read whole).

## References / see also

- [ADR-0027 — Opening remote files & directories over SSH](../design-decisions/0027-remote-files-over-ssh.md)
- [ADR-0018 — Document-streaming pipeline](../design-decisions/0018-document-streaming-pipeline.md) ·
  [Theory 09 — Document streaming and the WPDA](../theory/09-document-streaming-and-the-wpda.md)
- [feature 16 — Directory browser](16-directory-browser.md) ·
  [feature 17 — Breadcrumb & up/down navigation](17-breadcrumb-and-up-down-navigation.md) ·
  [feature 18 — Address-bar completion](18-address-bar-completion.md)
- [feature 01 — Live refresh](01-live-refresh.md) ·
  [feature 25 — Content previews (office · tables · logs · archives)](25-content-previews.md)
- [feature 28 — Diff rendering](28-diff-rendering.md) ·
  [feature 27 — LaTeX & Document↔PDF](27-latex-rendering.md) ·
  [feature 30 — Terminal preview](30-terminal-preview.md)
- [feature 23 — Password-manager bridge](23-password-manager-bridge.md) (the secrets-main-only doctrine) ·
  [Security — threat model](../security/threat-model.md)
