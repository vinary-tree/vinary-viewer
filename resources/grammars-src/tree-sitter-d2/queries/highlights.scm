(comment) @comment

(reserved_word) @keyword

[
  (boolean)
  (null)
] @constant.builtin

(number) @number

[
  (string)
  (block_string)
] @string

(identifier) @variable

(arrow_operator) @operator

[
  ":"
  "."
] @punctuation.delimiter

[
  "{"
  "}"
] @punctuation.bracket

(assignment key: (_) @property)
(block key: (_) @property)
(edge source: (_) @label)
(edge target: (_) @label)
