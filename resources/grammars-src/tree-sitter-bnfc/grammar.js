/**
 * A permissive BNFC/LBNF grammar for source highlighting in vinary-viewer.
 *
 * It recognizes the common Label. Category ::= ... ; rule shape and the
 * standard LBNF declaration macros. The goal is resilient highlighting, not
 * replacement of BNFC's own parser or semantic checks.
 */
module.exports = grammar({
  name: 'bnfc',

  extras: $ => [/[ \t\r\n]/],

  word: $ => $.identifier,

  rules: {
    source_file: $ => repeat(choice($.comment, $.rule, $.declaration)),

    rule: $ => seq(
      optional(seq(field('label', $.identifier), '.')),
      field('category', $.identifier),
      '::=',
      repeat($._item),
      ';'
    ),

    declaration: $ => seq(
      field('keyword', $.keyword),
      repeat($._item),
      ';'
    ),

    _item: $ => choice(
      $.comment,
      $.keyword,
      $.identifier,
      $.string,
      $.char,
      $.number,
      $.operator,
      $.punctuation
    ),

    keyword: _ => token(prec(1, choice(
      'comment',
      'coercions',
      'define',
      'entrypoints',
      'internal',
      'layout',
      'nonempty',
      'position',
      'rules',
      'separator',
      'terminator',
      'token',
      'toplevel',
      'stop'
    ))),

    identifier: _ => token(/[A-Za-z_][A-Za-z0-9_']*/),

    string: _ => token(seq('"', repeat(choice(/[^"\\\n]/, /\\./)), '"')),
    char: _ => token(seq("'", repeat(choice(/[^'\\\n]/, /\\./)), "'")),
    number: _ => token(/[0-9]+/),

    operator: _ => token(choice('::=', '|', '*', '+', '?')),
    punctuation: _ => token(choice('.', ';', ',', '(', ')', '[', ']', '{', '}')),

    comment: _ => token(choice(
      seq('--', /.*/),
      seq('//', /.*/),
      seq('/*', repeat(choice(/[^*]/, /\*[^/]/)), '*/')
    ))
  }
});
