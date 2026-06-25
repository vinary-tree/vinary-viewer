# Custom Keybindings (vim / emacs / default) — *Available now*

> **Status:** Available now. A full, user-customizable keybinding system with preset **default**,
> **vim**, and **emacs** keymaps, a named **command registry**, a modal/chord/leader **resolver**, a
> **command palette / fuzzy finder**, and a live-reloaded **`~/.config/vinary-viewer/keybindings.edn`**.

## 1. What it is

vinary-viewer resolves every keystroke through a small, data-driven keybinding engine. Keys map to
**named commands** (e.g. `:tab/next`, `:file/open-in-new-tab`, `:search/start`) drawn from a single
**command registry** — the "API". Three bundled **keymaps** bind those commands in different styles:

- **`default`** — a non-modal keymap that reproduces and extends the original shortcuts
  (`Ctrl+F` find, `Alt+←/→` history, plus `Ctrl+Tab`/`Ctrl+W`/`Ctrl+P`/`Ctrl+T`…).
- **`vim`** — modal (`normal`/`insert`/`visual`), with `j`/`k` scrolling, `g g`/`G`, tab verbs
  (`g t`/`g T`), a `Space` **leader** (`SPC f f` → open file, `SPC b b` → buffer switcher…), `:` ex
  command line, and `/` search.
- **`emacs`** — chord prefixes (`C-x C-f` → open file, `C-x k` → close tab), `M-x` command palette,
  `C-s`/`C-r` search, `C-c ←/→` history.

A user file `~/.config/vinary-viewer/keybindings.edn` selects a preset (`:extends`) and overrides or
removes individual bindings; editing it **re-binds live** (no restart).

## 2. How to use it

**Pick a keymap.** Create `~/.config/vinary-viewer/keybindings.edn`:

```clojure
{:extends :vim}            ; or :emacs, or :default (the implicit default if no file exists)
```

Save it and the running app rebinds immediately. With `:extends :vim`, the app starts in `normal`
mode; `j`/`k` scroll, `g g`/`G` jump to top/bottom, `g t` cycles tabs, `Space f f` opens a file, `:`
opens the command line, `i` enters insert, `Esc` returns to normal. The mode-line at the bottom-right
shows the current mode and any pending key-sequence (e.g. `NORMAL  SPC f`).

**Customize.** The user file is deep-merged over the chosen preset. A value of `:unbind` removes an
inherited binding; nested maps express sequences; `"SPC"` is the leader token:

```clojure
{:extends :vim
 :leader "SPC"
 :timeout-ms 800
 :keymaps {:normal {"H"   :history/back              ; add bindings
                    "L"   :history/forward
                    "C-p" :palette/files
                    "SPC" {"w" {"q" :tab/close}}      ; SPC w q → close tab
                    "g"   {"t" :unbind}}              ; remove an inherited binding
           :all    {"C-M-t" :theme/cycle}}}           ; applies in every mode
```

**Command palette.** `default` `Ctrl+Shift+P`, emacs `M-x`, vim `SPC SPC` (or `:`) open the **command
palette**; `default` `Ctrl+P` / emacs `C-x C-f` / vim `SPC f f` open the **fuzzy file finder**. Type to
filter, `↑`/`↓` to move, `Enter` to run, `Esc` to close.

## 3. How it works internally

The engine is six small namespaces plus three EDN keymaps, sitting in the re-frame + DataScript model
exactly like the rest of the app (keybinding/modal/sequence state is *ephemeral UI* → app-db; the
keymap itself is a static atom in `keymap.cljs`).

### 3.1 Command registry — the API (`src/vinary/app/commands.cljs`)

Every command is reified data (Command pattern):

```clojure
:tab/next  {:id :tab/next :title "Next tab" :category "Tabs" :dispatch [:tab/next] :when :has-tabs}
:tab/close {:id :tab/close :title "Close tab" :category "Tabs" :when :has-tabs
            :handler (fn [ctx] (when-let [p (:active-path ctx)] [:tab/close p]))}
```

`commands/run` resolves a command id against a **resolution context** `ctx` (tabs, active path, history
availability, find/palette visibility, `:in-input?`) and dispatches its `:dispatch` event (optionally
with `:arg`/runtime args) or calls its `:handler`. A command's optional `:when` predicate gates it: if
the predicate fails the key **passes through** (so e.g. `:history/back` doesn't swallow `Alt+←` with no
history — matching the disabled toolbar button). The registry currently holds **31 commands** across
Tabs, File, Navigation, Search, View, and Mode categories; it is also the candidate set for the
command palette (`commands/all-visible ctx`).

### 3.2 Key normalization (`src/vinary/input/keys.cljs`)

`event->chord` turns a `KeyboardEvent` into a canonical token: modifiers in fixed order
`C- M- S-`, where `C-` folds Ctrl **or** ⌘-on-macOS, `M-` is Alt/Option, and `S-` is emitted only for
named keys (for printables, Shift is already in the character — `Shift+/` → `"?"`, not `"S-/"`). Named
keys map to short tokens (`ArrowLeft`→`"left"`, `Escape`→`"escape"`, `" "`→`"space"`). Modifier-only /
IME events return `nil` and are ignored.

### 3.3 Keymaps + merge (`src/vinary/input/keymap.cljs`, `resources/keymaps/*.edn`)

Each keymap is `{:name :initial-mode :timeout-ms :leader :modes}`. A **mode-map** maps a chord token to
either a command (leaf) or a nested map (a **prefix/sequence** — the trie the resolver walks):
`{"C-x" {"C-f" :file/open}}` is `C-x C-f`. The three bundled presets are read from
`resources/keymaps/{default,vim,emacs}.edn` **at compile time** by the `vinary.input.presets/bundled`
macro and inlined as data — the renderer build stubs `fs`, so presets cannot be read at runtime.
`install-user!` deep-merges the user delta over the `:extends` preset (`:unbind` removes; `deep-merge`
keeps the preset where the user supplies no value), normalizes the keys (`"SPC"`→`"space"`), and stores
the result in an atom.

### 3.4 The resolver (`src/vinary/input/resolver.cljs`)

A single `keydown` listener on `window` (installed by `install!`, replacing the old hand-rolled
listener). The pure core `step` builds the active map as `(merge (:all modes) (mode modes))`, looks up
`(get-in root (conj sequence token))`, and returns a decision:

- **leaf** → `:dispatch` the command;
- **nested map** → `:prefix` (more keys expected);
- **miss mid-sequence** → `:retry` the token fresh;
- **miss at start** → `:consume` (vim swallows stray `normal`/`visual` keys) or `:pass` (let it reach
  inputs / the browser).

The **pending sequence and the chord timer live in resolver-local atoms**, updated *synchronously* —
re-frame dispatch is asynchronous, so two keydowns in one JS task would otherwise desync; the sequence
is mirrored to app-db (`:input/set-sequence`) only to drive the mode-line. A half-typed prefix
(`C-x` …) resets after `timeout-ms`. The palette owns all keys while open, and a **bare printable key
always reaches a focused input** (so typing a find query or a vim `/`-search works even in `normal`
mode); inputs report focus via `:on-focus`/`:on-blur` → `:input/set-in-input`.

### 3.5 Command palette (`src/vinary/ui/palette.cljs`)

One overlay widget, three sources: `:command` (all visible commands), `:file` (the git tree, fuzzy),
`:theme`. A subsequence fuzzy matcher filters as you type; `Enter` runs the selection (`:doc/open` for a
file, `:theme/set` for a theme, `commands/run` for a command).

### 3.6 Config loader (`src/vinary/main/config.cljs`)

The Electron **main** process reads `~/.config/vinary-viewer/keybindings.edn` (honoring
`XDG_CONFIG_HOME`), watches it with chokidar, and pushes it to the renderer over the `vv:keymap`
channel. **The config crosses the IPC seam as raw EDN *text*** (not `clj->js`): `clj->js` would flatten
the keyword command-ids (`:file/open`) to strings, so the renderer parses the text with `cljs.reader`,
preserving keywords. The renderer pulls once on boot (`requestKeymap`) to avoid a startup race, and
re-installs on every file change — *live keybinding reload*.

## 4. Design notes

- **Patterns:** Command (the registry), Strategy (keymap-per-mode + preset selection), Interpreter
  (`step` walking the trie), State (the modal FSM), Mediator (config over `window.vv`), Observer (the
  mode-line/palette subs). Effects (DOM scroll/focus, the timer) are re-frame fx at the edge.
- **Why a synchronous local sequence atom** rather than app-db: correctness under fast multi-key input
  (see §3.4).
- **Why EDN text over IPC** rather than a `clj->js` map: to preserve keyword command-ids (see §3.6).

## 5. Diagram

See [`component-keybindings-inprogress.puml`](../diagrams/component-keybindings-inprogress.puml) for the
component layout: the `keydown` → resolver → command-registry → re-frame flow, the keymap atom + presets
macro, and the main→renderer config seam.

> **Doc reconciliation note:** an earlier draft of this page (and the diagram filename's
> `-inprogress` suffix) tagged the feature "now available" because the documentation was generated
> while the `src/vinary/input/*` modules were mid-landing. The system is now complete and verified
> end-to-end (default `Ctrl+F`/`Alt+←→`/`C-t`; vim `j`/`g g`/`SPC` leader/modal; emacs `C-x C-f` chord +
> timeout reset; config auto-load via `XDG_CONFIG_HOME`; palette filter + run).
