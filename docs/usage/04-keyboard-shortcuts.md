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
| Vim | Vimium-style navigation, normal/insert mode behavior, leader-style sequences, and link hints. (Numeric count prefixes such as `5j` are **not** implemented: the `[:ui :input :count]` slot is reserved and cleared, never set.) |
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

Transient resolver state such as mode, pending sequence, and input focus lives in
`app-db` for display and inspection (a `:count` slot exists but is reserved — counts are
not implemented). The active keymap data lives
in an atom because it is comparatively static and read synchronously by the
resolver.

---

## 3. Common bindings

The exact bindings come from `resources/keymaps/*.edn` plus user overrides. The
stable actions available across the current UI include:

| Action | Typical binding |
|--------|-----------------|
| Find | `Ctrl+F` in Standard; `/` in Vim normal mode. |
| History back/forward | `Alt+Left` / `Alt+Right`, toolbar buttons, and mouse thumb buttons. **Keymap-independent** — bound in the `:all` block of every keymap (Standard, Vim, Emacs), so they work in any mode. |
| Parent directory | `Alt+Up` (`:nav/parent`); Vim `K`. Navigate the active tab to the current path's parent folder. Alt key is keymap-independent. |
| Open highlighted entry | `Alt+Down` (`:nav/open-target`); Vim `J`. Open the highlighted child of the active directory listing. Alt key is keymap-independent. |
| Switch tabs | `Ctrl+PageUp` (previous/left) / `Ctrl+PageDown` (next/right), wrapping at the ends — **keymap-independent** (in the `:all` block of every keymap). Vim also has `g t` / `g T`; Emacs also `M-f` / `M-b`. A blank tab restores, focuses, and selects its unfinished URI; a document tab focuses its body. All backed by `:tab/next` / `:tab/prev`. |
| New blank tab | `Ctrl+T`. The URI bar is focused and selected immediately; unfinished text is retained for that blank tab when you switch away. |
| Scroll the pane | **Bare arrow keys** (`↑`/`↓`/`←`/`→`), plus keymap commands backed by `:nav/scroll` (Vim `j`/`k`, page/half). All are smooth — see below. |
| Toggle sidebar | Command backed by `:sidebar/toggle`. |
| Open command palette | Command backed by palette state and command registry. |
| Link hints | `f` in Vim normal mode. Targets are links, file/dir rows, and — in a diff — the file banners: typing a banner's label toggles that file's collapse (ADR-0037). |
| Collapse/expand all diff files | Vim `z M` / `z R` (the fold idiom; no-ops outside a diff). Also View ▸ Collapse All Files (label flips to Expand All Files) and the palette commands **Collapse/Expand all diff files**. |
| Toggle git blame | `Ctrl+Shift+G` in Standard (`:git/blame-toggle`; self-gates — a no-op without a local source view). The chord is unbound in Vim/Emacs; every set reaches it via the palette's **Toggle git blame**. |

The find bar offers Exact, Approximate, and FZF modes. Exact searches the full
document for the entered phrase, including offscreen source lines. In the other
two modes, space-separated terms can appear in any order within one rendered
block or source line; the counter advances by block or line. Approximate covers
spelling edits and adjacent swaps. Preferences can also enable English
sound-alike matches in prose. FZF highlights the characters it matched.

For a later search review: libdictenstein vocabulary term IDs could index
document n-grams in another dictionary, and lling-llang could compose that
index with spelling correction. Measure the extra index against the current
block-posting approach before adding it.

Use the visual editor for the authoritative active binding list; it reads the
same registry the resolver uses.

**Smooth scrolling.** Bare arrow keys scroll the **focused pane** — the focused
element's nearest scrollable ancestor, else the content pane. They are skipped inside
editable elements (inputs, textareas, CodeMirror) so cursor movement still works, and
while a menu or modal overlay is open. A single `requestAnimationFrame` animator eases
toward an *accumulating* target, so a held key scrolls continuously and smoothly
(replacing the old per-press jumps); the same animator smooths Vim `j`/`k` and the
page/half-page commands. Implemented in `vinary.input.fx` (`:dom/scroll`); the
bare-arrow / page-key capture handler is `vinary.renderer.core/key-scroll!`.

### Slides (a PDF in the slides layout)

When a PDF shows one slide per screen (a detected slide deck, or View ▸ View as Slide Show — ADR-0045), the
same keys flip slides instead of scrolling pixels. Nothing needs rebinding: every scroll command is routed to
the slide show, in every keymap set.

| Keys | Slides action |
|------|---------------|
| `→` `↓` `PgDn`, Vim `j` `l` `Ctrl+D` `Ctrl+F`, Emacs `C-v` `C-n`, `Space`, the mouse wheel, a leftward touch / pen swipe | next slide (one overlay page) |
| `←` `↑` `PgUp`, Vim `k` `h` `Ctrl+U` `Ctrl+B`, Emacs `M-v` `C-p`, `Shift+Space`, `Backspace`, a rightward swipe | previous slide |
| `Shift+→` `Shift+↓` `Shift+PgDn` | next **frame** — skips the rest of this Beamer slide's overlays |
| `Shift+←` `Shift+↑` `Shift+PgUp` | previous frame, shown fully built |
| `Home` / `End`, Vim `g g` / `G` | first / last slide |
| digits, then `Enter` | go to that slide — the number printed on a Beamer slide (its frame number) first, else the page number; a "Go to 12" chip shows what you typed |
| `Backspace` while typing | delete the last digit |
| `Escape` | forget a typed number (it is also forgotten 2.5 s after the last digit) |
| `F5` / `Shift+F5` | start a **slide show** of the shown PDF from the first / the current slide (any PDF; `F5` again during a show goes back to the first slide) |
| `b` `.` / `w` `,` | during a show: black / white screen; the same key again, any navigation key, a click or `Escape` brings the slide back (a navigation key does not also move on) |
| click / `Shift`+click | during a show: next / previous slide (not on a link, not the end of a text selection or a drag) |
| `Tab` / `o` | the **overview** grid: one tile per frame, each showing the frame fully built. Arrows, PgUp/PgDn and Home/End move; digits pick a frame by the number on its slide; `Enter` opens it fully built and `Shift+Enter` at its first overlay; a click opens a tile (`Shift`+click the other end); `Escape`, `Tab` or `o` close it. Opening a slide from the overview is a jump, so Back returns. |
| `Escape` during a show | unwinds one step at a time: the overview, a typed number, a blanked screen, then the show itself |

A swipe is a quick (≤ 0.7 s), mostly horizontal (≥ 1.5 × its vertical travel) touch or pen gesture of at least 48 px;
a mouse drag never flips a slide, because it selects text. Each of these flips can animate — Preferences ▸ Slides ▸
*Slide transition*: None (instant, the default), Fade or Push — while find and the Contents outline always jump
instantly (the `:slides-transition` key in [Configuration](05-configuration.md)). A Beamer deck built with
`show notes on second screen` shows only its slide half; its pages step one at a time and are numbered by page (the
`:slides-notes` key there).

The slide keys (`Space`, `Shift+Space`, `Backspace`, `Enter`, `Shift+arrows`, digits) form a `:slides` layer
that is active only while the slides layout is showing **and** the keyboard is on the document — not in a text
field, the sidebar, the URI bar, or an open menu, dialog or prompt. The layer sits below the current mode, so
a mode's own binding wins: in Vim normal mode `Space` stays the leader (use `→`, `j` or `PgDn` there). It is
the same in every bundled set and can be overridden like a mode, under `:keymaps {:slides {…}}` (§5). The
palette's **Slides** commands (Next slide, Next frame, First slide, …) are offered only while a PDF is in the
slides layout; **View as slide show / document** switches any PDF's layout.

Without the keyboard: **View ▸ View as Slide Show** (or the address bar's slides button) switches any PDF
between the slides and document layouts; **View ▸ Start Slide Show** / **Start from Current Slide** and
**Slide Overview** mirror `F5`, `Shift+F5` and `Tab`; right-clicking a page offers **Start From This Slide**;
and in the slides layout the bottom bar carries ◀ page ▶, the frame counter, the overview and a Present
button in place of the zoom control (slides fit themselves).

A **slide show** (`F5`) shows the slides layout full-window: the menus, sidebar, tabs, address bar and status
bar hide, the slide fills the window on black with no margin, and the window goes fullscreen — only the
window you started it in, and only while the show runs (a window you had already made fullscreen stays
fullscreen afterwards). The pointer hides after 2.5 s without moving. The SSH password prompt, the palette and
the find bar still appear over a show. Switching tabs, navigating, focusing the address bar (`Ctrl+L`) or the
sidebar, or leaving fullscreen yourself (`F11`, your window manager) also ends it. `F5` and `Shift+F5` are in
every set's `:all` block, so they work in any mode; the Alt access keys of the hidden menu bar are off during
a show.

**Opening files/folders is OS-dependent.** In both the **directory browser** and the
**git file tree**, a **single click opens on Linux** and a **double click opens on
Windows/macOS** (the host file-manager convention, via
`vinary.ui.platform/single-click-open?`). `Ctrl`+click always opens in a new tab.

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

### Link gestures (mouse)

One family across every link-like surface — preview links, file-tree rows, directory-browser rows
(including `..`), and the address-bar breadcrumbs ([ADR-0044](../design-decisions/0044-browser-link-gesture-family.md)):

| Gesture | Effect |
|---------|--------|
| Click | Open in the current tab (the tree/browser follow their platform's single/double-click rule) |
| `Ctrl+click` **or** middle-click | Open in a **background** tab — you stay on what you are reading |
| `Ctrl+Shift+click` **or** `Shift`+middle-click | Open in a **focused** new tab |
| Middle-click a tab | Close it (tab strip and vertical Tabs panel alike) |

A middle-/Ctrl-clicked tree or directory row always opens *another* tab, even if that file is
already open; `Ctrl+Shift+click` reuses an existing tab for it. Links inside a browsed web page
follow the same rules and open app tabs — never a separate native window.

---

*Next: [05-configuration.md](05-configuration.md).*
