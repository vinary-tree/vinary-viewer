(comment) @comment

(keyword) @keyword

(rule label: (identifier) @label)
(rule category: (identifier) @type)

(declaration keyword: (keyword) @keyword)

(identifier) @variable

[
  (string)
  (char)
] @string

(number) @number

(operator) @operator
(punctuation) @punctuation.delimiter
