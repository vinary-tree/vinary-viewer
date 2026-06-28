# Web-view keyboard, Copy & cookies

**Status: Available now.**

---

## 1 · What it is

When you follow an `http(s)` link, the page opens in Vinary's in-app web view (a main-owned, isolated
`WebContentsView`). It is tuned for **reading parity** with the document previews:

- **Vim/standard scroll keys** — `j`/`k` (line), `Ctrl+d`/`Ctrl+u` (half page), Space / `Shift`+Space
  (page), `g g` / `G` (top/bottom), arrows, Page keys, `Home`/`End`.
- **`f` link hints** — press `f` to label every visible link and jump by typing its label (Vimium-style).
- **History** — `Alt+←` / `Alt+→` (and the toolbar buttons / mouse back-forward) move through history.
- **Right-click → Copy** — a native context menu offering **Copy**, **Copy Link Address** (on a link),
  and **Select All**.
- **Text selection** works natively.
- **Persistent cookies** — the web view uses a dedicated persistent session (`persist:vinary-web`), so
  **logins to documentation sites survive restarts** (useful for paywalled/authenticated docs).

## 2 · How you use it

Just open a web link. Scroll with the keys above, `f` to hint-click, right-click to Copy, and log in to
sites once — your session persists. The same web session hosts the optional
[ad-blocker](20-ad-blocking.md) and [extensions](21-browser-extensions.md).

## 3 · Internals

| Piece | Where |
|---|---|
| Scroll keys + `f` hints (self-contained, in the page context) | `resources/web-preload.js` |
| `Alt`-arrow history forwarding | `vinary.main.web` (`before-input-event`) |
| Right-click Copy menu (native) | `vinary.main.web` (`context-menu` handler) |
| Persistent session | `vinary.main.web` (`webPreferences {:partition "persist:vinary-web"}`) |

Because the web view is a separate native context, scroll/hint keys are handled locally in its preload
(no round-trip), and the Copy menu is a native Electron menu (the view paints over the DOM). Verified by a
dedicated scroll-parity probe.
