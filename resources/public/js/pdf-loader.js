'use strict';

// Dynamic-import the ESM-only pdf.js legacy build (pdfjs-dist v5 ships no CommonJS build) from a real CommonJS
// module context — where Node's import() has a proper module-resolution callback and resolves against node_modules.
// vinary.terminal.pdf requires this because shadow-cljs's own :node-script output cannot do the dynamic import
// itself: a bare `(js/import …)` is munged to an undefined `import$`, and Function-/eval-constructed import() calls
// run without a resolution callback ("A dynamic import callback was not specified"). Returns Promise<pdfjs module>.
module.exports = () => import('pdfjs-dist/legacy/build/pdf.mjs');
