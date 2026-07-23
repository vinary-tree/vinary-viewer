# 08 — The daemon window-lifetime crash experiment

**Status: Measured.** A second worked application of the scientific method, in the same shape as the
[ink-loss experiment](05-mathjax-inkloss-experiment.md), but to a **main-process lifetime defect** rather than
a rendering one: a symptom with an exact period, a hypothesis about the mechanism, a **controlled A/B** in
which the pre-fix build reproduces the failure on demand, and a re-measurement that shows the fixed build
surviving the same stimulus. The result is recorded in [`CHANGELOG.txt`](../../CHANGELOG.txt); this page
reconstructs the reasoning so the experiment can be re-run.

The class of defect — *a process-lifetime service holding a reference to a window-lifetime object* — has now
produced two separate crashes in this codebase (see §7), so it is written up as a pattern, not an incident.

---

## 1. Symptom

The resident `--daemon` wrote one crash log per day into `~/.config/vinary-viewer/`:

```
vinary-viewer crash — 2026-07-23T16:24:41.682Z
version 0.3.0-dev  platform darwin-arm64

TypeError: Object has been destroyed
    at WebContents.send (node:electron/js2c/browser_init:2:88091)
    at vinary.main.adblock.result_BANG_ (…/dist/main/main.js:3167:227)
    at vinary.main.adblock.refresh_BANG_ [as _onTimeout] (…/dist/main/main.js:3168:419)
    at listOnTimeout (node:internal/timers:605:17)
```

Two independent observations made this unusually tractable before a single line of source was read:

| Observation | What it constrains |
|---|---|
| Two logs, `…T16-24-41.681Z` and `…T16-24-41.682Z`, **exactly 24 h apart to the millisecond** | The trigger is a `setInterval`, not user action — and its period is 24 h |
| The logs are **byte-identical apart from the timestamp** | One deterministic fault, refiring; not a race with varying shape |
| The frame is `refresh_BANG_ [as _onTimeout]` | The throw is *synchronous inside the timer callback* — it never reached a promise |

---

## 2. Hypothesis

The defect requires three individually reasonable facts to coincide:

1. **The refresh schedule is process-scoped.** `vinary.main.adblock/start-scheduler!` installs a 24 h
   `setInterval` — matching the observed period exactly.
2. **The status target was resolved as `(or (windows/active-wc) (:wc @state))`, with no liveness check.**
   `:wc` is captured once, at `init!`, from the first window `core/wire-window!` touches; under the warm pool
   that first window is a **hidden pool window**, which `windows/add!` deliberately never registers. Under
   `--daemon`, `window-all-closed` does not quit, so `windows/active-wc` legitimately returns `nil` — the
   daemon's steady state — and the fallback yields the long-since-destroyed captured `webContents`.
3. **The push happens first.** `refresh!`'s opening statement is the synchronous `(result! {:status
   :updating})`. Its `.catch` handlers guard only the promise chain, so a throw there escapes the function
   entirely, through `listOnTimeout`, into `uncaughtException`.

**Predictions.** If the mechanism is as stated, then:

- **P1** — the crash fires on the **first tick after the last window closes**, and not before.
- **P2** — because the throw precedes `(build lists)`, **no scheduled refresh ever completes** in a
  long-running daemon: the ad-block filter lists silently stop updating. A crash is the visible symptom of the
  defect; this is its invisible one.
- **P3** — the crash is **independent of the 24 h period**; the period only sets how long you wait.

---

## 3. Instrument

P3 is what makes the experiment cheap, so it is also the first thing built: `VV_ADBLOCK_REFRESH_MS`
overrides the schedule (see [features/20 §Scheduled refresh and windows](../features/20-ad-blocking.md)).
Combined with two isolation switches the app already honours, the whole experiment runs against a **disposable
instance** that cannot touch the developer's real daemon, config, or crash logs:

| Switch | Role in the experiment |
|---|---|
| `VV_ADBLOCK_REFRESH_MS=5000` | 24 h → 5 s, so a "daily" fault is observed in seconds |
| `VV_POOL=0` | no hidden pool window, so session services bind to the **visible** window — one open/close closes the captured one |
| `XDG_CONFIG_HOME=<scratch>` | crash logs land in a scratch directory; the real ones are never disturbed |
| `--user-data-dir=<scratch>` | a private single-instance lock and engine cache, so the run neither steals nor is stolen by the real daemon's `second-instance` handoff |

Windows are opened by handing argv to the daemon (a second `electron .` invocation) and closed over the
**DevTools protocol** (`--remote-debugging-port`, then `GET /json/close/<targetId>`), so the whole sequence is
scriptable and needs no GUI interaction.

**The control build** is `HEAD` in a throwaway `git worktree` with *only* the `VV_ADBLOCK_REFRESH_MS` knob
added and **none** of the fix — the instrument must be in both arms, or the arms differ by more than the
intervention.

---

## 4. Procedure

Identical for both arms:

1. Start the daemon with the four switches above.
2. Open a document (second-instance handoff) → one visible window; session services bind to it.
3. Close that window over CDP → no window on screen, captured `webContents` destroyed.
4. Wait ≥ 5 ticks (25 s).
5. Read out: (a) crash logs in the scratch config dir, (b) the daemon's stderr, (c) whether a **deleted**
   `<userData>/adblock-engine.bin` is rebuilt while no window is open (the P2 probe).

---

## 5. Result

| Arm | Crash logs after 5 ticks | Daemon stderr | Engine cache rebuilt with no window? |
|---|---|---|---|
| **Control** (`HEAD` + knob only) | **1**, on the **first** tick after the close | `uncaught main-process exception: TypeError: Object has been destroyed`, frames `adblock.cljs:33` (`result!`) ← `adblock.cljs:43` (`refresh!` as `_onTimeout`) ← `listOnTimeout` | *(never reached — the tick died first)* |
| **Fixed** | **0** | clean | **yes** — cache deleted at `12:52:45` with **0** page targets open, recreated at `12:52:50` by the next tick |

The control's stack is the *same* stack as the field crash log of §1, function for function, so the
reproduction is of the reported defect and not of a lookalike. **P1 confirmed** (first tick after the close,
not before — the window was open across earlier ticks with no crash). **P2 confirmed in both directions**: the
control never reached the engine build; the fixed build rebuilt a deleted engine cache with provably no window
open (`birth == mtime == 12:52:50`, five seconds after the delete).

Two further re-measurements guard against a fix that merely silences the symptom:

- **The live path still works.** With a window open, a CDP-registered `vv.onAdblockStatus` listener observes
  the full `{"status":"updating"} → {"status":"ok","last-updated":…}` cycle, once per scheduled tick and once
  per manual `vv.adblockRefresh()`.
- **A window claimed *after* background refreshes still receives status.** Reopening a window after the
  no-window ticks yields `updating → ok` on the very next tick, confirming the target is resolved dynamically
  rather than captured.

At the unit level, the same claim is made falsifiable without Electron: reverting `windows/active` and
`windows/active-wc` to their pre-fix bodies (a deliberate **negative control**, then restored) turns exactly
6 assertions in `windows-test` red — `active-skips-destroyed-windows`, `active-nil-when-no-live-window`, and
`send-is-a-silent-noop-without-a-live-window`. A test that cannot fail is not evidence; these can.

---

## 6. Intervention

The fix is a liveness discipline, not a `try`/`catch`:

| Change | Where |
|---|---|
| `live` — the single predicate every captured `webContents` is filtered through | `vinary.main.windows` |
| `send!` — targeted push to the active window, a silent no-op when none is on screen (the counterpart to `broadcast!`) | `vinary.main.windows` |
| `active`/`active-wc` skip destroyed windows (a window sits in the registry between `close` and `closed`) | `vinary.main.windows` |
| Captured `webContents` dropped once dead, so it is never retried | `adblock`, `extensions` (`boot-wc`) |
| Scheduler tick wrapped, so no synchronous throw can reach `uncaughtException` from a timer | `vinary.main.adblock` |
| Same unguarded pattern closed | `extensions`, `passwords`, `web` |

---

## 7. The generalization

This is the **second** crash of this exact class. The first (`b7538a7`) was a chokidar file-watcher pushing to
the `webContents` it captured when it was created; the fix was `windows/broadcast!`. It hardened the *global*
push path and left the *targeted* one — `active-wc` with a captured fallback — unguarded, which is the defect
measured here.

### 7.1 · A negative result: the web view is *not* an instance

The obvious next prediction — *the web `WebContentsView` is a child of its owner window's `contentView`, so it
dies with that window, and every `(.send (.-webContents (:view @state)) …)` in `vinary.main.web` is the same
bug* — is **wrong**, and was tested rather than assumed:

1. Open an `http(s)` URL (the view is created; CDP lists two page targets — the app window and the view).
2. Close the owner window over CDP → **one target remains: the view is still alive.** `state` holds a strong
   reference, so Electron does not destroy it with the window.
3. Open a new window and `httpShow` / `httpScroll` / `httpTocGoto` into it → the view **re-parents** into the
   new window (`adopt!` already guards the old owner with `isDestroyed`) and no exception is raised.

So the view outlives its window *by design*, and the guard belongs only on the **app window's** `webContents`
(`web/app-wc`, which is fixed) — not on `:view`. Recorded here because "the child view must die with its
window" is a plausible, load-bearing, and false intuition; the next reader should not spend the experiment
again, nor "fix" a path that is already correct.

The invariant, stated once so the third instance does not happen:

> **A process-lifetime service must not hold a window-lifetime handle.** Timers, watchers, pollers, and
> session-event listeners outlive every window — especially under `--daemon`, which survives
> `window-all-closed`, and with the warm pool, whose first window is hidden and later claimed and closed.
> Resolve the target window **at push time** (`windows/send!` or `windows/broadcast!`), and pass any retained
> `webContents` through `windows/live` first.

The corollary the timer frame taught: a callback invoked by the **event loop** (a timer, an emitter) has no
enclosing `catch` and no promise to reject into, so a throw inside it is process-fatal. `refresh!` returning a
never-rejecting promise was necessary but not sufficient — its *synchronous prelude* was outside that
guarantee.
