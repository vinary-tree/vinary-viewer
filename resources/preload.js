// vinary-viewer preload — the Mediator IPC seam.
// Runs in an isolated context with Node access and exposes a minimal, safe, JSON-only API to the
// sandboxed renderer via contextBridge. The renderer never touches the filesystem or ipcRenderer
// directly — every cross-process message goes through this seam (no point-to-point sprawl).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('vv', {
  // renderer → main
  open: (path) => ipcRenderer.send('vv:open', path),
  close: (path) => ipcRenderer.send('vv:close', path),
  watchAssets: (docPath, paths) => ipcRenderer.send('vv:watch-assets', { docPath, paths }),
  requestKeymap: () => ipcRenderer.send('vv:keymap-request'),
  requestGrammars: () => ipcRenderer.send('vv:grammars-request'),
  pdfShow: (path, bounds) => ipcRenderer.send('vv:pdf-show', { path, bounds }),
  pdfHide: () => ipcRenderer.send('vv:pdf-hide'),
  pdfBounds: (bounds) => ipcRenderer.send('vv:pdf-bounds', { bounds }),
  httpShow: (url, bounds) => ipcRenderer.send('vv:http-show', { url, bounds }),
  httpHide: () => ipcRenderer.send('vv:http-hide'),
  httpBounds: (bounds) => ipcRenderer.send('vv:http-bounds', { bounds }),
  httpTocGoto: (id) => ipcRenderer.send('vv:http-toc-goto', id),
  openDialog: () => ipcRenderer.send('vv:open-dialog'),
  copyText: (text) => ipcRenderer.send('vv:clipboard-write', text),
  openPath: (p) => ipcRenderer.send('vv:open-path', p),
  openExternal: (url) => ipcRenderer.send('vv:open-external', url),
  requestSettings: () => ipcRenderer.send('vv:settings-request'),
  saveSettings: (edn) => ipcRenderer.send('vv:settings-save', edn),
  saveKeymap: (edn) => ipcRenderer.send('vv:keymap-save', edn),
  requestAppInfo: () => ipcRenderer.send('vv:app-info-request'),
  quit: () => ipcRenderer.send('vv:quit'),
  toggleDevtools: () => ipcRenderer.send('vv:devtools'),
  zoom: (dir) => ipcRenderer.send('vv:zoom', dir),

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
  onAppInfo: (cb) => {
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on('vv:app-info', h);
    return () => ipcRenderer.removeListener('vv:app-info', h);
  },
});
