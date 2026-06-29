(ns vinary.main.startup
  "Pure, electron-free startup configuration for the main process, so the node :test build can assert
   the app's invariant Chromium command-line switches without loading electron.")

(def chromium-switches
  "Chromium command-line switches the app appends unconditionally at launch (applied by the doseq in
   vinary.main.core/main, BEFORE app `whenReady` — the required timing for compositor switches).

   Two distinct Chromium stages produce stale-pixel artifacts under software compositing on this class
   of host (NVIDIA + Wayland, where app.getGPUFeatureStatus() reports gpu_compositing = disabled_software);
   RASTER ≠ SWAP — rasterization produces tile bitmaps, swap/present pushes the final composited frame to
   the screen — so each stage needs its own switch:

     - \"disable-gpu-sandbox\"       — Linux often sandboxes the GPU process so it cannot open the system
                                       DRI/GBM driver (\"MESA-LOADER: failed to open dri … Permission
                                       denied\"); loosen only the GPU sandbox (the renderer stays sandboxed).

     - \"disable-partial-raster\"    — RASTERIZATION stage (cc::LayerTreeSettings::use_partial_raster):
                                       Chromium reuses previously-rastered TILE CONTENT for sub-regions of a
                                       layer it believes unchanged. Disabling forces every tile to be fully
                                       re-rastered. Kept as defense for a stale-content-within-a-tile mode;
                                       it does NOT by itself fix the present-stage scroll band below.

     - \"ui-disable-partial-swap\"   — PRESENTATION stage (viz::RendererSettings::partial_swap_enabled):
                                       normally only the per-frame DAMAGE RECT of the final composited frame
                                       is drawn and presented to the output surface. Under software
                                       compositing on NVIDIA + Wayland, presenting only the damage rect into
                                       a rotated/recycled buffer can leave a STALE BAND of an earlier scroll
                                       offset in the buffer's undamaged region — the reported top-fifth →
                                       bottom-fifth duplication (multiple bands accumulate; cursor motion
                                       generates more frames and reshuffles them). Setting this switch makes
                                       use_partial_swap_ = false, so DirectRenderer expands root_damage_rect
                                       to the FULL viewport every drawn frame and SoftwareRenderer (a
                                       DirectRenderer subclass) repaints the entire backing buffer — no stale
                                       region survives. The switch was originally created for an NVIDIA
                                       partial-present defect. Source: switch in ui/base/ui_base_switches.cc
                                       (kUIDisablePartialSwap); consumer in
                                       components/viz/host/renderer_settings_creation.cc
                                       (partial_swap_enabled = !HasSwitch(kUIDisablePartialSwap)); full-frame
                                       redraw in components/viz/service/display/direct_renderer.cc;
                                       SoftwareRenderer : public DirectRenderer in software_renderer.h.

   Cost is per-DRAWN-frame damage area (not frame rate): idle = zero (no damage → no draw), and active
   scroll of an image-dense doc is already a heavy-repaint scenario. Harmless on GPU-composited hosts —
   they merely forgo a present-bandwidth optimization (no correctness change). VV_SOFTWARE_GL=1 additionally
   forces full software rendering via app.disableHardwareAcceleration; that one is conditional and handled
   separately in core/main."
  ["disable-gpu-sandbox" "disable-partial-raster" "ui-disable-partial-swap"])
