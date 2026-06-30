(ns vinary.main.startup
  "Pure, electron-free startup configuration for the main process, so the node :test build can assert
   the app's invariant Chromium switches, the GPU-mode predicates, and command-line argument parsing
   without loading electron."
  (:require [clojure.string :as str]
            [vinary.app.uri :as uri]))

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

(defn doc-uris
  "Ordered, normalized tab uris for the non-flag document arguments in `argv` — supporting any number of
   files/URIs named on the command line (`vv a.md b.pdf https://example.com`), each opened in its own tab.

   argv[0]=electron, argv[1]=app path (install.sh runs `electron \"$REPO\" \"$@\"`, and `npm start` =
   `electron .`), so the user's arguments begin at index 2. http(s) and vv-archive:// URLs are kept
   verbatim; a file:// URI is reduced to its path; a local path (absolute or relative) is made absolute
   via `resolve-abs` (a 1-argument, cwd-relative resolver — node's `path.resolve`, injected so this stays
   electron- and node-builtin-free). Leading-'-' flags and blank arguments are dropped."
  [argv resolve-abs]
  (->> (drop 2 argv)
       (remove #(str/starts-with? % "-"))
       (map (fn [arg]
              (let [u (uri/normalize arg)]   ; blank→nil, http kept, archive kept, file:// stripped, else as-is
                (cond
                  (nil? u)         nil
                  (uri/http? u)    u
                  (uri/archive? u) u
                  :else            (resolve-abs u)))))
       (remove nil?)
       vec))
