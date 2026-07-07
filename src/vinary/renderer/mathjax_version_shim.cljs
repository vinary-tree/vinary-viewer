(ns vinary.renderer.mathjax-version-shim
  "Browser-compat shim for MathJax's `js/components/version.js`.

   That module computes its version as
     (typeof PACKAGE_VERSION === 'undefined') ? eval('require')(…package.json…).version : PACKAGE_VERSION
   The eval('require')/__dirname branch is Node-only and cannot bundle for the :browser target — it is the
   single root Node-ism that made `js/mathjax.js` (and its dependents AsyncLoad/Entities) fail to load in the
   renderer. Defining the `PACKAGE_VERSION` global takes the constant branch instead, so the whole MathJax
   `js/` source bundles cleanly. The value is informational only (MathJax uses it for \\require version
   checks, which this app does not enable).

   This namespace must be required BEFORE any `mathjax-full/js/…` module so the global is set at their load
   time — vinary.renderer.math lists it first in its :require.")

(set! (.-PACKAGE_VERSION js/goog.global) "3.2.1")
