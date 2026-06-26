# Keyboard shortcuts

vinary-viewer has a current, configurable keybinding system: bundled Standard,
Vim, and Emacs keymap sets; a modal/chord resolver; a command registry; a visual
editor; live switching; and persisted user keymaps.

---

## 1. Keymap sets

Use `Settings > Key Bindings` to select the active set.

| Set | Intent |
|-----|--------|
| Standard | Browser-like defaults for users who do not want modal editing keys. |
| Vim | Vimium-style navigation, normal/insert mode behavior, counts, leader-style sequences, and link hints. |
| Emacs | Emacs-style chords and command navigation. |
| Custom sets | User-created sets that extend a bundled set and override bindings. |

The active set is installed into `vinary.input.keymap` and the resolver reads it
on every `keydown`.

---

## 2. Resolver model

The keybinding pipeline separates input, command names, and effects:

```text
DOM keydown
  -> vinary.input.keys tokenization
  -> vinary.input.resolver modal/chord state machine
  -> vinary.app.commands command registry
  -> re-frame dispatch and effects
```

The resolver can:

| Decision | Meaning |
|----------|---------|
| `:dispatch` | A complete key sequence resolved to a command. |
| `:prefix` | The sequence is valid but needs more keys. |
| `:consume` | The key is swallowed by the current mode. |
| `:pass` | Let the browser/input element handle the key. |
| `:retry` | Reset the pending sequence and try the current token from the root. |

Transient resolver state such as mode, pending sequence, count prefix, and input
focus lives in `app-db` for display and inspection. The active keymap data lives
in an atom because it is comparatively static and read synchronously by the
resolver.

---

## 3. Common bindings

The exact bindings come from `resources/keymaps/*.edn` plus user overrides. The
stable actions available across the current UI include:

| Action | Typical binding |
|--------|-----------------|
| Find | `Ctrl+F` in Standard; `/` in Vim normal mode. |
| History back/forward | `Alt+Left` / `Alt+Right`, toolbar buttons, and mouse thumb buttons. |
| Switch tabs | Keymap commands backed by `:tab/next` and `:tab/prev`. |
| Scroll | Keymap commands backed by `:nav/scroll`. |
| Toggle sidebar | Command backed by `:sidebar/toggle`. |
| Open command palette | Command backed by palette state and command registry. |
| Link hints | `f` in Vim normal mode. |

Use the visual editor for the authoritative active binding list; it reads the
same registry the resolver uses.

---

## 4. Visual editor

Open `Settings > Key Bindings > Customize...`.

The dialog has two panes:

| Pane | Behavior |
|------|----------|
| Left | Lists built-in sets first, then custom sets. Built-ins are read-only. Custom sets can be cloned, renamed, reordered, and removed. |
| Right | Shows commands grouped by category and the bindings for the selected set. |

The editor supports key capture, emacs-style modifier chips, clone/rename,
drag-reorder, context menus, and undo/redo with `Ctrl+Z` and `Ctrl+Shift+Z`.
Changes apply live and are persisted.

---

## 5. On-disk format

User keymaps persist to:

```text
~/.config/vinary-viewer/keybindings.edn
```

The current envelope is:

```clojure
{:active "default"
 :order ["My Vim"]
 :sets {"My Vim"
        {:extends :vim
         :keymaps {:normal {"g t" :tab/next
                            "/"   :find/toggle}}}}}
```

Fields:

| Field | Meaning |
|-------|---------|
| `:active` | Active set id. Built-ins use `"default"`, `"vim"`, and `"emacs"`. |
| `:order` | Display order of custom set ids. |
| `:sets` | Map of custom set id to a delta over a bundled preset. |
| `:extends` | Preset base, usually `:default`, `:vim`, or `:emacs`. |
| `:keymaps` | Mode-to-binding overrides for that custom set. |

The main process watches this file. Editing it externally re-pushes the EDN over
`vv:keymap`, the renderer normalizes it, and the active keymap is reinstalled.
The visual editor writes the same file through `vv:keymap-save`.

---

## 6. Mouse and menu equivalents

Not every user relies on keys. The command surface is intentionally shared:

| UI surface | Uses the same command/event model |
|------------|-----------------------------------|
| Toolbar Back/Forward | `:history/back`, `:history/forward` |
| Mouse back/forward buttons | History navigation sent from main to renderer |
| Menu bar | Dispatches the same re-frame events as keybindings |
| Command palette | Uses `vinary.app.commands` entries |
| Keybinding resolver | Dispatches command registry entries |

---

*Next: [05-configuration.md](05-configuration.md).*
