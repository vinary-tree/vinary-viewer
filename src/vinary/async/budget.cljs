(ns vinary.async.budget
  "The clock behind vinary.async.scheduler's frame budget, isolated so the budgeting arithmetic can be
   tested without a browser and without waiting real milliseconds.

   Split out rather than inlined for one reason: 'did this slice overrun its budget?' is the decision that
   determines whether a chunked job blocks a frame, and a decision that important should be assertable
   directly. With a substitutable clock a test can advance time by exact amounts and check the boundary
   (`>=`, not `>`) rather than sleeping and hoping.")

(defn- default-now
  "Milliseconds on a monotonic clock. `performance.now()` where it exists (it is monotonic and immune to
   wall-clock adjustment); `Date.now()` otherwise, which is what the :node-test build gets."
  []
  (if (and (exists? js/performance) (.-now js/performance))
    (.now js/performance)
    (.now js/Date)))

(defonce ^:private clock (atom default-now))

(defn now
  "Current time in milliseconds, through the installed clock."
  []
  (@clock))

(defn set-clock!
  "Install `f` as the clock (tests). Pass nil to restore the real one."
  [f]
  (reset! clock (or f default-now))
  nil)

(defn elapsed
  "Milliseconds since `t0`."
  [t0]
  (- (now) t0))

(defn spent?
  "Has a slice that started at `t0` used up `budget-ms`?

   `>=` rather than `>` on purpose: a budget of 0 must yield after exactly one unit of work rather than
   spinning forever on a clock that has not advanced, which is precisely what a test with a frozen clock
   would do."
  [t0 budget-ms]
  (>= (elapsed t0) budget-ms))
