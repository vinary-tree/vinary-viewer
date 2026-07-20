# Configuration

Runtime configuration lives under `~/.config/vinary-viewer/` unless
`XDG_CONFIG_HOME` is set. The main process owns reading, writing, and watching
these files; the renderer receives plain EDN text or plain data over the
`window.vv` mediator.

---

## 1. Configuration directory

| Path | Status | Purpose |
|------|--------|---------|
| `settings.edn` | Available | Theme, fonts, and sidebar width/visibility. |
| `keybindings.edn` | Available | Active keymap set and custom keymap definitions. |
| `recent.edn` | Available | Recent-navigation memory: the dir→child `:trail` and the `:recent-files` MRU. |
| `window.edn` | Available | Main-window geometry: position, size, and maximized state. |
| `filetypes.edn` | Available | Filename and pattern mappings to source grammar ids. |
| `connections.edn` | Available | Non-secret SSH connection metadata (host aliases, last path, recent-remote MRU). **Never** holds passwords, passphrases, or keys. |
| `extensions.edn` | Available | Ad-block enable state and filter lists, plus installed scoped-extension state. |
| `grammars/<lang>/grammar.wasm` | Available | Optional tree-sitter grammar for a source language. |
| `grammars/<lang>/highlights.scm` | Available | Optional highlight query for that grammar. |

The installer does not remove this directory. `./uninstall.sh` removes only the
launchers.

---

## 2. Settings

Open `Settings > Preferences...` to change the variable-width (prose), LaTeX-preview,
and fixed-width (code) font families and sizes, and to toggle Fira Code programming
ligatures. Use `Settings > Theme` to change the active theme.

Settings persist as EDN (the keys below are exactly those the Preferences UI writes):

```clojure
{:theme "spacemacs-dark"
 :font-variable "Noto Sans, system-ui, sans-serif"   ; prose / Markdown / UI body font
 :font-latex "Latin Modern Roman, Georgia, serif"    ; prose font for LaTeX (.tex) previews only
 :font-size 15                                        ; document font size (px)
 :font-fixed "Fira Code, JetBrains Mono, monospace"  ; code / logs / tables (monospace)
 :code-font-size 13                                   ; code font size (px)
 :code-ligatures? false                               ; Fira Code programming ligatures (default off)
 :sidebar-width 280            ; sidebar width in px
 :sidebar-visible? true        ; sidebar open / closed
 :stream? true}                ; progressive rendering of large documents
```

Every font key is optional: an absent key falls back to the built-in default baked into
`app.css` (Noto Sans / Latin Modern Roman / Fira Code), and an absent `:code-ligatures?`
means ligatures are off. Unknown keys are merged into the settings map but only known UI
settings have visible effects. Beyond fonts and theme, `settings.edn` also remembers the
**sidebar** width and open/closed state, so the shell reopens the way you left it.

---

## 3. Themes

The renderer loads one structural stylesheet plus one theme palette:

```html
<link id="vv-theme-link" rel="stylesheet" href="css/themes/spacemacs-dark.css">
<link rel="stylesheet" href="css/app.css">
```

`app.css` contains layout rules and references only `--vv-*` custom properties
for colors. Each file under `resources/public/css/themes/` defines the same
token set for a palette. Theme switching updates `#vv-theme-link.href` and saves
the chosen theme to `settings.edn`.

To add a theme:

1. Copy an existing palette CSS file.
2. Keep every `--vv-*` token defined.
3. Add the new theme id and label to the theme list in the UI.
4. Rebuild the renderer.

---

## 4. Keybindings

Use `Settings > Key Bindings` to switch sets and
`Settings > Key Bindings > Customize...` to edit them. The editor persists to
`keybindings.edn` and external edits are live-reloaded.

Minimal valid envelope:

```clojure
{:active "default"
 :order []
 :sets {}}
```

See [04-keyboard-shortcuts.md](04-keyboard-shortcuts.md) for the schema and
resolver model.

---

## 5. Grammar registry

Source previews use bundled grammar metadata and optional user grammars. A user
grammar lives at:

```text
~/.config/vinary-viewer/grammars/<lang>/grammar.wasm
~/.config/vinary-viewer/grammars/<lang>/highlights.scm
```

The main process loads registry entries and sends them to the renderer over
`vv:grammars`. The renderer uses web-tree-sitter for highlighting when a grammar
matches the opened source file. Files without a matching grammar still open in a
read-only CodeMirror view.

`filetypes.edn` maps extensionless or conventionally named files to existing
grammar ids:

```clojure
{:filenames {"Cargo.lock" "toml"
             "tool.lock" "toml"}
 :patterns {"*.service" "toml"
            "config/*.lock" "json"}}
```

The built-in registry already maps `Cargo.lock` to the `toml` grammar. User
filename mappings match basenames exactly. User patterns are globs: `*` matches
inside one path segment, `?` matches one character, and `**` can span
directories. Patterns with `/` match the normalized path; patterns without `/`
match only the basename.

Mappings are accepted only when the target resolves to a bundled or user grammar
id, language alias, or extension. Unresolved mappings are ignored.

---

## 6. Recent navigation and window geometry

**`recent.edn`** holds two pieces of navigation memory, managed by
`vinary.main.recent` (it mirrors `settings.edn`: raw EDN text over IPC, watched for
external edits, written back debounced):

```clojure
{:trail        {"/home/me/docs" "/home/me/docs/report.md"
                "/home/me"      "/home/me/docs"
                "/"             "/home"}
 :recent-files ["/home/me/docs/report.md" "/home/me/notes.md"]}
```

| Key | Meaning |
|-----|---------|
| `:trail` | A `directory → last-opened-child` map. It is what makes `Alt+Up` then `Alt+Down` return to the most-recently-opened file. Bounded to the 200 most-recent directories. |
| `:recent-files` | The most-recently-used opened **files** (not directories or URLs), capped at 10, surfaced under File ▸ Open Recent. **Clear Recent** empties this list. |

**`window.edn`** records the main window's geometry so it reopens where you left it,
handled entirely in the main process (`vinary.main.window`):

```clojure
{:x 120 :y 80 :width 1280 :height 860 :maximized? false}
```

A saved position that is no longer on any connected display is dropped, so the
window can never reopen off-screen; the normal (non-maximized) bounds are stored so
un-maximizing restores the previous size.

---

## 7. Security boundary

Configuration files are local input. Keep these rules:

| Boundary | Rule |
|----------|------|
| Filesystem | Main process reads config and sends plain data/EDN over IPC. |
| Renderer | Renderer does not gain `fs` access to read config directly. |
| Filetypes | Treat mappings as local classification hints; unresolved targets are ignored. |
| Grammars | Treat WASM grammars and highlight queries as user-supplied code/data at a trust boundary. |
| Theme CSS | Keep theme files local and define the full token set. |

Update [../security/threat-model.md](../security/threat-model.md) when adding a
new config file, IPC capability, or external loader.

## Remote files over SSH

Open remote files and directories with `ssh://[user@]host[:port]/path` (or the `sftp://` alias) — typed in
the address bar, passed on the command line (`vv ssh://host/notes.md`), or clicked in a remote directory
listing. See [../design-decisions/0027-remote-files-over-ssh.md](../design-decisions/0027-remote-files-over-ssh.md).

- **Authentication** reuses your existing SSH setup: the running `ssh-agent`, key files under `~/.ssh/`
  (with a passphrase prompt for encrypted keys), and `~/.ssh/config` (`Host`/`HostName`/`User`/`Port`/
  `IdentityFile`/`ProxyJump`/`Match`/`Include`). If those don't suffice, you're prompted for a password /
  passphrase / one-time code. **Secrets are never persisted** — they live only in the running process.
- **Host keys** are verified against `~/.ssh/known_hosts`; an unknown host prompts once (trust-on-first-use)
  and, on accept, is appended there. A **changed** host key is rejected outright.
- **`connections.edn`** stores only non-secret host metadata (aliases, recently-opened remotes).
- **Live-refresh** for remote docs is **opt-in polling** (SFTP has no file-change events). Enable it in
  `settings.edn`:

```clojure
{:remote {:poll-seconds 4      ; poll a remote doc every N seconds; 0 or absent = off (the default)
          :poll-dirs? false}}  ; also poll open directory listings (heavier); default false
```

Polling backs off (to 60 s) and jitters on error so a downed host is not hammered; closing the tab stops it.

---

*Next: [06-troubleshooting.md](06-troubleshooting.md).*
