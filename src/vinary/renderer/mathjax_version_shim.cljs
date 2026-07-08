(ns vinary.renderer.mathjax-version-shim
  "OBSOLETE after the MathJax 4 migration — retained (not deleted) as a record of why it existed.

   Under MathJax 3 (`mathjax-full`), `js/components/version.js` computed its version as
     (typeof PACKAGE_VERSION === 'undefined') ? eval('require')(…package.json…).version : PACKAGE_VERSION
   whose eval('require')/__dirname branch is Node-only and could not bundle for the :browser target, so
   vinary.renderer.math loaded this shim FIRST to define the `PACKAGE_VERSION` global (constant branch).

   MathJax 4 (`@mathjax/src`) restructured versioning: building the renderer from its `cjs/` source no longer
   pulls in a version.js with that eval('require') branch, so the :browser bundle compiles WITHOUT any shim
   (verified: `shadow-cljs compile renderer`, 0 warnings). vinary.renderer.math no longer requires this ns; the
   `set!` below is commented out so it has no effect."
  ;; (set! (.-PACKAGE_VERSION js/goog.global) "3.2.1")   ; MathJax-3-only shim; MathJax 4 needs none — see docstring
  )
