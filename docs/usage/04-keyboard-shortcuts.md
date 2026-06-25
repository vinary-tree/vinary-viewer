# Keyboard shortcuts

This page lists the keyboard shortcuts that work **today**, then describes the richer **vim/emacs
custom-keybinding system that is currently now available**. Each current binding is shown with the
exact re-frame event it dispatches, so the behavior is unambiguous.

---

## 1. Current bindings (Available now)

Global keys are installed by `vinary.renderer.core/keybindings!` (a single `window` `keydown` listener).
The in-page find bar adds its own input-scoped keys while it is focused (`vinary.ui.views/find-bar`).

### 1.1 Global

| Keys        | Action                  | Dispatches            | Notes                                                                 |
|-------------|-------------------------|-----------------------|----------------------------------------------------------------------|
| `Ctrl`+`F`  | Toggle in-page find     | `[:find/toggle]`      | Opens the find box (auto-focused); pressing it again closes + clears. `preventDefault` suppresses the browser's native find. |
| `Alt`+`←`   | History **back**        | `[:history/back]`     | Goes to the previously viewed document; no-op (disabled) at the start of history. |
| `Alt`+`→`   | History **forward**     | `[:history/forward]`  | Goes to the next document in history; no-op at the end / after a new open truncated the forward branch. |

```clojure
;; vinary.renderer.core/keybindings! — the exact dispatch table
(cond
  (and (.-ctrlKey e) (= (.-key e) "f")) (do (.preventDefault e) (rf/dispatch [:find/toggle]))
  (.-altKey e) (case (.-key e)
                 "ArrowLeft"  (do (.preventDefault e) (rf/dispatch [:history/back]))
                 "ArrowRight" (do (.preventDefault e) (rf/dispatch [:history/forward]))
                 nil)
  :else nil)
```

### 1.2 In-page find bar (only while the find input is focused)

| Keys           | Action                | Dispatches              | Notes                                            |
|----------------|-----------------------|-------------------------|--------------------------------------------------|
| `Enter`        | Next match            | `[:find/cycle 1]`       | Wraps from last → first.                         |
| `Shift`+`Enter`| Previous match        | `[:find/cycle -1]`      | Wraps from first → last.                         |
| `Esc`          | Close find            | `[:find/close]`         | Hides the bar and clears all highlights.         |
| *(typing)*     | Update the query      | `[:find/set-query q]`   | Recomputes + repaints matches on every keystroke.|

The find bar also has on-screen buttons that dispatch the same events: **↑** (`[:find/cycle -1]`),
**↓** (`[:find/cycle 1]`), and **×** (`[:find/close]`). The counter shows `current/total`.

### 1.3 Toolbar buttons (mouse equivalents)

| Button | Action          | Dispatches            |
|--------|-----------------|-----------------------|
| **←**  | History back    | `[:history/back]`     |
| **→**  | History forward | `[:history/forward]`  |
| theme `<select>` | Switch theme | `[:theme/set <name>]` |

> **Why so few global keys today?** The baseline keymap is intentionally minimal — `Ctrl+F` and
> `Alt+←/→` — because the full, configurable keybinding system (below) is being built to supersede and
> generalize it. Treat the table above as the **stable floor** that will keep working as the larger
> system lands.

---

## 2. Custom vim/emacs keybindings — now available

A configurable, modal keybinding system (vim- and emacs-style) is **now available**. Parts of its
plumbing already exist in the codebase; the user-facing surface (preset keymaps, the chord/modal
resolver, the command palette, and the on-disk config) is **not finished yet**. This section documents
the intended model and clearly marks what is already present versus forthcoming, so you can follow the
design without mistaking it for a shipped feature.

### 2.1 The model: a command registry + keymaps + a resolver

The design follows three layers, mirroring how editors like Emacs and Vim separate *what* from *how*:

```text
   keys ──▶  Resolver  ──▶  Command  ──▶  re-frame event(s)
            (modal/chord)   (named,        (the actual effect)
                            in a registry)
```

1. **Command registry** *(planned surface)* — a table of **named commands**
   (e.g. `:tab/next`, `:theme/cycle`, `:sidebar/toggle`, `:nav/scroll`), each mapping to the re-frame
   event(s) it dispatches. Commands are the stable, user-referenceable vocabulary; keymaps bind keys to
   *command names*, never directly to events.
2. **Keymaps / presets** *(planned surface)* — named bindings from key sequences to commands. Shipped
   presets are intended for **vim** (modal: a `:normal` mode where `j`/`k` scroll, `gt`/`gT` switch
   tabs, `/` opens find, etc.) and **emacs** (chords: `C-x C-f`, `M-x` command palette, etc.). The
   active preset and any user overrides come from configuration (§2.4).
3. **Resolver** *(planned surface)* — a modal/chord state machine that consumes `keydown` events,
   accumulates a pending key **sequence**, tracks the current **mode** (`:normal`, etc.) and a numeric
   **count** prefix, and dispatches the resolved command (or waits for more keys, or times out a partial
   chord). It must also know when focus is in a text input (so keys type instead of command).

### 2.2 What already exists in the code (the substrate)

The following building blocks are **already present** — they are the half of the system that does not
depend on the resolver/registry surface:

- **Ephemeral input state in `app-db`** (`vinary.app.db`): `:ui :input {:mode :normal :sequence []
  :count nil :in-input? false :timeout-id nil}` — the slots a modal resolver needs (current mode,
  pending key sequence, count prefix, an "am I in a text input?" flag, and a partial-chord timeout id).
- **A command-palette state slot** (`vinary.app.db`): `:ui :palette {:open? false :source :command
  :prefix "" :query "" :items [] :selected 0}` — for a future fuzzy command/file palette.
- **Input subscriptions** (`vinary.app.subs`): `:input/mode`, `:input/pending`, `:input/in-input?`,
  `:palette/state` — so views can reflect the current mode and pending chord.
- **Command-target events** (`vinary.app.events`) that the registry will bind to:
  `:tab/next`, `:tab/prev`, `:sidebar/toggle`, `:theme/cycle`, `:nav/focus`, `:nav/scroll`,
  `:doc/open-in-tab`, `:tree/move`, `:tree/activate`. These already work when dispatched directly.
- **Navigation effects** (`vinary.input.fx`): `:dom/scroll` and `:dom/focus`, which the
  scroll/focus commands rely on. `:dom/scroll` understands `:top`/`:bottom`, `:page`/`:-page`,
  `:half`/`:-half`, and absolute pixel deltas; `:dom/focus` targets `:tree`, `:content`, or `:toggle`
  (toggle moves focus between the tree filter and the content pane).

In other words, the **commands and their effects exist and are dispatchable now**; what is being
implemented is the **registry + preset keymaps + resolver + palette UI + config loader** that bind keys
to those commands.

### 2.3 What is NOT built yet (Forthcoming)

- The **command registry** namespace and the **preset keymaps** (vim / emacs) — there is no
  `keymap`/`commands`/`resolver` namespace under `src/vinary/input/` yet (only `input/fx.cljs`). The
  `app-db` comment that mentions *"the keymap itself lives in `vinary.input.keymap`'s atom"* describes
  the **intended** home for the keymap; that namespace is **not present yet**.
- The **modal/chord resolver** wired into the global `keydown` path (today `keybindings!` handles only
  the fixed `Ctrl+F` / `Alt+←/→` keys).
- The **command palette** UI (the `:ui :palette` state exists; the view + fuzzy matcher do not).
- The **on-disk config** (`~/.config/vinary-viewer/keybindings.edn`) and its loader (§2.4).

Until these land, use the **Available now** bindings in §1.

### 2.4 Planned configuration: `~/.config/vinary-viewer/keybindings.edn`

The intended configuration surface — **Forthcoming (planned)** — is a single EDN file read at startup:

```clojure
;; ~/.config/vinary-viewer/keybindings.edn   — PLANNED shape (not yet read by the app)
{:preset :vim                 ; or :emacs — the base keymap to start from
 :bindings                    ; user overrides, layered on top of the preset
 {"g t"   :tab/next           ; key sequence → command name (from the registry)
  "g T"   :tab/prev
  "C-x b" :doc/open-in-tab
  "/"     :find/toggle
  "z z"   [:nav/scroll {:to :center}]}}
```

The loader will read this file (if present), select the named preset, and layer the user `:bindings`
over it; absent the file, the chosen default preset applies. **None of this is wired yet** — the path,
the loader, and the EDN schema above are the planned design, documented here so the eventual config is
predictable.

---

## 3. Quick reference

### Works today

```text
Ctrl+F            toggle find          → [:find/toggle]
Alt+Left          history back         → [:history/back]
Alt+Right         history forward      → [:history/forward]

(find bar focused)
Enter             next match           → [:find/cycle 1]
Shift+Enter       previous match       → [:find/cycle -1]
Esc               close find           → [:find/close]
```

### Available now (do not rely on yet)

```text
modal vim/emacs keymaps · command registry · chord/modal resolver
command palette · ~/.config/vinary-viewer/keybindings.edn
```

---

*Next: [05-configuration.md](05-configuration.md).*
