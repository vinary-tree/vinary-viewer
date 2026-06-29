(ns vinary.main.startup
  "Pure, electron-free startup configuration for the main process, so the node :test build can assert
   the app's invariant Chromium command-line switches without loading electron.")

(def chromium-switches
  "Chromium command-line switches the app appends unconditionally at launch (applied by the doseq in
   vinary.main.core/main, BEFORE app `whenReady` — the required timing for compositor switches):

     - \"disable-gpu-sandbox\"      — Linux often sandboxes the GPU process so it cannot open the system
                                      DRI/GBM driver (\"MESA-LOADER: failed to open dri … Permission
                                      denied\"); loosen only the GPU sandbox (the renderer stays sandboxed).

     - \"disable-partial-raster\"   — RASTERIZATION stage (cc::LayerTreeSettings::use_partial_raster): stop
                                      reusing previously-rastered TILE CONTENT for sub-regions a layer
                                      believes unchanged. Defensive only.

     - \"ui-disable-partial-swap\"  — PRESENTATION stage (viz::RendererSettings::partial_swap_enabled):
                                      present the FULL viewport every frame instead of only the per-frame
                                      damage rect. Defensive only.

   NOTE — neither partial-raster nor partial-swap fixes the NVIDIA + Wayland scroll-band (a copy of the
   window's top ~1/5 painted into the bottom ~1/5 while scrolling). Screen-captured A/B testing this
   session showed the band PERSISTS with the GPU process active even with both switches set and with any
   ANGLE/GL or Vulkan backend forced; it vanishes ONLY when the GPU process is removed. The actual fix is
   `disable-hardware-acceleration?` below. These two switches are kept (low cost; may help other
   software-compositor modes) but are NOT the band fix — don't let their names mislead the next reader."
  ["disable-gpu-sandbox" "disable-partial-raster" "ui-disable-partial-swap"])

(defn disable-hardware-acceleration?
  "Whether to remove Chromium's GPU process at startup (app.disableHardwareAcceleration) — the ACTUAL fix
   for the NVIDIA + Wayland scroll-band.

   The band (a copy of the window's top ~1/5 painted into the bottom ~1/5 while scrolling; multiple bands
   accumulate; cursor motion worsens it) comes from the GPU process's Wayland window-surface PRESENTATION
   path. Screen-captured + visually-verified A/B this session: with the GPU process ON the band is present
   regardless of presentation backend (default, --use-angle=gl, --use-angle=vulkan all band); removing the
   GPU process ELIMINATES it. Software compositing is already in force here (gpu_compositing =
   disabled_software — Chromium blocklists GPU compositing for this NVIDIA + Wayland combo), so the GPU
   process provides no compositing/raster benefit — only the broken presentation — making removal a
   low-cost default on Wayland. Kept overridable.

   `env` is a map of the relevant environment-variable values (strings or nil):
     :VV_GPU           — set to force the GPU process back ON (opt out of this workaround) anywhere.
     :VV_SOFTWARE_GL   — set to force the GPU process OFF (full software) anywhere.
     :XDG_SESSION_TYPE / :WAYLAND_DISPLAY — detect a Wayland session (the affected presentation path)."
  [{:keys [VV_GPU VV_SOFTWARE_GL XDG_SESSION_TYPE WAYLAND_DISPLAY]}]
  (cond
    VV_GPU         false
    VV_SOFTWARE_GL true
    :else          (boolean (or (= "wayland" XDG_SESSION_TYPE)
                                (and WAYLAND_DISPLAY (not= "" WAYLAND_DISPLAY))))))
