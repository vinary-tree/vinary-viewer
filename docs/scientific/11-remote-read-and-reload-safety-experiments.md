# 11 — Remote read and reload-safety experiments

**Status: in progress.**
- **Recorded:** the baseline and its hypotheses (this page), plus one discovery made while validating the instrument (H0).
- **Pending:** the interventions and their re-measurement (§8, §9).

These experiments support [ADR-0046 — Reload-safe, pipelined remote reads] and the slide-deck work of
[ADR-0045 — Slide-deck presentation], where a remote deck (`vv sftp://host/talk.pdf`) is read whole over SFTP
on open and again on every recompile.

The methodology is that of [07 — Measurement methodology](07-measurement-methodology.md):
1. Instrument first.
2. Predict before measuring.
3. Record the raw numbers (`data/11/`).
4. Keep the refuted hypotheses.

---

## 1 · The question

How long does vinary-viewer take to fetch a whole remote file — a slide deck, a PDF — over SFTP, as a function
of three variables, and how much of that time could a better reader remove?
- $`S`$, the file size;
- $`R`$, the network round-trip time;
- the server's SSH identity string.

Reading the code (`ssh_transport.js remoteReadFile`, ssh2 1.17.0) predicted a strictly sequential reader. A
whole-file read uses `sftp.createReadStream`:
- ssh2's read stream issues **one** read request of at most 64 KiB at a time (`SFTP.js` ≈ 3646, 3724–3760);
- that request is split into $`k`$ sequential protocol reads when it exceeds ssh2's maximum read length $`L`$
  (`SFTP.js` 102, 115–116, 157–162, 2804–2819).

$`L`$ depends on the server:

| Server identity (`SSH-2.0-…`) | $`L`$ (bytes) | $`k = \lceil 65536 / L \rceil`$ |
|---|---|---|
| matches `OpenSSH` or `dropbear` (or advertises `limits@openssh.com`) | 260 096 | 1 |
| anything else — including ssh2's own default ident, i.e. the test fixture | 31 952 | 3 |

ssh2's `fastGet`, by contrast, keeps 64 × 32 KiB requests in flight (`SFTP.js` ≈ 2185), which is the 2 MiB
channel window.

## 2 · Instruments

- **Driver: [`scripts/bench-remote-read.cjs`](../../scripts/bench-remote-read.cjs)** (`npm run bench:remote`).
  - It drives the **real** transport (`src/vinary/main/ssh_transport.js`) against the hermetic in-process SFTP
    server ([`test/fixtures/ssh-server.js`](../../test/fixtures/ssh-server.js)).
  - The server runs in a child process per server identity, pinned with `taskset -c 6-9`.
  - The client is pinned with `taskset -c 2-5`.
  - Every read is verified by SHA-256 against the served bytes.
- **Network distance: [`test/fixtures/latency-proxy.js`](../../test/fixtures/latency-proxy.js).**
  - It is a TCP proxy that delays every chunk by $`R/2`$ in each direction (FIFO, one timer per direction,
    `TCP_NODELAY` on its own sockets).
  - A delay inside the server's READ handler would model *server processing time*, not distance: window
    adjusts, OPEN and STAT would stay instant. The proxy delays everything the network would.
- **Cells.**
  - The parameter grid: sizes {64 KiB, 1 MiB, 8 MiB, 32 MiB} × $`R`$ ∈ {0, 10, 50, 100} ms × ident {ssh2,
    OpenSSH} × reader {`stream` — the shipped `remoteReadFile`, `fastGet` — the pipelining reference}.
  - Each cell uses a warm connection and 1 warm-up read, then up to 7 timed reads or 30 s (minimum 3).
  - Statistics: median, p95 (nearest rank), mean ± sample sd; plus client CPU ms, peak `arrayBuffers` and
    event-loop-delay p99.
- **Host.** AMD Ryzen Threadripper PRO 5975WX (64 threads), `performance` governor, boost to 4.56 GHz. Software:
  Node v26.11.1, ssh2 1.17.0, Linux 7.2.9.
- **Raw data.**
  - [`data/11/baseline.json`](data/11/baseline.json): every sample.
  - [`baseline.txt`](data/11/baseline.txt): the run log.
  - [`baseline-analysis.txt`](data/11/baseline-analysis.txt).
  - [`h0-nodelay-factorial.txt`](data/11/h0-nodelay-factorial.txt).

## 3 · Hypotheses (written before the baseline)

Notation:
- $`S`$: bytes read.
- $`R`$: round-trip time.
- $`L`$, $`k`$: as above.
- $`D`$: requests in flight.
- $`C`$: bytes per request.
- $`W`$: the SSH channel window (≈ 1–2 MiB).
- $`B_{cpu}`$: the client's decrypt-and-copy throughput.

**H1 — today's reader is round-trip bound.** One OPEN, one request per 64 KiB (each costing $`k`$ round trips),
and a final read that returns EOF:

```math
t_{\text{stream}} \approx R\left(1 + k\left\lceil \frac{S}{65536} \right\rceil + 1\right) + \frac{S}{B_{cpu}}
```

**H2 — a pipelined reader is window bound.** With $`D`$ requests of $`C`$ bytes in flight, round trips fall to
$`\lceil S/(DC)\rceil`$ until the channel window caps the bytes in flight:

```math
t_{\text{pipelined}} \approx R_{open} + R\cdot\max\!\left(\left\lceil \frac{S}{D\,C} \right\rceil,\; \frac{S}{W}\right) + \frac{S}{B_{cpu}}
```

H2 is **refuted** if, once $`D\,C \ge 2\,\text{MiB}`$, the measured time is more than 25 % off the model, or if
raising $`D`$ past $`W/C`$ still gains more than 10 %.

**H3 — sibling discovery.** The up-to-12 sequential stats after a remote open cost $`\approx 12R`$; done in
parallel, overlapped with the byte read, they cost ≈ 0.

**H4 — bytes per change.** Today a change costs $`N\cdot S`$ for $`N`$ windows retaining the file. After a
single-flight, read-once fan-out it costs at most $`2S`$ per burst of notifications.

**H5 — torn reads.** During real `pdflatex`/`lualatex`/`xelatex` compile loops, a naive read sometimes returns
a torn PDF. A verified reader delivers none, and an incomplete probe moves at most 1 KiB.

**H6 — memory.** Peak memory is $`\approx 2S`$ today (chunks plus the final concatenation) and $`1S`$ for a
preallocated reader.

## 4 · H0 — discovered while validating the instrument

The first smoke run of the driver contradicted H1 at $`R = 0`$ by two orders of magnitude.

**Observation:**
- Over loopback, with no proxy, 1 MiB took **4118 ms** against ssh2's ident.
- That is ≈ 82 ms per protocol round trip: 50 round trips.
- At 0.24 MiB/s, this rules out bandwidth as the limit.

**H0 — Nagle's algorithm plus delayed ACK stall every request/response turn.**
- Nagle (RFC 896) holds a small segment while earlier data is unacknowledged.
- Delayed ACK (RFC 1122 §4.2.3.2; Linux's minimum delay is 40 ms) holds the acknowledgement.
- ssh2 never calls `setNoDelay` on either end: `client.js` ≈ 1635 and `server.js` ≈ 1326 only *expose* it.
- Prediction: about 40 ms per side that leaves Nagle on.

**Experiment:** a 2 × 2 factorial on `TCP_NODELAY` (client side × server side), sequential stream reader, 1 MiB.

| `TCP_NODELAY` on | ssh2 ident, $`R=0`$ | OpenSSH ident, $`R=0`$ | ssh2, $`R=10`$ | OpenSSH, $`R=10`$ |
|---|---|---|---|---|
| neither | 4118 ms | 1394 ms | 4621 ms | 1661 ms |
| client only | 2063 ms | 704 ms | 2560 ms | 925 ms |
| server only | 2054 ms | 740 ms | 2562 ms | 927 ms |
| **both** | **21 ms** | **8.4 ms** | **534 ms** | **194 ms** |

**Result: H0 confirmed.**
- Each side that leaves Nagle on adds one ≈ 40 ms stall per request.
- With both sides off, the 10 ms cells sit on H1's prediction: 534 vs 500 ms, and 194 vs 180 ms.

**Consequence for production.**
- OpenSSH's `sshd` already disables Nagle: `packet.c apply_qos()` calls `set_nodelay()` on every socket
  connection, interactive or not.
- So in the field only our **client-side** stall remains.
- The baseline below therefore emulates `sshd` (`--server-nodelay`) and measures the client **as shipped**.

## 5 · Baseline (server `TCP_NODELAY`, client as shipped)

$`\hat t`$ = H1's prediction; "excess / request" = $`(t - \hat t)/n_{req}`$ with $`n_{req} = 2 + k\lceil S/65536\rceil`$.
Medians of the timed reads (n = 3–7 per cell; p95 within 2 % of the median in every cell — `baseline.txt`).

**ssh2 ident ($`k = 3`$):**

| $`S`$ | $`R`$ (ms) | stream (ms) | $`\hat t`$ (ms) | stream / $`\hat t`$ | excess / request | fastGet (ms) | stream / fastGet |
|---|---|---|---|---|---|---|---|
| 64 KiB | 0 | 206.0 | 0 | — | 41.2 ms | 205.0 | 1.0× |
| 64 KiB | 10 | 255.9 | 50 | 5.12 | 41.2 ms | 258.9 | 1.0× |
| 64 KiB | 50 | 255.8 | 250 | 1.02 | 1.2 ms | 256.1 | 1.0× |
| 64 KiB | 100 | 506.8 | 500 | 1.01 | 1.4 ms | 507.4 | 1.0× |
| 1 MiB | 0 | 2 058.9 | 0 | — | 41.2 ms | 169.3 | 12.2× |
| 1 MiB | 10 | 2 584.3 | 500 | 5.17 | 41.7 ms | 235.3 | 11.0× |
| 1 MiB | 50 | 2 556.4 | 2 500 | 1.02 | 1.1 ms | 343.2 | 7.4× |
| 1 MiB | 100 | 5 067.1 | 5 000 | 1.01 | 1.3 ms | 553.7 | 9.2× |
| 8 MiB | 0 | 15 925.5 | 0 | — | 41.3 ms | 204.5 | 77.9× |
| 8 MiB | 10 | 19 979.7 | 3 860 | 5.18 | 41.8 ms | 446.1 | 44.8× |
| 8 MiB | 50 | 19 734.7 | 19 300 | 1.02 | 1.1 ms | 857.1 | 23.0× |
| 8 MiB | 100 | 39 196.4 | 38 600 | 1.02 | 1.5 ms | 1 494.8 | 26.2× |
| 32 MiB | 0 | 63 403.3 | 0 | — | 41.2 ms | 301.2 | 210.5× |
| 32 MiB | 10 | 80 035.7 | 15 380 | 5.20 | 42.0 ms | 975.0 | 82.1× |
| 32 MiB | 50 | 78 751.7 | 76 900 | 1.02 | 1.2 ms | 2 574.1 | 30.6× |
| 32 MiB | 100 | 156 126.6 | 153 800 | 1.02 | 1.5 ms | 4 518.3 | 34.6× |

**OpenSSH ident ($`k = 1`$):**

| $`S`$ | $`R`$ (ms) | stream (ms) | $`\hat t`$ (ms) | stream / $`\hat t`$ | excess / request | fastGet (ms) | stream / fastGet |
|---|---|---|---|---|---|---|---|
| 64 KiB | 0 | 124.8 | 0 | — | 41.6 ms | 165.9 | 0.8× |
| 64 KiB | 10 | 154.8 | 30 | 5.16 | 41.6 ms | 205.9 | 0.8× |
| 64 KiB | 50 | 152.6 | 150 | 1.02 | 0.9 ms | 203.6 | 0.7× |
| 64 KiB | 100 | 304.0 | 300 | 1.01 | 1.3 ms | 404.7 | 0.8× |
| 1 MiB | 0 | 742.4 | 0 | — | 41.2 ms | 168.3 | 4.4× |
| 1 MiB | 10 | 934.6 | 180 | 5.19 | 41.9 ms | 211.3 | 4.4× |
| 1 MiB | 50 | 917.8 | 900 | 1.02 | 1.0 ms | 208.8 | 4.4× |
| 1 MiB | 100 | 1 822.8 | 1 800 | 1.01 | 1.3 ms | 409.5 | 4.5× |
| 8 MiB | 0 | 5 367.9 | 0 | — | 41.3 ms | 195.5 | 27.5× |
| 8 MiB | 10 | 6 683.0 | 1 300 | 5.14 | 41.4 ms | 350.7 | 19.1× |
| 8 MiB | 50 | 6 627.3 | 6 500 | 1.02 | 1.0 ms | 622.9 | 10.6× |
| 8 MiB | 100 | 13 213.7 | 13 000 | 1.02 | 1.6 ms | 1 175.9 | 11.2× |
| 32 MiB | 0 | 21 215.3 | 0 | — | 41.3 ms | 315.1 | 67.3× |
| 32 MiB | 10 | 26 388.6 | 5 140 | 5.13 | 41.3 ms | 691.4 | 38.2× |
| 32 MiB | 50 | 26 278.4 | 25 700 | 1.02 | 1.1 ms | 1 952.3 | 13.5× |
| 32 MiB | 100 | 52 190.9 | 51 400 | 1.02 | 1.5 ms | 3 673.6 | 14.2× |

## 6 · Analysis of the baseline

**H1 holds where the client-side stall is absent.**
- At $`R \in \{50, 100\}`$ ms the shipped reader is within 1–2 % of the sequential model in every cell, for
  both idents and for files from 64 KiB to 32 MiB.
- The residual 0.9–1.6 ms per request is the per-request cost of crypto, framing and the proxy hop.

**H0's client-side half dominates low-latency links.**
- At $`R \in \{0, 10\}`$ ms every request carries a near-constant **41.2–42.0 ms** excess, regardless of size or
  ident.
- That makes a LAN- or VPN-near server, the typical "my build host" case, about **5× slower than its round
  trips**.
- The stall vanishes by $`R = 50`$ ms. The baseline does not locate the threshold between 10 and 50 ms; a
  20 / 30 ms sweep would.
- No claim is made here about the exact TCP state transitions that release it; the factorial establishes the
  cause, and the fix does not depend on the mechanism.

**Pipelining has large headroom.**
- `fastGet` is 7–210× faster than the shipped reader for files ≥ 1 MiB (ssh2 ident) and 4–67× (OpenSSH ident).
- The largest factors are at $`R = 0`$, where the stall is per request.
- At 64 KiB `fastGet` is no faster, and is slower against OpenSSH (0.7–0.8×): its extra `fstat` round trip
  outweighs pipelining a single request.
- A pipelined reader should therefore issue its first request with the open, not after an extra stat.

**Memory.** Peak `arrayBuffers` during a 32 MiB stream read was 105–174 MiB of process total, against ≈ 32 MiB
of payload. H6 will be tested against a preallocated reader.

## 7 · Refuted

- **Refuted:** "the fixture's slowness is the proxy". The 4118 ms appears **without** the proxy, at
  $`R = 0`$ (§4).
- **Refuted:** "a server-side READ delay can stand in for network distance". This was rejected by design (§2)
  rather than by measurement.

## 8 · Planned interventions (ADR-0046)

| ID | Intervention | Hypothesis it tests | Predicted effect |
|---|---|---|---|
| E0 | `client.setNoDelay(true)` on every pooled SSH connection | H0 (client half) | $`R \le 10`$ ms cells drop to H1, e.g. OpenSSH 1 MiB at 10 ms: 935 → ≈ 190 ms; no change at $`R \ge 50`$ ms |
| E1 | Pipelined, preallocated whole-file reader (`remoteOpenFile().readAll`, depth $`D = \lceil 2\,\text{MiB}/C \rceil`$) | H2, H6 | OpenSSH 32 MiB at 50 ms: 26.3 s → ≈ 1–2 s; peak ≈ 1 S |
| E2 | Sibling stats in parallel, overlapped with the read | H3 | −12 R on a remote open of a `.tex`/`.pdf` group |
| D1–D7 | Single-flight read-once fan-out, sequence ordering, torn-read detection, keep-last-good | H4, H5 | ≤ 2 S per burst; 0 torn PDFs delivered |

## 9 · Results after the interventions

Each intervention is re-measured with the same driver, cells and host as the baseline (§5), so a row here
compares directly with the row there.

### 9.1 · E0 — client `TCP_NODELAY` (2026-10-16)

**Intervention.** `connect()` in `src/vinary/main/ssh_transport.js` calls `client.setNoDelay(true)` right after
`client.connect(…)`. ssh2 creates the TCP socket synchronously inside `connect`, so the handshake is covered
too. A ProxyJump hop's forwarded channel has no TCP socket of its own; ssh2 skips it, and the hop's own
connection gets the setting when it connects.

**Prediction (§8).**
- $`R \le 10`$ ms cells drop to H1's sequential model, e.g. OpenSSH 1 MiB at 10 ms: 935 → ≈ 190 ms.
- $`R \ge 50`$ ms cells do not change.

**Run.** Same driver, `--server-nodelay` (emulating `sshd`), shipped reader only (`stream`).
- Client pinned to cores 2–5, server to 6–9.
- Governor `performance`.
- 1 warm-up, then ≤ 7 timed reads or 30 s per cell; every read SHA-256-verified.
- The $`R = 50`$ ms cells were re-measured for 1 and 8 MiB only, as the no-change control.
- Raw output: `data/11/e0.txt`, `data/11/e0-rtt50.txt` (+ `.json`). The comparison below is
  `data/11/e0-analysis.txt`.

| Ident | $`S`$ | $`R`$ (ms) | baseline (ms) | E0 (ms) | speedup | $`\hat t`$ (ms) | E0 / $`\hat t`$ | excess / request |
|---|---|---|---|---|---|---|---|---|
| ssh2 | 64 KiB | 0 | 206.0 | 1.9 | 108× | 0 | — | 0.38 ms |
| ssh2 | 1 MiB | 0 | 2 058.9 | 14.0 | 147× | 0 | — | 0.28 ms |
| ssh2 | 8 MiB | 0 | 15 925.5 | 72.7 | 219× | 0 | — | 0.19 ms |
| ssh2 | 32 MiB | 0 | 63 403.3 | 267.9 | 237× | 0 | — | 0.17 ms |
| ssh2 | 64 KiB | 10 | 255.9 | 52.9 | 4.8× | 50 | 1.058 | 0.58 ms |
| ssh2 | 1 MiB | 10 | 2 584.3 | 538.5 | 4.8× | 500 | 1.077 | 0.77 ms |
| ssh2 | 8 MiB | 10 | 19 979.7 | 4 136.3 | 4.8× | 3 860 | 1.072 | 0.72 ms |
| ssh2 | 32 MiB | 10 | 80 035.7 | 16 123.4 | 5.0× | 15 380 | 1.048 | 0.48 ms |
| ssh2 | 1 MiB | 50 | 2 556.4 | 2 547.8 | 1.0× | 2 500 | 1.019 | 0.96 ms |
| ssh2 | 8 MiB | 50 | 19 734.7 | 19 572.7 | 1.0× | 19 300 | 1.014 | 0.71 ms |
| OpenSSH | 64 KiB | 0 | 124.8 | 1.2 | 107× | 0 | — | 0.39 ms |
| OpenSSH | 1 MiB | 0 | 742.4 | 10.1 | 74× | 0 | — | 0.56 ms |
| OpenSSH | 8 MiB | 0 | 5 367.9 | 44.1 | 122× | 0 | — | 0.34 ms |
| OpenSSH | 32 MiB | 0 | 21 215.3 | 144.1 | 147× | 0 | — | 0.28 ms |
| OpenSSH | 64 KiB | 10 | 154.8 | 32.0 | 4.8× | 30 | 1.067 | 0.67 ms |
| **OpenSSH** | **1 MiB** | **10** | **934.6** | **193.5** | **4.8×** | **180** | **1.075** | **0.75 ms** |
| OpenSSH | 8 MiB | 10 | 6 683.0 | 1 390.3 | 4.8× | 1 300 | 1.069 | 0.69 ms |
| OpenSSH | 32 MiB | 10 | 26 388.6 | 5 495.2 | 4.8× | 5 140 | 1.069 | 0.69 ms |
| OpenSSH | 1 MiB | 50 | 917.8 | 925.3 | 1.0× | 900 | 1.028 | 1.41 ms |
| OpenSSH | 8 MiB | 50 | 6 627.3 | 6 644.0 | 1.0× | 6 500 | 1.022 | 1.11 ms |

**Result: the prediction holds in every cell.**
- At $`R = 10`$ ms every cell now sits 5–8 % above H1. The per-request excess fell from 41.2–42.0 ms to
  0.48–0.77 ms, the same size as the residual H1 already left at $`R \ge 50`$ ms (crypto, framing, the proxy
  hop). The worked example: OpenSSH 1 MiB at 10 ms went from 934.6 to **193.5 ms** (predicted ≈ 190).
- At $`R = 0`$ the whole-file read is now bound by the client's CPU, not by timers: 32 MiB in 268 ms (ssh2
  ident, 119 MiB/s) and 144 ms (OpenSSH ident, 222 MiB/s). The client process's own CPU time (`cpu_ms`) is
  42–76 % of the wall time in these cells; the rest is the server process and the loopback hop.
- At $`R = 50`$ ms the medians moved by −0.8 % to +0.8 %, inside run-to-run noise; there was no stall there to
  remove.
- p95 stayed within 1.3 % of the median in every $`R \ge 10`$ cell but one: ssh2 64 KiB at 10 ms (7.6 %, a
  4 ms spread on a 53 ms read). The $`R = 0`$ cells last only a few milliseconds, so their p95/median ratio (up
  to 3×) reflects scheduler jitter, not the transport.

**Consequence for E1.** The baseline's `fastGet` column (§5) was measured with the client-side stall still in
place, so its "stream / fastGet" headroom at $`R \le 10`$ ms is inflated: after E0, the sequential reader
at $`R = 0`$ (14.0 ms for 1 MiB, ssh2 ident) is already faster than the baseline's `fastGet` (169.3 ms). E3
re-measures `fastGet` together with the pipelined reader, all with E0 in place. Pipelining is still expected
to matter wherever $`R`$ dominates: at $`R \ge 10`$ ms the sequential reader remains at H1, one round trip
per request.

**Guard.** `test/ssh-transport-smoke.js` `testNoDelay` reads 1 MiB from the fixture with its server-side
`noDelay` on, and fails above 800 ms. With the fix commented out, the same arm measured 2 067 ms (the
one-sided stall of §4); with it, it passes in tens of milliseconds.

*E1–E3 and D1–D7 are recorded below as they land.*

## 10 · Regression guards

- **`TCP_NODELAY` (E0):** `ssh-transport-smoke` `testNoDelay`. A 1 MiB fixture read must finish within 800 ms.
  The stalled read takes ≈ 2 s, the fixed one tens of milliseconds, so the threshold separates them with a
  wide margin on either side (§9.1).

*Pending:*
- `ssh-transport-smoke` arms for the pipelined reader;
- `remote-pdf-e2e` byte counts.

[ADR-0045 — Slide-deck presentation]: ../design-decisions/0045-slide-deck-presentation.md
[ADR-0046 — Reload-safe, pipelined remote reads]: ../design-decisions/0046-reload-safe-pipelined-remote-reads.md
