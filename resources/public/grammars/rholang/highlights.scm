; Comments
(line_comment) @comment
(block_comment) @comment

; Keywords
[
  "contract"
  "for"
  "in"
  "if"
  "else"
  "match"
  "select"
  "new"
  "let"
] @keyword

; Bundle keywords
[
  (bundle_write)
  (bundle_read)
  (bundle_equiv)
  (bundle_read_write)
] @keyword

; Literals
(bool_literal) @boolean
(long_literal) @number
(string_literal) @string
(uri_literal) @string
(nil) @constant.builtin
(simple_type) @type

; Word-based operators (logical/keyword-like)
[
  "or"
  "and"
  "matches"
  "not"
] @keyword.operator

; Symbolic operators (including added '=' and '&' for declarations and concurrent declarations)
[
  "|"
  "!?"
  (send_single)
  (send_multiple)
  "=="
  "!="
  "<"
  "<="
  ">"
  ">="
  "+"
  "++"
  "-"
  "--"
  "*"
  "/"
  "%"
  "%%"
  "~"
  "\\/"
  "/\\"
  "<-"
  "<<-"
  "?!"
  "=>"
  ":"
  "="
  "&"
] @operator

; Additional operators in context
(quote "@" @operator)
(var_ref_kind) @operator

; Punctuation (split for brackets and delimiters)
[
  "("
  ")"
  "{"
  "}"
  "["
  "]"
] @punctuation.bracket

[
  ","
  ";"
  "."
  "..."
] @punctuation.delimiter

; Variables and Names
(var) @variable
(wildcard) @variable
(var_ref) @variable

; Channels and Quotes
(quote) @function
(eval) @function

; Collections (updated capture to @type for alignment with style-map)
(list) @type
(tuple) @type
(set) @type
(map) @type
(set "Set" @type)
(key_value_pair key: (_) @variable value: (_) @variable)

; Methods
(method name: (_) @function)

; Case patterns
(case pattern: (_) @variable)

; Function-like constructs
(contract name: (_) @function)
