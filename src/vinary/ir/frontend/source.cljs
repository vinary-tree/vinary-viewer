(ns vinary.ir.frontend.source
  "Source-code front-end: convert a web-tree-sitter parse tree into the common document IR — the direct
   analog of lling-llang's SyntaxNode (kind = grammar node type, span = line/byte range, error/missing flags,
   leaf text = the source slice). The read-only CodeMirror source-view still renders the highlighted code;
   this IR is the canonical syntax parse, and `outline` derives a code Contents outline (top-level
   declarations) — navigation source files previously lacked. Pure — walks tree-sitter node objects, no DOM."
  (:require [clojure.string :as str]
            [vinary.ir.node :as node]))

(defn- pos [^js p] {:line (inc (.-row p)) :column (.-column p)})

(defn node->ir
  "A tree-sitter node → an IR node. Named children become IR children; a node with no named children becomes
   a leaf carrying its source slice. `text` is the full source string."
  [^js n text]
  (let [nc   (.-namedChildCount n)
        kids (when (pos? nc) (mapv #(node->ir (.namedChild n %) text) (range nc)))
        m    (cond-> {:span {:start (assoc (pos (.-startPosition n)) :offset (.-startIndex n))
                             :end   (assoc (pos (.-endPosition n)) :offset (.-endIndex n))}}
               (.-isError n)   (assoc :error? true)
               (.-isMissing n) (assoc :missing? true))]
    (if (seq kids)
      (node/node (keyword (.-type n)) kids m)
      (node/leaf (keyword (.-type n)) (subs text (.-startIndex n) (.-endIndex n)) m))))

(defn tree->ir
  "A parse tree + its source `text` → the IR: the grammar root's children under a :document node."
  [^js tree text]
  (let [root (node->ir (.-rootNode tree) text)]
    (node/node :document (node/children root)
               (assoc (node/node-meta root) :root-type (name (node/kind root))))))

;; grammar node-type substrings that mark an outline-worthy top-level declaration (language-agnostic)
(def ^:private decl-pattern
  #"(?i)(function|method|class|struct|impl|module|interface|enum|trait|def|declaration|definition|namespace|type_alias|const|fn_|procedure)")

(defn- decl? [n] (boolean (re-find decl-pattern (name (node/kind n)))))

(defn- decl-name
  "The name of a declaration `n`: the text of its first identifier-like descendant, else its first source
   line (trimmed)."
  [n]
  (or (some (fn [c] (when (re-find #"(?i)(identifier|name)" (name (node/kind c)))
                      (let [t (str/trim (node/text-content c))] (when (seq t) t))))
            (node/preorder n))
      (first (remove str/blank? (str/split-lines (str/trim (node/text-content n)))))))

(defn outline
  "A Contents outline for source code: the top-level declaration nodes → [{:level 1 :text :id :line}], where
   :id is `L<start-line>` for CodeMirror line navigation. Source files previously had no Contents outline."
  [ir]
  (into []
        (comp (filter decl?)
              (keep (fn [n]
                      (let [line (get-in (node/node-meta n) [:span :start :line])
                            nm   (decl-name n)]
                        (when (and line nm)
                          {:level 1 :text (str nm) :id (str "L" line) :line line})))))
        (node/children ir)))
