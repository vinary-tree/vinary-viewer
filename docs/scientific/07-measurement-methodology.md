# 07 — Measurement methodology

**Status: Gap (methodology defined, harness not yet built).** vinary-viewer ships **no runtime performance
benchmark** today — no `perf`, `hyperfine`, or massif harness in the repository. The
[scientific pillar's overview](00-overview.md) names this as the project's largest measurement gap, and
scientific integrity requires that an absent measurement be *named*, not implied. This page writes down the
methodology that **will** be used when a benchmark is added, so that the first benchmark is rigorous rather
than ad hoc.

> **Why write the methodology before the harness?** Because the expensive mistakes in benchmarking are made in
> *how* you measure, not in *what* you measure: an un-pinned CPU frequency, a warm-vs-cold cache confound, a
> single run reported as if it were a distribution. Fixing the method up front means the first data point is
> trustworthy.

---

## 1. Principles (from the project's benchmarking convention)

The project's global convention (`CLAUDE.md`) already prescribes a rigorous benchmarking practice; this page
instantiates it for vinary-viewer.

1. **Be data-driven; profile before optimizing.** Never optimize on a hunch. Record a profile, identify the
   bottleneck, form a hypothesis, change one thing, and re-measure against the hypothesis — the same
   scientific loop the [ink-loss experiment](05-mathjax-inkloss-experiment.md) followed for correctness.
2. **Measure once, analyze from the artifact.** Run a benchmark once, tee its full output to a file, and
   analyze the file — rather than re-running to see different numbers (which invites cherry-picking and wastes
   the run). This is the same [tee-once discipline](../engineering/08-ci-and-validation-discipline.md) the
   validation gates use.
3. **Report time *and* space, and the trade-off between them.** A previewer's binding constraint is often
   memory, not latency (see [bounded-memory engineering](../engineering/10-bounded-memory-engineering.md)), so
   a latency-only number is incomplete.
4. **Prefer better algorithms and data structures over micro-tuning.** The largest wins in this codebase are
   asymptotic — the streaming pipeline's `$`\Theta(N) \to \Theta(1)`$` parse-memory bound
   ([theory/09 §8](../theory/09-document-streaming-and-the-wpda.md)) — not constant-factor.

---

## 2. Controlling the environment

Measurement noise dominates small effects unless the machine is pinned. Before recording:

- **Pin CPU affinity** and run the benchmark on isolated cores, so the scheduler does not migrate the process
  mid-measurement.
- **Fix the CPU frequency at maximum** (disable frequency scaling / turbo variability) so successive runs are
  comparable.
- **Warm the cache deliberately, and report warm and cold separately** — a first-open (cold) and a re-open
  (warm) are different questions for a previewer.

```bash
# Pin to an isolated core at a fixed frequency, tee once, analyze the artifact.
taskset -c 2 hyperfine --warmup 3 --export-json /tmp/vv-bench.json \
  'vv --cli large.md'          # CLI render is the cleanest headless timing target
```

---

## 3. Instruments, matched to the question

| Question | Instrument | Notes |
|----------|-----------|-------|
| End-to-end wall-clock of a headless render | **`hyperfine`** | statistical: warmups, multiple runs, mean ± σ. The `vv --cli` path is the natural target — no window, pipe-friendly, deterministic. |
| Where CPU time goes | **`perf record --call-graph lbr`** | low-overhead call-graph capture; analyze the report offline. Generate and analyze the report in parallel where possible. |
| Peak and shape of memory | **`valgrind --tool=massif`** | the space half of §1.3 — a heap-over-time profile, essential for validating the bounded-memory claims. |
| Deep CPU/microarchitecture | **Intel VTune** (`/opt/intel/oneapi/vtune/latest/`) | for hotspots that `perf` flags but cannot explain. |
| Where a thread blocks / sleeps | **`bcc` / `bcc-libbpf-tools`** | off-CPU and wall-time stacks — for latency that is *waiting*, not computing. |

The choice is driven by the question: a latency regression wants `hyperfine` + `perf`; a memory regression
wants massif; a "the UI stutters" report wants off-CPU stacks.

---

## 4. What to benchmark first, when the harness lands

The methodology is only useful applied to the right targets. When a harness is added, the first measurements
should validate the claims the rest of the suite *asserts but does not yet time*:

1. **Streaming first-paint latency vs. batch**, across document size — the claim that streaming trades total
   throughput for `$`\Theta(\text{first batch})`$` first-paint ([theory/09 §8](../theory/09-document-streaming-and-the-wpda.md)).
2. **Peak RSS vs. document size, streamed vs. batch** — the claim that streamed parse memory is `$`O(1)`$` in
   `$`N`$` ([scientific/02](02-bounded-memory-streaming-validation.md)), measured with massif so the *shape*
   (flat vs. linear) is visible, not just the peak.
3. **IR lowering throughput** — the constant-factor cost the common IR adds over the retired direct path, so
   the migration's cost is quantified, not assumed.

Each should be recorded as a scientific-ledger entry: environment, method, result, and — if an optimization
follows — hypothesis and re-measurement, exactly as the [ink-loss experiment](05-mathjax-inkloss-experiment.md)
is recorded here.

## 5. See also

- [engineering/10 — Bounded-memory engineering](../engineering/10-bounded-memory-engineering.md) — the space
  claims this methodology would validate.
- [theory/09 §8 — Complexity](../theory/09-document-streaming-and-the-wpda.md) — the asymptotic claims to time.
- [05 — MathJax ink-loss experiment](05-mathjax-inkloss-experiment.md) — the scientific-method loop, applied
  to a correctness defect, that a performance experiment would mirror.
