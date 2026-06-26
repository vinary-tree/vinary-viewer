# Configuration

Runtime configuration lives under `~/.config/vinary-viewer/` unless
`XDG_CONFIG_HOME` is set. The main process owns reading, writing, and watching
these files; the renderer receives plain EDN text or plain data over the
`window.vv` mediator.

---

## 1. Configuration directory

| Path | Status | Purpose |
|------|--------|---------|
| `settings.edn` | Available | Theme and font preferences. |
| `keybindings.edn` | Available | Active keymap set and custom keymap definitions. |
| `filetypes.edn` | Available | Filename and pattern mappings to source grammar ids. |
| `grammars/<lang>/grammar.wasm` | Available | Optional tree-sitter grammar for a source language. |
| `grammars/<lang>/highlights.scm` | Available | Optional highlight query for that grammar. |

The installer does not remove this directory. `./uninstall.sh` removes only the
launchers.

---

## 2. Settings

Open `Settings > Preferences...` to change variable-width and fixed-width font
families and sizes. Use `Settings > Theme` to change the active theme.

Settings persist as EDN:

```clojure
{:theme "spacemacs-dark"
 :variable-font-family "Inter"
 :variable-font-size 16
 :fixed-font-family "JetBrains Mono"
 :fixed-font-size 14}
```

The exact keys are the settings currently written by the UI. Unknown keys are
merged into the settings map but only known UI settings have visible effects.

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

## 6. Security boundary

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

---

*Next: [06-troubleshooting.md](06-troubleshooting.md).*
