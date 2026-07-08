(ns vinary.tui.core
  "vv-tui — the interactive full-screen terminal viewer. Wires the raw-terminal driver (vinary.tui.term) → key
   parser (vinary.tui.keys) → pure reducer (vinary.tui.state) and paints the viewport/find/toc models, reusing the
   CLI's IR front-ends + the shared WPDA streaming engine (vinary.terminal.stream). Graphics are forced OFF (images
   → placeholder lines) so the scrolling viewport is line-exact. Batch docs retain their IR so SIGWINCH re-wraps at
   the new width without re-reading; large logs stream into a bounded viewport ring. `--drive <keyfile>` (with
   --no-tty) replays keys through the SAME keys→state→frame pipeline and dumps the final frame deterministically —
   the headless test seam that needs no pseudo-tty."
  (:require ["../main/content_service.js" :as cs]
            ["path" :as path]
            ["fs" :as fs]
            [clojure.string :as str]
            [vinary.cli.render :as render]
            [vinary.terminal.caps :as caps]
            [vinary.terminal.syntax :as tsyntax]
            [vinary.terminal.stream :as tstream]
            [vinary.grammar-catalog :as gc]
            [vinary.tui.term :as term]
            [vinary.tui.keys :as keys]
            [vinary.tui.state :as state]
            [vinary.tui.viewport :as vp]
            [vinary.tui.find :as find]
            [vinary.tui.toc :as toc]))

(def ^:private version "vv-tui 0.3.0")
(def ^:private usage
  (str/join "\n"
    ["vv-tui — interactively page a document in the terminal"
     "" "Usage: vv-tui [options] <file>" ""
     "Keys:  ↑/k ↓/j scroll · Space/b page · g/G top/bottom · / find (n/N next/prev) · t contents · q quit" ""
     "Options:"
     "      --width N       wrap column (default: terminal width)"
     "      --no-color      disable ANSI colour"
     "      --drive FILE    (test) replay key bytes from FILE headlessly and dump the final frame"
     "  -h, --help          show this help"
     "  -V, --version       show the version"]))

(def ^:private stream-threshold (* 5 1024 1024))
(def ^:private stream-cap 100000)                          ; bounded viewport ring for a streamed log
(def ^:private text-kinds #{"markdown" "text" "log" "mermaid" "html" "source"})

(defn- parse-args [args]
  (loop [as args file nil opts {}]
    (if (empty? as)
      {:file file :opts opts}
      (let [a (first as)]
        (cond
          (#{"-h" "--help"} a)    (recur (rest as) file (assoc opts :help true))
          (#{"-V" "--version"} a) (recur (rest as) file (assoc opts :version true))
          (= "--no-color" a)      (recur (rest as) file (assoc opts :no-color true))
          (= "--width" a)         (recur (drop 2 as) file (assoc opts :width (js/parseInt (or (second as) "80"))))
          (= "--drive" a)         (recur (drop 2 as) file (assoc opts :drive (second as)))
          (str/starts-with? a "-") (recur (rest as) file opts)               ; ignore unknown flags
          :else                    (recur (rest as) (or file a) opts))))))    ; first non-flag arg is the file

(defn- ansi-opts [opts w]
  (let [c (caps/detect (assoc opts :force-color (when (:drive opts) true)))]
    {:width      (or (:width opts) w (:width c))
     :color?     (if (:no-color opts) false (:color? c))
     :truecolor? (:truecolor? c)
     :hyperlinks? false
     :graphics   nil                                        ; the scrolling TUI uses placeholders, never inline images
     :highlight  (when (and (not (:no-color opts)) (:color? c)) (tsyntax/highlighter))
     :image      nil}))

(defn- kind-of [file]
  (let [k0 (.classifyName cs file)]
    (if (and (= "text" k0) (gc/grammar-for-path file gc/bundled-grammars {})) "source" k0)))

(defn- big? [file] (> (.-size (.statSync fs file)) stream-threshold))

;; ── loading a document → {:lines :anchors :toc :ir :base-dir :streaming?} ──────
(defn- load-batch [payload aopts base-dir]
  (-> (render/render-doc payload aopts)
      (.then (fn [{:keys [ir toc lines anchors]}]
               {:lines lines :anchors anchors :toc toc :ir ir :base-dir base-dir :streaming? false}))))

(defn- load-doc
  "Promise<{:lines :anchors :toc :ir? :base-dir :streaming? :file file}>. Text kinds read directly (bypassing
   content_service content-sniffing); a large log/text streams (empty initial lines, filled by the stream)."
  [file aopts]
  (let [kind (kind-of file)
        base-dir (some->> file (.dirname path))]
    (cond
      (and (#{"log" "text"} kind) (big? file))
      (js/Promise.resolve {:lines [] :anchors {} :toc [] :ir nil :base-dir base-dir :streaming? true :file file})
      (contains? text-kinds kind)
      (load-batch {:kind kind :path file :text (.readFileSync fs file "utf8")} aopts base-dir)
      (= "image" kind)
      (load-batch {:kind "image" :path file} aopts base-dir)
      :else
      (-> (.openUri cs file) (.then (fn [p] (load-batch (js->clj p :keywordize-keys true) aopts base-dir)))))))

;; ── frame composition (pure over the state + size) ────────────────────────────
(defn- body-rows [st w body-h]
  (if (= :toc (:mode st))
    (toc/overlay-lines (:toc st) w body-h)
    (let [{:keys [slice top]} (vp/visible (assoc (:vp st) :h body-h))
          f (:find st)]
      (map-indexed (fn [i line] (if f (find/highlight line (find/line-spans f (+ top i))) line)) slice))))

(defn- status-row [st name total body-h]
  (let [top (get-in st [:vp :top]) dropped (get-in st [:vp :dropped] 0)
        pos (str (min total (+ top body-h)) "/" total)]
    (case (:mode st)
      :find (str "/" (:query st))
      :toc  "  ↑/↓ select · Enter jump · t/Esc close · q quit"
      (str " " name "  " pos (when (pos? dropped) (str "  (+" dropped " earlier)"))
           "  —  j/k ↑↓ · Space page · / find · t toc · q quit"))))

(defn- compose-frame [st w h name]
  (let [body-h (max 1 (dec h))
        rows   (vec (body-rows st w body-h))
        rows   (into rows (repeat (max 0 (- body-h (count rows))) ""))
        all    (conj (subvec rows 0 body-h) (status-row st name (get-in st [:vp :total] (count (get-in st [:vp :lines]))) body-h))]
    (str (term/home)
         (str/join "" (map-indexed (fn [r ln] (str (term/cursor (inc r) 1) (term/clear-eol) ln)) all)))))

;; ── the interactive app ───────────────────────────────────────────────────────
(defn- run-interactive [doc file opts]
  (let [{:keys [w h]} (term/size)
        aopts   (atom (ansi-opts opts w))
        name    (.basename path file)
        ir      (atom (:ir doc))
        base    (:base-dir doc)
        st      (atom (state/init (-> (vp/viewport w (max 1 (dec h)) (when (:streaming? doc) stream-cap))
                                      (vp/set-lines (:lines doc)))
                                  (toc/build (:toc doc) (:anchors doc))))
        pending (atom [])                                   ; keys.feed partial-escape buffer
        esc-timer (atom nil)
        stop-stream (atom nil)
        painting (atom false)
        paint! (fn [] (when-not @painting
                        (reset! painting true)
                        (js/queueMicrotask (fn [] (reset! painting false)
                                             (let [{:keys [w h]} (term/size)]
                                               (term/write! (compose-frame @st w h name)))))))
        apply-ev (fn [ev]
                   (swap! st state/step ev)
                   (when (:quit? @st)
                     (when-let [s @stop-stream] (s))
                     (term/restore!) (js/process.exit 0))
                   (paint!))
        on-key (fn [buf]
                 (let [[p evs] (keys/feed @pending (js/Array.from buf))]
                   (reset! pending p)
                   (doseq [ev evs] (apply-ev ev))
                   ;; lone-ESC flush: if a bare ESC is held, emit it as :escape after a short delay
                   (when @esc-timer (js/clearTimeout @esc-timer))
                   (when (seq @pending)
                     (reset! esc-timer (js/setTimeout (fn []
                                                        (let [[p2 evs2] (keys/flush @pending)]
                                                          (reset! pending p2)
                                                          (doseq [ev evs2] (apply-ev ev)))) 40)))))
        relayout (fn []                                     ; SIGWINCH: re-wrap batch docs at the new width; re-slice logs
                   (let [{:keys [w h]} (term/size)
                         body-h (max 1 (dec h))]
                     (if (and @ir (not= w (:width @aopts)))
                       (let [_ (swap! aopts assoc :width w)
                             {:keys [lines anchors]} (render/render-ir-lines @ir @aopts base)]
                         (swap! st state/resize w body-h lines (toc/build (:toc doc) anchors)))
                       (swap! st update :vp vp/resize w body-h (get-in @st [:vp :lines])))
                     (paint!)))
        resize-timer (atom nil)
        on-resize (fn [] (when @resize-timer (js/clearTimeout @resize-timer))
                    (reset! resize-timer (js/setTimeout relayout 90)))]   ; debounce a SIGWINCH storm
    ;; stream a large log into the viewport ring
    (when (:streaming? doc)
      (reset! stop-stream
              (tstream/stream-records!
               file
               {:pace     (fn [f] (js/setImmediate f))
                :on-blocks (fn [blocks]
                             (swap! st state/append-lines (str/split (render/render-record-blocks blocks @aopts) #"\n" -1))
                             (paint!))
                :on-error (fn [e] (term/restore!) (.error js/console (str "vv-tui: " (.-message e))) (js/process.exit 1))})))
    (term/init! {:on-key on-key :on-resize on-resize :on-resume paint! :no-tty? false})
    (paint!)))

;; ── --drive: replay keys headlessly, dump the final frame (deterministic test seam) ──
(defn- run-drive [doc file opts drive-file]
  (let [w (or (:width opts) 80) h 24
        aopts (ansi-opts opts w)
        name  (.basename path file)
        st    (atom (state/init (-> (vp/viewport w (max 1 (dec h)) (when (:streaming? doc) stream-cap))
                                    (vp/set-lines (:lines doc)))
                                (toc/build (:toc doc) (:anchors doc))))
        drive (fn []
                (let [bytes (js/Array.from (.readFileSync fs drive-file))
                      [pending evs] (keys/feed [] bytes)
                      [_ evs2] (keys/flush pending)]
                  (doseq [ev (concat evs evs2)] (swap! st state/step ev)))
                (.write js/process.stdout (compose-frame @st w h name))
                (.write js/process.stdout "\n")
                (js/process.exit 0))]
    (if (:streaming? doc)
      ;; drain the stream fully first (test logs are small), then replay keys
      (tstream/stream-records!
       file
       {:on-blocks (fn [blocks] (swap! st state/append-lines (str/split (render/render-record-blocks blocks aopts) #"\n" -1)))
        :on-done drive
        :on-error (fn [e] (.error js/console (str "vv-tui: " (.-message e))) (js/process.exit 1))})
      (drive))))

(defn ^:export main []
  (let [{:keys [file opts]} (parse-args (drop 2 (js->clj js/process.argv)))]
    (cond
      (:help opts)    (do (println usage) (js/Promise.resolve nil))
      (:version opts) (do (println version) (js/Promise.resolve nil))
      (nil? file)     (do (.write js/process.stderr (str usage "\n")) (set! (.-exitCode js/process) 1) (js/Promise.resolve nil))
      :else
      (let [{:keys [w]} (term/size)]
        (-> (load-doc file (ansi-opts opts w))
            (.then (fn [doc]
                     (if (:drive opts)
                       (run-drive doc file opts (:drive opts))
                       (run-interactive doc file opts))))
            (.catch (fn [e]
                      (term/restore!)
                      (.write js/process.stderr (str "vv-tui: " file ": " (.-message e) "\n"))
                      (js/process.exit 1))))))))
