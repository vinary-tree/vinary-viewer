# 11 — Remote read and reload-safety experiments

**Status: in progress.**
- **Recorded:**
  - the baseline and its hypotheses, plus one discovery made while validating the instrument (H0);
  - E0, E1 and E2 (§9.1–§9.3);
  - why H2 failed at 32 MiB, and E4, the eager channel window that fixes it (§9.4).
- **Pending:**
  - E3: the re-measurement with E0–E4 in place, the pipeline depth, and H6's memory (§9.5);
  - the D-phase measurements of H4 and H5.

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
- **Whole remote opens (H3): the `open-pdf` and `open-md` readers.**
  - They time the open the app performs, `content_service.openRemoteUri`, rather than a bare read. That
    covers the stat, the read (verified for a PDF, ADR-0046 §Torn reads) and the discovery of the document
    group.
  - The server serves a group next to each size: `group-<size>.pdf`, `group-<size>.md` and a small
    `group-<size>.tex`.
    - The PDF is valid and padded to about `<size>`. The read is verified, so random bytes would time the
      settle loop instead.
    - The markdown file is `<size>` bytes of ASCII text, so its UTF-8 decode is lossless and its SHA-256
      still verifies.
  - Every timed open also checks the group it found: the opened file first, then its same-stem members.
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
| E4 | Grant the SFTP channel window eagerly, as OpenSSH does (added after §9.4's trace) | H2′ | A large read keeps ≈ 2 MiB in flight per round trip, not ≈ 1 MiB; H2's model holds again |
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

### 9.2 · E1 — pipelined, preallocated whole-file reader (2026-10-16)

**Intervention.** `remoteOpenFile(uri)` in `src/vinary/main/ssh_transport.js` returns a file handle with
`readAt`, `readRange`, `readAll`, `fstat` and `close`. The handle is registered with its connection like a
stream, so a dropped connection rejects its pending requests and the idle reaper leaves it alone.

`readAll`:
- preallocates the file (`Buffer.allocUnsafe(size)`) and refuses a file over the cap before any READ;
- keeps at most $`D`$ requests of $`C`$ bytes in flight, with $`C = L`$ (ssh2's `_maxReadLen`) and
  $`D = \min\!\left(64,\ \max\!\left(4,\ \lceil 2\,\text{MiB}/C \rceil\right)\right)`$. That gives $`D = 9`$ for
  an OpenSSH ident ($`DC = 2.23`$ MiB) and $`D = 64`$ for ssh2's ($`DC = 1.95`$ MiB);
- issues the end-of-file probe at exactly the expected size, in the same pipeline;
- re-requests a short read's remainder, and shrinks $`C`$ only when that remainder returns data (a server that
  caps reads);
- lets CLOSE land in the background.

`remoteReadFile`, the `stream` reader below, is OPEN → `readAll` → CLOSE. Called without a size hint, as here,
it also pays one FSTAT.

**Prediction (§8, H2).** OPEN + FSTAT + the window-bound body:

```math
\hat t_{\text{E1}} \approx 2R + R\cdot\max\!\left(\left\lceil \frac{S}{D\,C} \right\rceil,\; \frac{S}{W}\right) + \frac{S}{B_{cpu}}
```

- $`W = 2`$ MiB is ssh2's channel window (`Channel.js` `MAX_WINDOW`).
- $`S/B_{cpu}`$ is the same cell's time at $`R = 0`$.
- For 32 MiB at 50 ms this gives $`2R + 16R + 58.5 = 958.5`$ ms (OpenSSH) and $`2R + 17R + 80.8 = 1030.8`$ ms
  (ssh2).
- The headline prediction was OpenSSH 32 MiB at 50 ms: 26.3 s → ≈ 1–2 s.

**Run.** Same driver, pinning and `--server-nodelay` as §9.1.
- Readers: `stream` (now E1), `stream-legacy` (the pre-E1 algorithm, kept as `_readFileStream`) and `fastGet`.
- Cells: 2 warm-ups, then ≤ 9 timed reads or 30 s (32 MiB: ≤ 7 or 40 s).
- Raw data: `data/11/e1-fixed-nodelay-server-2026-10-16.txt` and its two `.json` files.
- A first cut was **slower** than the legacy reader on small files (ssh2 ident, 64 KiB at 50 ms: 296.5 against
  253.7 ms; `data/11/e1-prefix-nagle-server-2026-10-16.*`, fixture server with Nagle on). Three causes, all
  fixed before the commit and pinned by request-count assertions in `ssh-transport-smoke`:
  1. an end of file straddling the probe cost an extra round trip;
  2. that short read shrank the pipeline;
  3. CLOSE sat on the critical path.

| Ident | $`S`$ | $`R`$ (ms) | legacy (ms) | E1 (ms) | `fastGet` (ms) | legacy / E1 | $`\hat t_{\text{E1}}`$ (ms) | E1 / $`\hat t_{\text{E1}}`$ |
|---|---|---|---|---|---|---|---|---|
| ssh2 | 64 KiB | 50 | 253.5 | 153.0 | 253.6 | 1.7× | 151.5 | 1.010 |
| ssh2 | 1 MiB | 50 | 2 530.6 | 156.9 | 258.8 | 16.1× | 155.3 | 1.010 |
| ssh2 | 32 MiB | 0 | 267.9 † | 80.8 | 129.8 | 3.3× | — | — |
| ssh2 | 32 MiB | 50 | 78 751.7 ‡ | 1 812.9 | 1 933.3 | 43× | 1 030.8 | **1.759** |
| OpenSSH | 64 KiB | 50 | 152.6 | 152.7 | 203.2 | 1.0× | 151.2 | 1.010 |
| OpenSSH | 1 MiB | 50 | 914.8 | 156.0 | 207.9 | 5.9× | 154.4 | 1.010 |
| OpenSSH | 32 MiB | 0 | 144.1 † | 58.5 | 114.8 | 2.5× | — | — |
| OpenSSH | 32 MiB | 50 | 26 278.4 ‡ | 1 748.7 | 1 902.3 | 15.0× | 958.5 | **1.824** |

† The 32 MiB legacy reads were not repeated in this run. At $`R = 0`$ the value is §9.1's (the same legacy
algorithm, with E0). ‡ At 50 ms it is §5's; E0 changed nothing at 50 ms (§9.1).

**Result.**
- **The headline holds.** OpenSSH 32 MiB at 50 ms went from 26.3 s to **1.75 s** (15×), the ssh2 ident from
  78.8 s to 1.81 s (43×). 1 MiB at 50 ms went from 0.91 s / 2.53 s to 0.156 s.
- **Small files are no slower.** 64 KiB at 50 ms costs 3 R with either reader against OpenSSH (152.7 against
  152.6 ms). The ssh2 ident's 5 R legacy read ($`k = 3`$) drops to 3 R.
- **E1 beats `fastGet` in every cell.** `fastGet` keeps the same 2 MiB in flight but writes the file to disk and
  reads it back.
- **H2 holds within 1 % wherever one window covers the file**, the 64 KiB and 1 MiB cells.
- **At 32 MiB H2 is refuted by its own criterion.** The time is 1.76× (ssh2) and 1.82× (OpenSSH) the model, more
  than 25 % off with $`DC \ge 2`$ MiB.
  - A post-hoc fit with one MiB per round trip, $`2R + 32R + S/B_{cpu}`$, lands within 2 %: 1 758.5 ms against
    1 748.7 (OpenSSH), 1 780.8 against 1 812.9 (ssh2).
  - `fastGet` (64 × 32 KiB) hits the same ceiling (1.90 / 1.93 s), so the ceiling is not this reader's.
  - A fit is not a mechanism. §9.4 (E3) diagnoses the cause.
- **H6 is untested here.** The run had no `--expose-gc`, so the peak `arrayBuffers` (130–161 MiB for 32 MiB,
  every reader) includes uncollected garbage from earlier reads. E3 repeats it with `--expose-gc`.

### 9.3 · E2 — the document group's stats, concurrent and overlapped with the read (2026-10-17)

**Intervention.** A remote open of a group kind (PDF, Markdown, Org, LaTeX, Mermaid, diff) also looks for the
file's same-stem companions: `talk.tex` beside `talk.pdf` is what offers the Preview/Source switch.
- **Before:** `remoteSiblingsGroup` in `src/vinary/main/content_service.js` stat'ed the 12 candidates one after
  another, after the read. The candidates are the stem with each of the 12 group extensions, including the
  opened file itself.
- **After:**
  - it stats them **all at once** (`Promise.all`);
  - it reuses the caller's stat of the opened file instead of stat'ing that one again;
  - `openRemoteUri` starts the lookup **before** its read and awaits it after.

**Instrument.** The `open-pdf` and `open-md` readers of §2: the whole `openRemoteUri` on a group of three files
(`.pdf`, `.md`, `.tex`), every open SHA-256-verified and its group checked.

**Model and prediction** (written before the second run: `data/11/e2-prediction.txt`).
- **Before:** stat + read path + 12 sequential stats.
- **After:** the 11 stats of the other candidates are one batch. It is answered in 1 R, inside a read path of at
  least 2 R (Markdown: OPEN, body; PDF: OPEN, the verified read's tail READ, body, FSTAT), so it adds nothing to
  the critical path:

```math
t_{\text{after}} \approx t_{\text{before}} - 12R
```

- **Refuted if,** in any cell with $`R \ge 10`$ ms, the saving differs from $`12R`$ by more than $`R`$.
- **The baseline already fits the read paths:** at $`R = 100`$ ms, `open-md` is 15.1 R (stat + OPEN + body +
  12) and `open-pdf` 17.2 R (stat + OPEN + tail + body + FSTAT + 12).

**Run.**
- Pinning as §9.1, `--server-nodelay`.
- Cells: sizes 64 KiB / 1 MiB / 8 MiB, $`R \in \{0, 10, 50, 100\}`$ ms, both idents; 2 warm-ups, ≤ 9 timed
  opens or 30 s per cell.
- The baseline ran at `5b06fa5`, before E2; the second run with E2.
- Raw data: `data/11/e2-baseline.{txt,json}`, `data/11/e2-after.{txt,json}`. The comparison, every cell:
  `data/11/e2-analysis.txt`, produced by
  [`scripts/analyze-e2-sibling-stats.cjs`](../../scripts/analyze-e2-sibling-stats.cjs).

OpenSSH ident (the ssh2 ident's 18 cells agree with these within 0.3 R; see the analysis file):

| $`R`$ (ms) | $`S`$ | `open-pdf` before → after (ms) | saved / $`R`$ | `open-md` before → after (ms) | saved / $`R`$ |
|---|---|---|---|---|---|
| 10 | 64 KiB | 180.7 → 54.8 | 12.59 | 159.4 → 33.7 | 12.57 |
| 10 | 1 MiB | 183.3 → 57.6 | 12.57 | 162.3 → 36.8 | 12.55 |
| 10 | 8 MiB | 271.5 → 145.5 | 12.60 | 253.1 → 128.0 | 12.50 |
| 50 | 64 KiB | 862.2 → **254.8** | 12.15 | 761.7 → **153.8** | 12.16 |
| 50 | 1 MiB | 867.0 → 257.9 | 12.18 | 765.8 → 157.3 | 12.17 |
| 50 | 8 MiB | 1 237.8 → 626.0 | 12.24 | 1 141.8 → 531.2 | 12.21 |
| 100 | 64 KiB | 1 715.3 → 507.5 | 12.08 | 1 514.5 → 305.5 | 12.09 |
| 100 | 1 MiB | 1 718.3 → 510.1 | 12.08 | 1 517.2 → 307.6 | 12.10 |
| 100 | 8 MiB | 2 436.0 → 1 227.5 | 12.08 | 2 236.9 → 1 028.3 | 12.09 |

**Result: H3 holds in all 36 cells with $`R \ge 10`$ ms, both idents.**
- **The saving is 12.06–12.60 R**, never more than 0.60 R from $`12R`$.
- **It slightly exceeds $`12R`$:** by 5–6 ms at $`R = 10`$ and 6–12 ms at 50–100 ms. That is 12 requests' worth of
  the ≈ 0.5–1 ms per-request residual of §9.1.
- **At $`R = 0`$ the saving is 0–3 ms.**
- **At 50 ms a remote PDF now opens in 255 ms instead of 862 ms (3.4×), and a remote Markdown file in 154 ms
  instead of 762 ms (4.9×).**
- **What remains is the read path alone:** 5.1 R for the PDF, 3.1 R for Markdown.

**The round trips that remain, and why they stay.**
- **The PDF's tail READ before its body** is deliberate (ADR-0046 §Torn reads). A torn file costs one ~1 KiB
  READ instead of a download, and the settle loop may probe several times (H5).
- **The FSTAT after the body** detects a rewrite *during* the read. Pipelining it behind the last READ would save
  1 R, but SFTP does not order it after those READs for an *external* writer.
  - Draft-ietf-secsh-filexfer-02 §6.1 requires only that "requests relating to the same file" give the results
    of sequential processing, and lets "non-overlapping read/write requests to the same file" run in parallel.
  - §7 allows responses "in a different order from the order in which the requests were sent".
  - A server may therefore answer the FSTAT while earlier READs are still executing, and miss a writer that
    lands between them. The fixture's asynchronous `fs` calls do exactly that.
- **The initial stat** decides directory against file and supplies the size.
  - Issuing the OPEN alongside it would save 1 R per open.
  - The cost: every reader in `openRemoteUri` (and the settle loop's re-attempts) would have to accept a handle
    opened before the kind is known, and close it on the directory and error paths.
  - It is outside ADR-0046's interventions and is not pursued here.

### 9.4 · Why H2 failed at 32 MiB, and E4: the eager channel window (2026-10-17)

**Question.** §9.2 left H2 refuted at 32 MiB: 1.76–1.82× its model, about 1 MiB per round trip. `fastGet` hit the
same ceiling, so the ceiling sits below both readers.

**Instrument.** [`scripts/diag-sftp-window.cjs`](../../scripts/diag-sftp-window.cjs) traces one whole-file read
through the real transport and the latency proxy, logging both sides of the protocol:
- every READ as the server receives it;
- every channel-data packet the client's SFTP channel consumes, with the client's receive window after it;
- every window grant (`SSH_MSG_CHANNEL_WINDOW_ADJUST`) the client sends.

It counts them per round-trip bucket. It is a trace, not a benchmark: one read, no repetition.

**Hypothesis H2′: ssh2 keeps only half its window in flight.**
- **The basis is ssh2 1.17.0's flow control.**
  - `lib/Channel.js` defines `MAX_WINDOW` = 2 MiB and `WINDOW_THRESHOLD` = `MAX_WINDOW / 2`.
  - In `lib/client.js`, every channel-data packet debits the receive window, and a grant goes out only when the
    window has fallen to the threshold or below.
  - The grant is `MAX_WINDOW − window`, i.e. it tops the window back up to 2 MiB.
- **A first derivation predicted no loss at all, and was wrong.** It assumed both 1 MiB grants of an arriving
  2 MiB burst would go out together, keeping 2 MiB circulating.
- **The trace refuted that derivation and confirmed H2′.** With ssh2's policy, OpenSSH ident, 50 ms
  (`data/11/e4-window-trace-ssh2-openssh.txt`):
  - The first round trip carries the initial 2 MiB window.
  - Every later round trip carries **≈ 1 MiB** (1 032–1 048 KiB), with **exactly one** grant of ≈ 1 MiB.
  - Only four READs reach the server per round trip, although nine (2.23 MiB) are kept outstanding.
- **The mechanism.**
  - After the first burst the client's window sits just above the threshold, ≈ 1 048 KiB.
  - The next burst's first packet takes it to ≈ 1 016 KiB, and the one grant tops up just that deficit,
    ≈ 1 MiB.
  - The rest of the burst leaves the window above the threshold again, so no second grant follows.
  - From then on a single ≈ 1 MiB credit circulates: the server spends it in one burst, and that burst triggers
    the next grant, one round trip later.
  - The effective window is $`W - T = W/2`$, where $`T`$ is the threshold.

**Reference behaviour.** OpenSSH's client sends a grant as soon as more than three maximum-size packets are
owed, or the window falls below half (`channels.c` `channel_check_window`: `local_window_max − local_window >
local_maxpacket*3` or `local_window < local_window_max/2`, provided `local_consumed > 0`). That keeps nearly the
whole window in flight.

**Intervention E4.** `eagerWindow` in `src/vinary/main/ssh_transport.js` applies OpenSSH's policy to the
transport's SFTP channel.
- After each channel-data packet the SFTP parser has consumed, once more than `WINDOW_STEP` = 3 × 32 KiB is owed,
  it tops the window back up to `WINDOW_MAX` = 2 MiB.
- It grants **no more credit than ssh2 itself would**. The server can still have at most 2 MiB outstanding, so
  the memory bound is unchanged; credit is only granted sooner.
- It relies on ssh2 private fields: the channel-emulating SFTP object's `push`, `incoming.window` and
  `outgoing.{id, state}`, and `_protocol.channelWindowAdjust`.
  - If any of them is missing, ssh2's own policy stays in place: correct, only slower.
  - `transport.configure({ eagerWindow: false })` selects ssh2's policy on purpose; the trace's control arm and
    the smoke's control arm use it.

**Prediction.** If H2′ is right, granting eagerly restores H2's own model. For this size-hinted read (OPEN plus
the body, no FSTAT):

```math
\hat t \approx R + R\cdot\max\!\left(\left\lceil \frac{S}{D\,C} \right\rceil,\; \frac{S}{W}\right) + \frac{S}{B_{cpu}}
```

That gives 50 + 16 · 50 + 58.5 = **908.5 ms** for OpenSSH and 50 + 17 · 50 + 80.8 = **980.8 ms** for the ssh2
ident. H2's criterion still applies: E4 is refuted if a read is more than 25 % off.

**Result** (`data/11/e4-window-trace-{ssh2,eager}-{openssh,ssh2}.txt`; client pinned to cores 2–5, 32 MiB at
50 ms):

| Ident | Window policy | Total (ms) | Round trips | Grants | Steady volume per round trip | Total / $`\hat t`$ |
|---|---|---|---|---|---|---|
| OpenSSH | ssh2's | 1 769.2 | 35.4 | 31 | ≈ 1 MiB | 1.947 |
| OpenSSH | eager (E4) | **980.2** | 19.6 | 269 | ≈ 2 MiB | **1.079** |
| ssh2 | ssh2's | 1 797.7 | 36.0 | 31 | ≈ 1 MiB | 1.833 |
| ssh2 | eager (E4) | **1 045.4** | 20.9 | 262 | ≈ 2 MiB | **1.066** |

- **H2′ holds and E4 restores H2.** Both idents land within 8 % of the model.
- **The read is 1.72–1.80× faster.**
- **The cost is about eight times as many grants.** Each is a few dozen bytes on the wire, ≈ 13 KiB upstream per
  32 MiB read, or 0.04 %.
- **A first, scratch prototype measured 1 001.5 ms** (OpenSSH ident) before the transport changed; it monkeypatched
  the same policy into the diagnostic.
- **The timed matrix repeats this for every reader** with E4 in place (§9.5). `fastGet` shares the channel, so it
  benefits too.

**Found on the way: a pre-existing connection-pool race** (fixed separately in `486b9ea`).
- **How it surfaced:** the smoke guarding E4 reconnected to one fixture server and then hung in `server.close()`.
  `ss` showed an ESTABLISHED client connection while the transport's pool was empty.
- **The cause:**
  - A closed connection's late `close` event deleted its pool entry *by key*.
  - A reconnect pools its successor before the successor's handshake, so the late `close` evicted the successor.
  - The successor was leaked open, and the next request dialled a third connection.

*E3, the re-measurement with E0–E4 in place (§9.5), and the D-phase measurements (H4, H5) are recorded
below as they land.*

## 10 · Regression guards

- **`TCP_NODELAY` (E0):** `ssh-transport-smoke` `testNoDelay`. A 1 MiB fixture read must finish within 800 ms.
  The stalled read takes ≈ 2 s, the fixed one tens of milliseconds, so the threshold separates them with a
  wide margin on either side (§9.1).

- **Pipelined reader (E1):** `ssh-transport-smoke` `testPipelinedReader`.
  - Byte-exact reads at sizes 0, 1, C−1, C, C+1, 3C+17 and 8 MiB, under both idents, with and without a size
    hint.
  - At most D requests in flight, and actually pipelined; out-of-order replies.
  - A file that grows past the probe or shrinks mid-read; the cap refused with 0 READs; a server that caps reads.
  - The depth-1 retry; the reaper mid-read; a dropped connection rejecting within 1 s; no leaked handles.
  - Request-count assertions pin the three small-file fixes of §9.2.
- **Group stats (E2):** `content-service-smoke` `testRemote`.
  - Every group-candidate stat reply is held 400 ms. The smoke then asserts, for a PDF and for a LaTeX file:
    - at least 11 stats in flight at once;
    - at least one still in flight when the opened file's body is first READ;
    - exactly one stat of the opened file.
  - Three negative controls each fail at their own assertion:
    1. sequential stats ("max in flight 1");
    2. concurrent stats started after the read ("0 still in flight at its first body READ");
    3. concurrent and overlapped, but re-stat'ing the opened file.

- **Eager channel window (E4):** `ssh-transport-smoke` `testEagerWindow`.
  - Every grant the client sends on the SFTP channel during an 8 MiB read is recorded. This counts grants rather
    than timing anything:
    - with the policy, at least 48 grants, none larger than `WINDOW_STEP` + one packet;
    - with `eagerWindow: false` (the control arm), at most 9 grants of ≈ 1 MiB.
  - It pins `WINDOW_MAX` and `WINDOW_STEP` against ssh2's own `MAX_WINDOW` and `PACKET_SIZE`, and the private
    surface the policy uses.
- **Connection pool (found in §9.4):** `ssh-transport-smoke` `testStaleCloseKeepsSuccessor`. After `closeAll`
  and a reconnect, the server must hold exactly one live connection, and none after the final `closeAll`.
  Deleting by key fails it with two.

*Pending:* `remote-pdf-e2e` byte counts.

[ADR-0045 — Slide-deck presentation]: ../design-decisions/0045-slide-deck-presentation.md
[ADR-0046 — Reload-safe, pipelined remote reads]: ../design-decisions/0046-reload-safe-pipelined-remote-reads.md
