/**
 * A permissive D2 grammar for source highlighting in vinary-viewer.
 *
 * It intentionally recognizes D2's common line-oriented surface syntax rather
 * than enforcing every semantic constraint. The viewer needs resilient
 * highlighting for partially-written diagrams more than compiler-grade errors.
 */
module.exports = grammar({
  name: 'd2',

  extras: $ => [/[ \t\r]/],

  word: $ => $.identifier,

  conflicts: $ => [
    [$.edge, $.assignment]
  ],

  rules: {
    source_file: $ => repeat(choice($._statement, $._newline)),

    _statement: $ => choice(
      $.comment,
      $.block,
      $.edge,
      $.assignment
    ),

    block: $ => seq(
      field('key', $._path),
      ':',
      '{',
      repeat(choice($._statement, $._newline)),
      '}'
    ),

    assignment: $ => prec.right(1, seq(
      field('key', $._path),
      ':',
      optional($._value)
    )),

    edge: $ => prec.right(2, seq(
      field('source', $._path),
      field('operator', $.arrow_operator),
      field('target', $._path),
      optional(seq(':', optional($._value)))
    )),

    _path: $ => seq(
      $._path_segment,
      repeat(seq('.', $._path_segment))
    ),

    _path_segment: $ => choice(
      $.reserved_word,
      $.identifier,
      $.string,
      $.number
    ),

    _value: $ => repeat1(choice(
      $.string,
      $.block_string,
      $.number,
      $.boolean,
      $.null,
      $.reserved_word,
      $.identifier,
      $.unquoted_text
    )),

    comment: _ => token(seq('#', /.*/)),

    string: _ => choice(
      token(seq('"', repeat(choice(/[^"\\\n]/, /\\./)), '"')),
      token(seq("'", repeat(choice(/[^'\\\n]/, /\\./)), "'"))
    ),

    block_string: _ => token(seq('|', optional(/[A-Za-z_][A-Za-z0-9_-]*/), /[^|]*/, '|')),

    number: _ => token(/[+-]?(?:\d+\.\d+|\d+)(?:[eE][+-]?\d+)?/),
    boolean: _ => choice('true', 'false'),
    null: _ => 'null',

    reserved_word: _ => token(prec(1, choice(
      'animated',
      'bold',
      'border-radius',
      'class',
      'classes',
      'constraint',
      'd2-config',
      'direction',
      'fill',
      'filled',
      'font',
      'font-color',
      'font-size',
      'height',
      'icon',
      'italic',
      'label',
      'layout-engine',
      'link',
      'near',
      'opacity',
      'shape',
      'shadow',
      'source-arrowhead',
      'stroke',
      'stroke-dash',
      'stroke-width',
      'style',
      'target-arrowhead',
      'tooltip',
      'vars',
      'width'
    ))),

    identifier: _ => token(/[A-Za-z_][A-Za-z0-9_-]*/),

    arrow_operator: _ => token(prec(2, choice(
      '<-->',
      '<->',
      '<--',
      '-->',
      '->',
      '<-',
      '--',
      '=>'
    ))),

    unquoted_text: _ => token(/[^\s#{}:;]+/),
    _newline: _ => /\n+/
  }
});
