// vinary-viewer preload — the Mediator IPC seam.
// Runs in an isolated context with Node access and exposes a minimal, safe, JSON-only API to the
// sandboxed renderer via contextBridge. The renderer never touches the filesystem or ipcRenderer
// directly — every cross-process message goes through this seam (no point-to-point sprawl).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('vv', {
  // renderer → main
  open: (path) => ipcRenderer.send('vv:open', path),
  close: (path) => ipcRenderer.send('vv:close', path),
  syncRetainedFiles: (paths) => ipcRenderer.send('vv:retained-files', paths),
  watchAssets: (docPath, paths) => ipcRenderer.send('vv:watch-assets', { docPath, paths }),
  requestKeymap: () => ipcRenderer.send('vv:keymap-request'),
  requestGrammars: () => ipcRenderer.send('vv:grammars-request'),
  // RETIRED native-PDF seam (no main listener after ADR 0013 — PDFs now render in-renderer via pdf.js);
  // kept recoverable. These are harmless no-ops while the native view is retired.
  pdfShow: (path, bounds) => ipcRenderer.send('vv:pdf-show', { path, bounds }),
  pdfHide: () => ipcRenderer.send('vv:pdf-hide'),
  pdfBounds: (bounds) => ipcRenderer.send('vv:pdf-bounds', { bounds }),
  httpShow: (url, bounds, tabId) => ipcRenderer.send('vv:http-show', { url, bounds, tabId }),
  httpHide: () => ipcRenderer.send('vv:http-hide'),
  httpBounds: (bounds) => ipcRenderer.send('vv:http-bounds', { bounds }),
  httpSnapshot: () => ipcRenderer.invoke('vv:http-snapshot'),
  httpTocGoto: (id) => ipcRenderer.send('vv:http-toc-goto', id),
  httpScroll: (kind) => ipcRenderer.send('vv:http-scroll', kind),   // page/edge keys → native web view (when visible)
  openDialog: (defaultPaths) => ipcRenderer.send('vv:open-dialog', defaultPaths),
  copyText: (text) => ipcRenderer.send('vv:clipboard-write', text),
  readText: () => ipcRenderer.invoke('vv:clipboard-read'),   // Promise<string> — the system clipboard, for Paste
  openPath: (p) => ipcRenderer.send('vv:open-path', p),
  openExternal: (url) => ipcRenderer.send('vv:open-external', url),
  requestSettings: () => ipcRenderer.send('vv:settings-request'),
  saveSettings: (edn) => ipcRenderer.send('vv:settings-save', edn),
  saveKeymap: (edn) => ipcRenderer.send('vv:keymap-save', edn),
  requestRecent: () => ipcRenderer.send('vv:recent-request'),
  saveRecent: (edn) => ipcRenderer.send('vv:recent-save', edn),
  completePath: (input) => ipcRenderer.invoke('vv:complete-path', input),
  contentPage: (request) => ipcRenderer.invoke('vv:content-page', request),
  loadDiffSources: (req) => ipcRenderer.invoke('vv:load-diff-sources', req),
  // bounded-memory document streaming (session pull-cursor)
  streamOpen: (req) => ipcRenderer.invoke('vv:stream-open', req),
  streamPull: (req) => ipcRenderer.invoke('vv:stream-pull', req),
  streamClose: (req) => ipcRenderer.invoke('vv:stream-close', req),
  // extensions + ad-blocking (renderer → main)
  requestExtConfig: () => ipcRenderer.send('vv:ext-config-request'),
  saveExtConfig: (edn) => ipcRenderer.send('vv:ext-config-save', edn),
  extState: () => ipcRenderer.send('vv:ext-state-request'),
  extInstall: (idOrUrl) => ipcRenderer.send('vv:ext-install', idOrUrl),
  extRemove: (id) => ipcRenderer.send('vv:ext-remove', id),
  extSetEnabled: (id, on) => ipcRenderer.send('vv:ext-set-enabled', { id, on }),
  extCheckUpdates: () => ipcRenderer.send('vv:ext-check-updates'),
  extActionClicked: (id, popup, bounds) => ipcRenderer.send('vv:ext-action-clicked', { id, popup, bounds }),
  extPopupClose: () => ipcRenderer.send('vv:ext-popup-close'),
  adblockSetEnabled: (on) => ipcRenderer.send('vv:adblock-set-enabled', on),
  adblockSetLists: (kw) => ipcRenderer.send('vv:adblock-set-lists', kw),
  adblockRefresh: () => ipcRenderer.send('vv:adblock-refresh'),
  // native password-manager bridge (renderer → main)
  passwordState: () => ipcRenderer.send('vv:password-state-request'),
  passwordSearch: (url) => ipcRenderer.send('vv:password-search', url),
  passwordFill: (item) => ipcRenderer.send('vv:password-fill', item),
  passwordSave: (payload) => ipcRenderer.send('vv:password-save', payload),
  passwordDismissSave: (token) => ipcRenderer.send('vv:password-dismiss-save', token),
  // SSH/SFTP remote files (renderer → main). vv:ssh-prompt-reply carries a user-typed secret and NOTHING else
  // does — it is one-shot and never persisted. The rest are non-secret metadata / EDN text.
  sshPromptReply: (promptId, secret) => ipcRenderer.send('vv:ssh-prompt-reply', { promptId, secret }),
  sshCloseConnection: (connKey) => ipcRenderer.send('vv:ssh-close-connection', connKey),
  requestConnections: () => ipcRenderer.send('vv:connections-request'),
  saveConnections: (edn) => ipcRenderer.send('vv:connections-save', edn),
  loadRemoteAsset: (req) => ipcRenderer.invoke('vv:load-remote-asset', req),   // fetch a remote asset's bytes → data URL
  requestAppInfo: () => ipcRenderer.send('vv:app-info-request'),
  quit: () => ipcRenderer.send('vv:quit'),
  toggleDevtools: () => ipcRenderer.send('vv:devtools'),
  zoom: (dir) => ipcRenderer.send('vv:zoom', dir),
  zoomSet: (f) => ipcRenderer.send('vv:zoom-set', f),
  requestZoom: () => ipcRenderer.send('vv:zoom-request'),   // boot pull: seed the zoom bar with the restored factor
  httpZoom: (dir) => ipcRenderer.send('vv:http-zoom', dir),
  httpZoomSet: (f) => ipcRenderer.send('vv:http-zoom-set', f),

  // main → renderer (each returns an unsubscribe fn)
  onContent: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:content', h);
    return () => ipcRenderer.removeListener('vv:content', h);
  },
  onError: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:error', h);
    return () => ipcRenderer.removeListener('vv:error', h);
  },
  onTree: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:tree', h);
    return () => ipcRenderer.removeListener('vv:tree', h);
  },
  onKeymap: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:keymap', h);
    return () => ipcRenderer.removeListener('vv:keymap', h);
  },
  onGrammars: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:grammars', h);
    return () => ipcRenderer.removeListener('vv:grammars', h);
  },
  onHttpNavigated: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:http-navigated', h);
    return () => ipcRenderer.removeListener('vv:http-navigated', h);
  },
  // a PDF link clicked in the web view → open it in the app's pdf.js viewer (main intercepted the navigation)
  onHttpOpenPdf: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:http-open-pdf', h);
    return () => ipcRenderer.removeListener('vv:http-open-pdf', h);
  },
  onHttpSnapshotReady: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:http-snapshot-ready', h);
    return () => ipcRenderer.removeListener('vv:http-snapshot-ready', h);
  },
  onWebToc: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:web-toc', h);
    return () => ipcRenderer.removeListener('vv:web-toc', h);
  },
  onWebActiveHeading: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:web-active-heading', h);
    return () => ipcRenderer.removeListener('vv:web-active-heading', h);
  },
  onHistoryNav: (cb) => {
    const h = (_e, dir) => cb(dir);
    ipcRenderer.on('vv:history-nav', h);
    return () => ipcRenderer.removeListener('vv:history-nav', h);
  },
  // app-global Ctrl/Cmd chords forwarded from the (separate-context) web view → replayed through the resolver
  onWebKey: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:web-key', h);
    return () => ipcRenderer.removeListener('vv:web-key', h);
  },
  onOpenFiles: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:open-files', h);
    return () => ipcRenderer.removeListener('vv:open-files', h);
  },
  onSettings: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:settings', h);
    return () => ipcRenderer.removeListener('vv:settings', h);
  },
  onRecent: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:recent', h);
    return () => ipcRenderer.removeListener('vv:recent', h);
  },
  onExtConfig: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:ext-config', h); return () => ipcRenderer.removeListener('vv:ext-config', h); },
  onExtState: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:ext-state', h); return () => ipcRenderer.removeListener('vv:ext-state', h); },
  onExtInstallResult: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:ext-install-result', h); return () => ipcRenderer.removeListener('vv:ext-install-result', h); },
  onExtUpdateResult: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:ext-update-result', h); return () => ipcRenderer.removeListener('vv:ext-update-result', h); },
  onAdblockStatus: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:adblock-status', h); return () => ipcRenderer.removeListener('vv:adblock-status', h); },
  onPasswordState: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:password-state', h); return () => ipcRenderer.removeListener('vv:password-state', h); },
  onPasswordItems: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:password-items', h); return () => ipcRenderer.removeListener('vv:password-items', h); },
  onPasswordSavePrompt: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:password-save-prompt', h); return () => ipcRenderer.removeListener('vv:password-save-prompt', h); },
  onPasswordResult: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:password-result', h); return () => ipcRenderer.removeListener('vv:password-result', h); },
  // SSH/SFTP (main → renderer). vv:ssh-prompt is a NON-secret request (the reply carries the secret); the rest
  // are non-secret status/errors and the connections EDN text.
  onSshPrompt: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:ssh-prompt', h); return () => ipcRenderer.removeListener('vv:ssh-prompt', h); },
  onSshError: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:ssh-error', h); return () => ipcRenderer.removeListener('vv:ssh-error', h); },
  onSshStatus: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:ssh-status', h); return () => ipcRenderer.removeListener('vv:ssh-status', h); },
  onConnections: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:connections', h); return () => ipcRenderer.removeListener('vv:connections', h); },
  onZoomChanged: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('vv:zoom-changed', h); return () => ipcRenderer.removeListener('vv:zoom-changed', h); },
  onAppInfo: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:app-info', h);
    return () => ipcRenderer.removeListener('vv:app-info', h);
  },
});
