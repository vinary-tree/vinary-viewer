(ns vinary.main.startup
  "Pure, electron-free startup configuration for the main process, so the node :test build can assert
   the app's invariant Chromium switches, the GPU-mode predicates, and command-line argument parsing
   without loading electron."
  (:require [clojure.string :as str]
            [vinary.app.uri :as uri]
            [vinary.file-type :as file-type]))

(def chromium-switches
  "Chromium command-line switches the app appends unconditionally at launch (applied by the doseq in
   vinary.main.core/main, BEFORE app `whenReady` — the required timing for compositor switches):

     - \"disable-gpu-sandbox\"      — Linux often sandboxes the GPU process so it cannot open the system
                                      DRI/GBM driver (\"MESA-LOADER: failed to open dri … Permission
                                      denied\"); loosen only the GPU sandbox (the renderer stays sandboxed).

     - \"disable-partial-raster\"   — RASTERIZATION stage (cc::LayerTreeSettings::use_partial_raster):
                                      stop reusing previously-rastered TILE CONTENT. Defensive only.

     - \"ui-disable-partial-swap\"  — PRESENTATION stage (viz::RendererSettings::partial_swap_enabled):
                                      present the FULL viewport every frame. Defensive only.

   NOTE — neither partial-raster nor partial-swap fixes the NVIDIA + Wayland scroll-band (a copy of the
   window's top ~1/5 painted into the bottom ~1/5 while scrolling). Screen-captured A/B testing this
   session isolated the actual lever to `disable-gpu-rasterization?` below. These two switches are kept
   (low cost; may help other software-compositor modes) but are NOT the band fix."
  ["disable-gpu-sandbox" "disable-partial-raster" "ui-disable-partial-swap"])

(defn- wayland-session?
  [{:keys [XDG_SESSION_TYPE WAYLAND_DISPLAY]}]
  (boolean (or (= "wayland" XDG_SESSION_TYPE)
               (and WAYLAND_DISPLAY (not= "" WAYLAND_DISPLAY)))))

(defn disable-gpu-rasterization?
  "Whether to append Chromium's --disable-gpu-rasterization at startup — the NVIDIA + Wayland scroll-band
   fix that KEEPS the GPU process (the higher-performance fix).

   The band (a copy of the window's top ~1/5 painted into the bottom ~1/5 while scrolling; multiple bands;
   worsened by cursor motion) is produced when GPU-rasterized tiles go through the GPU process's broken
   Vulkan-on-Wayland window-surface PRESENTATION. Screen-captured + visually-verified A/B this session
   isolated the lever: with the GPU process on, adding/removing --disable-gpu-rasterization flips the band
   CLEAN<->BANDED with every other flag held constant (default, --use-angle=gl/gl-egl/vulkan, SkiaRenderer,
   vsync, srgb all band without it; all clean with it). Disabling GPU rasterization makes tiles
   software-rasterized (already software-blocklisted on this host — gpu_compositing/rasterization =
   disabled_software — so NO performance loss) while KEEPING the GPU process for compositing/presentation/
   WebGL/video; this is faster than removing the GPU process entirely (disable-hardware-acceleration?).

   Default ON for Wayland sessions; VV_GPU_RASTER=1 opts out (force full GPU rasterization; band returns).

   `env` is a map of env-var values (strings or nil):
     :VV_GPU_RASTER     — set to opt out (keep GPU rasterization on).
     :XDG_SESSION_TYPE / :WAYLAND_DISPLAY — detect a Wayland session (the affected presentation path)."
  [{:keys [VV_GPU_RASTER] :as env}]
  (and (not VV_GPU_RASTER) (wayland-session? env)))

(defn disable-hardware-acceleration?
  "Whether to remove Chromium's GPU process entirely (app.disableHardwareAcceleration) — the last-resort
   FULL-software escape hatch, only when VV_SOFTWARE_GL is set. The default band fix keeps the GPU process
   (see disable-gpu-rasterization?); this is for ruling the GPU out entirely or for hosts where even the
   GPU-raster-off path misbehaves.

   `env`: {:VV_SOFTWARE_GL <string|nil>}."
  [{:keys [VV_SOFTWARE_GL]}]
  (boolean VV_SOFTWARE_GL))

(def usage-text
  "Top-level `vv` usage, printed by `electron . --help` / `vv --gui --help` — mirrors the install.sh dispatcher
   launcher's own --help (the primary path for `vv --help`)."
  (str/join "\n"
    ["vinary-viewer — preview Markdown, PDFs, images, diagrams & source"
     ""
     "Usage:"
     "  vv [--gui] [files…]   open in the desktop GUI (default), one tab per file/URL"
     "  vv --cli  [files…]    render documents to the terminal  (pipe-friendly:  vv --cli x.md | less)"
     "  vv --tui  <file>      interactively page a document      (scroll · / find · t contents · q quit)"
     "  vv --help | --version"
     ""
     "Files may be local paths or URIs (https:// · ssh:// · sftp:// · vv-archive://)."
     ""
     "Stdin:  piped input becomes the FIRST document (a lone '-' repositions it):"
     "  git diff | vv -t diff        view a diff from a pipe (unified/side-by-side)"
     "  make 2>&1 | vv               view piped text literally (plain text)"
     ""
     "Options:"
     "  -t, --type TYPE       file type for the Nth file (repeatable; stdin is file 0; files without a"
     "                        type deduce from their extension, else plain text). TYPE is a MIME type"
     "                        (text/x-diff, application/pdf), a short alias (diff, md, csv, log, table),"
     "                        or a grammar language (python, rust — highlighted source). --file-type is"
     "                        accepted as an alias."
     ""
     "Mode options:  vv --cli --help   ·   vv --tui --help"]))

(defn version-text [version] (str "vinary-viewer " version))

(defn help-request?
  "Which top-level help the command line asks for, if any: :help | :version | nil — recognising -h/--help and
   -V/--version among the user's arguments (argv from index 2, matching doc-uris' `electron <app> <args…>` shape).
   Lets the main process print and exit before opening a window."
  [argv]
  (let [args (set (drop 2 argv))]
    (cond
      (some args ["-h" "--help"])    :help
      (some args ["-V" "--version"]) :version
      :else                          nil)))

(def ^:private instance-id-flag "--vv-instance-id=")

(defn instance-id
  "The launch's idempotency key, if present: the value of a `--vv-instance-id=<id>` flag anywhere in `argv`
   (scripts/vv-open.mjs stamps every window-opening signal of one `vinary-viewer` invocation with the same
   id), else nil. `doc-uris` drops leading-'-' args, so the flag never reaches the tab list — this reads it
   back out. Whichever signal arrives (own argv when a process becomes primary, or the argv Electron delivers
   to `second-instance`), the id travels with it, so the daemon opens at most one window per invocation."
  [argv]
  (some (fn [a] (when (and (string? a) (str/starts-with? a instance-id-flag))
                  (not-empty (subs a (count instance-id-flag)))))
        argv))

(defn- normalize-doc-arg
  "One command-line document argument → its canonical tab uri, or nil for a blank. http(s), vv-archive://
   and ssh://sftp:// URIs are kept verbatim (NEVER resolve-abs'd — they are virtual addresses, not local
   paths); a file:// URI is reduced to its path; a local path (absolute or relative) is made absolute via
   `resolve-abs` (a 1-argument, cwd-relative resolver — node's `path.resolve`, injected so this stays
   electron- and node-builtin-free)."
  [resolve-abs arg]
  (let [u (uri/normalize arg)]   ; blank→nil, http kept, archive kept, file:// stripped, else as-is
    (cond
      (nil? u)         nil
      (uri/http? u)    u
      (uri/archive? u) u
      (uri/remote? u)  u          ; ssh://sftp:// kept verbatim (never resolve-abs a remote URI)
      :else            (resolve-abs u))))

(defn doc-uris
  "Ordered, normalized tab uris for the non-flag document arguments in `argv` — supporting any number of
   files/URIs named on the command line (`vv a.md b.pdf https://example.com`), each opened in its own tab.

   argv[0]=electron, argv[1]=app path (install.sh runs `electron \"$REPO\" \"$@\"`, and `npm start` =
   `electron .`), so the user's arguments begin at index 2. Leading-'-' flags and blank arguments are
   dropped. `doc-specs` (below) is the full open grammar — types, stdin — built on the same normalization;
   this stays the flags-blind uri view (the lenient fallback for a skewed/invalid second-instance argv)."
  [argv resolve-abs]
  (->> (drop 2 argv)
       (remove #(str/starts-with? % "-"))
       (map (partial normalize-doc-arg resolve-abs))
       (remove nil?)
       vec))

(def ^:private stdin-flag "--vv-stdin=")

(defn- stdin-marker
  "The spilled stdin temp path named by a `--vv-stdin=<path>` flag among `args` — how the direct-spawn
   fallback (scripts/vv-open.mjs last resort) marks WHICH positional argument is the piped document — or
   nil. The temp path itself also appears among the files, at the stdin document's position."
  [args]
  (some (fn [a] (when (and (string? a) (str/starts-with? a stdin-flag))
                  (not-empty (subs a (count stdin-flag)))))
        args))

(defn doc-specs
  "The FULL open grammar for an argv-shaped launch (`:initial`, `:second-instance`, the direct-spawn
   fallback): scan `-t/--type/--file-type` and `-` (vinary.file-type/scan-open-args), normalize each file
   argument (normalize-doc-arg — URIs verbatim, local paths via `resolve-abs`), read a `--vv-stdin=<path>`
   marker back out of argv, and pair types with files (vinary.file-type/resolve-specs; the stdin document
   is file 0 or wherever `-`/the marker placed it). `cwd` is the invoking working directory, carried on
   the stdin spec so a piped diff's side-by-side view resolves against the repo it was generated in.

   → {:specs [{:uri :kind? :language? :delimiter? :stdin? :cwd?} …]} | {:error \"vv: …\"}"
  ([argv resolve-abs] (doc-specs argv resolve-abs nil))
  ([argv resolve-abs cwd]
   (let [args (vec (drop 2 argv))
         scan (file-type/scan-open-args args)]
     (if (:error scan)
       {:error (:error scan)}
       (let [files  (into [] (comp (map (partial normalize-doc-arg resolve-abs)) (remove nil?)) (:files scan))
             marker (some->> (stdin-marker args) (normalize-doc-arg resolve-abs))
             mi     (when marker (.indexOf files marker))
             spill? (and marker (some? mi) (>= mi 0))]
         (file-type/resolve-specs
          {:files      (if spill? (into (subvec files 0 mi) (subvec files (inc mi))) files)
           :types      (:types scan)
           :stdin      (when spill? {:path marker :cwd cwd})
           :dash-index (if spill? mi (:dash-index scan))}))))))

(defn socket-specs
  "The FULL open grammar for a daemon-socket open message (scripts/vv-open.mjs): the client has already
   consumed flags, cwd-resolved local paths, drained+spilled stdin, and inserted the spill path into
   `args` at `stdin-index`. Reconstruct the stdin spec from that index and pair types positionally
   (vinary.file-type/resolve-specs). A legacy client sends neither :types nor :stdin-index — the result
   is then byte-identical to the pre-ADR-0036 behavior (bare specs).

   → {:specs […]} | {:error \"vv: …\"}"
  [{:keys [args types stdin-index cwd]}]
  (let [args       (into [] (comp (map uri/normalize) (remove nil?)) args)
        stdin-path (when (and (number? stdin-index) (<= 0 stdin-index) (< stdin-index (count args)))
                     (nth args stdin-index))
        files      (if stdin-path
                     (into (subvec args 0 stdin-index) (subvec args (inc stdin-index)))
                     args)]
    (file-type/resolve-specs
     {:files      files
      :types      (vec types)
      :stdin      (when stdin-path {:path stdin-path :cwd cwd})
      :dash-index (when stdin-path stdin-index)})))
