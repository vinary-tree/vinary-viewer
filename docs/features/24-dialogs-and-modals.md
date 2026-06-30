# Dialogs & modals

> **Status: Available now.** Every blocking dialog renders through one shared **modal shell**, so they
> all share a defined border, theme-aware elevation, and accessible keyboard behavior.

---

## 1. What it is

vinary-viewer has several **modal dialogs** — Preferences, About, Extensions & Ad Blocking, the two
Passwords dialogs (the autofill panel and the save-login prompt) — plus two non-modal overlays that
share the same visual family (the command palette and the right-click context menu). Previously each
dialog hand-rolled its own overlay markup, so behavior drifted: some closed on `Esc`, some did not; only
one had a ✕; the 1 px border used a low-contrast token (`--vv-border`) that read as *borderless* against
the near-identical panel background; and shadows were hard-coded black, so the **light** theme inherited
the dark theme's heavy shadow.

This feature replaces that with **one shell component** (`vinary.ui.modal`) and a small **elevation
design-system** of theme tokens, so every dialog is consistent and accessible:

- a **defined border** (`--vv-dialog-border`, higher-contrast than `--vv-border`) + a **theme-aware
  drop-shadow** (`--vv-shadow`) and **backdrop scrim** (`--vv-scrim`);
- **`Esc`**, the **`✕`** in the title bar, and a **backdrop click** all close the dialog;
- **autofocus** into the dialog on open and **focus restore** on close;
- a **focus trap** (Tab / Shift+Tab cycle within the dialog);
- **true modality** — background commands (e.g. Vim `j`, `Ctrl+T`) cannot fire while a dialog is open.

A related fix makes **error-view text selectable and copyable** (`Ctrl+C`).

## 2. How to use it

| Dialog | Open it with |
| --- | --- |
| Preferences (fonts) | `Settings ▸ Preferences…` |
| Extensions & Ad Blocking | `Settings ▸ Extensions…` |
| Passwords (autofill) | `Settings ▸ Passwords…`, or the key icon on an `http(s)` tab |
| Keybindings editor | `Settings ▸ Key Bindings ▸ Customize…` |
| About | `Help ▸ About vinary-viewer` |
| Command palette | `Help ▸ Command Palette`, `Ctrl+Shift+P`, or Vim `:` |

**Close any dialog** with `Esc`, the `✕` in its title bar, or by clicking the dimmed area outside it.
While a dialog is open it owns the keyboard: `Tab` stays inside it, and background shortcuts are
inert until you close it. Mnemonics (`Alt`+the underlined letter) work where shown.

**Copy an error message:** when a document fails to open, select the red error text and press `Ctrl+C`.

## 3. How it works internally

### 3.1 The shared shell — `src/vinary/ui/modal.cljs`

`modal/modal` is a **form-2 Reagent component** with a **stable `:ref` callback**. Each dialog supplies
only its body + an opts map:

```clojure
[modal/modal {:on-close #(rf/dispatch [:settings/close])
              :title "Preferences"
              :actions [:button.vv-btn … "Close"]}
 [:div.vv-modal-body …]]
```

The shell renders `.vv-modal-overlay` (the scrim, `:on-click on-close`) ▸ `.vv-modal` (the panel,
`stopPropagation` on click) with a `.vv-modal-title` bar (title + `.vv-modal-x` ✕), the body, and an
optional `.vv-modal-actions` footer. Its behaviors:

- **Esc / Tab** — a panel `on-key-down` closes on `Escape` and traps `Tab`/`Shift+Tab` within the
  panel's focusable descendants (wrapping at the ends).
- **Autofocus + restore** — the stable ref focuses the panel on mount (so `Esc`/`Tab` work immediately)
  and restores the previously-focused element on unmount. The ref identity is fixed per instance, so a
  re-render (e.g. typing in a field) never re-grabs focus.
- **True modality** — the panel `on-key-down` calls `stopPropagation` on every key, so an un-handled key
  never bubbles to the window keymap resolver; a focus-escape fallback lives in the resolver (§3.3).

### 3.2 Elevation tokens — `app.css` + the themes

Four theme tokens drive every dialog/menu surface (see
[reference/css-variables.md §2.3.1](../reference/css-variables.md)): `--vv-dialog-border`, `--vv-shadow`
(modals/palette), `--vv-shadow-menu` (dropdowns/context menu), and `--vv-scrim` (backdrop). They are
defined per theme, so the light theme gets a soft, light-tinted shadow instead of a black one. The
five `.vv-modal` dialogs adopt the shell; the palette, context menu, and dropdowns keep their
specialized keyboard handling but consume the same tokens for a consistent look.

### 3.3 True modality — `src/vinary/input/resolver.cljs`

The keymap resolver builds a `:modal-open?` flag from app-db (Settings/About/Extensions/Passwords/save-
prompt/keybindings-editor) and early-returns while any is open, so a background chord cannot run even if
focus has somehow left the panel. (The palette already had its own early-return.)

### 3.4 Copyable error text — `src/vinary/renderer/core.cljs`

The `Ctrl+C` copy path only acts on selections inside a recognized *selectable root*. `selectable-root`
now includes `.vv-error` (and the inline `.vv-math-error` / `.vv-mermaid-error` diagram errors), so a
selected error message copies to the clipboard instead of being silently refused.

## 4. Design notes / trade-offs

- **Shared shell vs bespoke markup.** Extracting one shell removed five copies of the overlay +
  stop-propagation idiom and made `Esc`/✕/backdrop/autofocus/trap/modality uniform — the consistency and
  accessibility win outweighs the small indirection. The palette, context menu, and keybindings editor
  keep their own keyboard logic (arrow-nav, capture mode) but share the elevation tokens.
- **Border *contrast*, not a missing border.** Every dialog already declared a border; the fix was a
  higher-contrast, theme-aware token plus a theme-aware shadow — the panel edge now reads clearly in both
  themes without a heavy outline.
- **Verification.** Pure tests cover the palette matcher, context-menu items, and chord formatting; the
  Electron smoke opens each dialog and asserts the shared-modal behaviors (autofocus, focus-trap, true
  modality, `Esc`/✕/backdrop close) and the error-text copy path.

## 5. See also

- [reference/css-variables.md §2.3.1](../reference/css-variables.md) — the elevation tokens.
- [15-custom-keybindings.md](15-custom-keybindings.md) — the command palette + keybindings editor.
- [05-in-page-find.md](05-in-page-find.md) — the find bar (a non-modal floating overlay in the same family).
