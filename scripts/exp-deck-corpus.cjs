'use strict';
// Slide-deck detection on REAL engine output — the controlled positive corpus of docs/scientific/12 (ADR-0045).
//
// QUESTION. Does the deck detector (vinary.slides.deck, run by `vv --cli --deck-info` through the GUI's own evidence,
// page measure and classifier) reach the verdict its rules prescribe on PDFs that pdflatex, lualatex and xelatex
// actually produce — across Beamer's paper sizes, overlays, handout mode, second-screen notes, a Creator override and
// full-screen page mode — and stay silent on the non-decks next to them?
//
// METHOD. Each VARIANT below is a LaTeX source together with the verdict expected of it: {deck, tier, source, frames,
// secondScreen}. Those expectations are written down here, before any run, from the classifier's documented rules
// (vinary.slides.deck/classify, deck) and Beamer's sources. Frames depend on the engine: Beamer labels each page
// with its frame number, and the detector groups a frame's overlay pages by that label. pdflatex and lualatex write
// /PageLabels in the run itself. hyperref's xdvipdfmx driver (hxetex.def) writes them from the labels the PREVIOUS
// run left in the .aux file, so ONE xelatex pass has none (its log says "Rerun to get /PageLabels entry") and gets one
// frame per page, and a SECOND pass has them. The corpus first ran xelatex once (2026-10-16), and its text then said
// that xdvipdfmx writes no labels at all. The engine `xelatex2` (two passes) was added on 2026-10-17, with its
// expectation written here before its first run: the same verdict and frames as pdflatex (`labelled`). Every variant
// is compiled with every engine in a temporary directory. All the PDFs are classified in one `vv-cli --deck-info`
// call, and every field is compared.
//
//   npm run compile:cli:test && node scripts/exp-deck-corpus.cjs --json=out.json | tee out.txt
//
// Needs TeX Live with Beamer and beamerposter.

const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const CLI = path.join(ROOT, 'dist', 'cli', 'vv-cli.js');
// id → the program and how many passes it gets; `xelatex2` is xelatex run twice in the same directory (above)
const ENGINES = [{ id: 'pdflatex', cmd: 'pdflatex', passes: 1 }, { id: 'lualatex', cmd: 'lualatex', passes: 1 },
                 { id: 'xelatex', cmd: 'xelatex', passes: 1 }, { id: 'xelatex2', cmd: 'xelatex', passes: 2 }];

// 5 content frames of 3 overlays each, after a title frame: 16 pages in 6 frames
const FRAMES = Array.from({ length: 5 }, (_, i) => `\\begin{frame}{Frame ${i + 2}}
  \\begin{itemize}
    \\item<1-> First point of frame ${i + 2}
    \\item<2-> Second point, on the second overlay
    \\item<3-> Third point, on the third
  \\end{itemize}
\\end{frame}`).join('\n');
const beamer = (opts, preamble = '') => `\\documentclass[${opts}]{beamer}
${preamble}
\\title{Detection corpus}
\\begin{document}
\\begin{frame}\\titlepage\\end{frame}
${FRAMES}
\\end{document}
`;
const OVERRIDE = '\\hypersetup{pdfcreator={Custom slide tool}}';
// per engine: frames = 6 where Beamer's page labels group the overlays, 16 (one per page) where the deck has none —
// only a single xelatex pass (above)
const labelled = (engine) => engine !== 'xelatex';

const VARIANTS = [
  ...[['aspect43', 'aspectratio=43'], ['aspect169', 'aspectratio=169'], ['aspect1610', 'aspectratio=1610'],
      ['aspect141', 'aspectratio=141'], ['aspect32', 'aspectratio=32']].map(([name, opt]) => ({
    name, why: `Beamer, ${opt}, overlays`, source: beamer(opt),
    expect: (e) => ({ deck: true, tier: 'definite', source: 'beamer', frames: labelled(e) ? 6 : 16, secondScreen: null }),
  })),
  { name: 'handout', why: 'Beamer handout: one page per frame', source: beamer('handout'),
    expect: () => ({ deck: true, tier: 'definite', source: 'beamer', frames: 6, secondScreen: null }) },
  { name: 'notes-right', why: 'show notes on second screen=right: pages doubled horizontally',
    source: beamer('', '\\setbeameroption{show notes on second screen=right}'),
    expect: () => ({ deck: true, tier: 'definite', source: 'beamer', frames: 16, secondScreen: 'horizontal' }) },
  { name: 'notes-bottom', why: 'show notes on second screen=bottom: pages doubled vertically',
    source: beamer('', '\\setbeameroption{show notes on second screen=bottom}'),
    expect: () => ({ deck: true, tier: 'definite', source: 'beamer', frames: 16, secondScreen: 'vertical' }) },
  { name: 'creator-override', why: '\\hypersetup{pdfcreator=…}: no Beamer tag, Beamer paper, a TeX producer',
    source: beamer('aspectratio=169', OVERRIDE),
    expect: (e) => ({ deck: true, tier: 'probable', source: 'beamer-geometry', frames: labelled(e) ? 6 : 16, secondScreen: null }) },
  { name: 'fullscreen-override', why: 'pdfpagemode=FullScreen with the Creator overridden: the page-mode rule',
    source: beamer('', `${OVERRIDE}\n\\hypersetup{pdfpagemode=FullScreen}`),
    expect: () => ({ deck: true, tier: 'definite', source: 'fullscreen-mode', frames: 16, secondScreen: null }) },
  { name: 'fullscreen-beamer', why: 'pdfpagemode=FullScreen with Beamer\'s Creator: the first rule wins',
    source: beamer('', '\\hypersetup{pdfpagemode=FullScreen}'),
    expect: (e) => ({ deck: true, tier: 'definite', source: 'beamer', frames: labelled(e) ? 6 : 16, secondScreen: null }) },
  { name: 'poster', why: 'a one-page beamerposter (A0): Beamer\'s Creator, but not a deck',
    source: `\\documentclass{beamer}
\\usepackage[orientation=portrait,size=a0,scale=1.4]{beamerposter}
\\begin{document}
\\begin{frame}{A poster}
\\begin{block}{One block}Text.\\end{block}
\\end{frame}
\\end{document}
`,
    expect: () => ({ deck: false, tier: null, source: null, frames: 1, secondScreen: null }) },
  { name: 'article-landscape', why: 'a landscape article: landscape alone is never a deck',
    source: `\\documentclass{article}
\\usepackage[landscape]{geometry}
\\begin{document}
Page one.\\newpage Page two.\\newpage Page three.
\\end{document}
`,
    expect: () => ({ deck: false, tier: null, source: null, frames: 3, secondScreen: null }) },
  { name: 'article-on-beamer-paper', why: 'an article on 16:9 Beamer paper: the documented false positive of :beamer-geometry',
    source: `\\documentclass{article}
\\usepackage[papersize={16cm,9cm},margin=1cm]{geometry}
\\begin{document}
Page one.\\newpage Page two.
\\end{document}
`,
    expect: () => ({ deck: true, tier: 'probable', source: 'beamer-geometry', frames: 2, secondScreen: null }) },
];

const FIELDS = ['deck', 'tier', 'source', 'frames', 'secondScreen'];

function compile(engine, dir, name, source) {
  const tex = path.join(dir, `${name}-${engine.id}.tex`);
  fs.writeFileSync(tex, source);
  let status = 0;
  for (let pass = 0; pass < engine.passes && status === 0; pass++) {
    status = cp.spawnSync(engine.cmd, ['-interaction=nonstopmode', '-halt-on-error', path.basename(tex)],
      { cwd: dir, stdio: 'ignore', timeout: 300000 }).status;
  }
  const pdf = tex.replace(/\.tex$/, '.pdf');
  return status === 0 && fs.existsSync(pdf) ? pdf : null;
}

function main() {
  const json = (process.argv.find((a) => a.startsWith('--json=')) || '').slice(7) || null;
  if (!fs.existsSync(CLI)) throw new Error('build the CLI first: npm run compile:cli:test');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-deck-corpus-'));
  const engines = {};
  for (const e of ENGINES) {
    const version = cp.execFileSync(e.cmd, ['--version'], { encoding: 'utf8' }).split('\n')[0];
    engines[e.id] = e.passes === 1 ? version : `${version} (${e.passes} passes)`;
  }
  console.log(`# exp-deck-corpus ${new Date().toISOString()}  node ${process.version}`);
  for (const e of ENGINES) console.log(`# ${e.id}: ${engines[e.id]}`);
  const cases = [];
  for (const v of VARIANTS) {
    for (const e of ENGINES) {
      cases.push({ variant: v.name, engine: e.id, why: v.why, pdf: compile(e, dir, v.name, v.source), expect: v.expect(e.id) });
    }
  }
  const built = cases.filter((c) => c.pdf);
  const out = cp.spawnSync('node', [CLI, '--deck-info', ...built.map((c) => c.pdf)],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 16 * 1024 * 1024 });
  const lines = out.stdout.trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
  const byPath = new Map(lines.map((r) => [r.path, r]));
  let fieldMismatches = 0;
  console.log('\nvariant                  engine    expected                                   got                                        match');
  for (const c of cases) {
    const got = c.pdf ? byPath.get(c.pdf) : null;
    c.got = got ? Object.fromEntries(FIELDS.map((f) => [f, got[f]]).concat([['pages', got.pages], ['labels', got.labels]])) : null;
    c.mismatched = got ? FIELDS.filter((f) => got[f] !== c.expect[f]) : FIELDS.slice();
    fieldMismatches += c.mismatched.length;
    const fmt = (x) => (x ? `${x.deck ? 'deck' : 'no'} ${x.tier || '—'}/${x.source || '—'} f=${x.frames}${x.secondScreen ? ' ' + x.secondScreen : ''}` : 'COMPILE FAILED');
    console.log(`${c.variant.padEnd(24)} ${c.engine.padEnd(9)} ${fmt(c.expect).padEnd(42)} ${fmt(c.got).padEnd(42)} ${c.mismatched.length ? 'MISMATCH: ' + c.mismatched.join(',') : 'yes'}`);
  }
  const verdictOk = cases.filter((c) => c.got && ['deck', 'tier', 'source'].every((f) => c.got[f] === c.expect[f])).length;
  console.log(`\n${cases.length} cases (${cases.length - built.length} failed to compile): verdict (deck/tier/source) as expected in ${verdictOk}; ` +
              `field mismatches ${fieldMismatches}`);
  if (json) {
    fs.writeFileSync(json, JSON.stringify({ date: new Date().toISOString(), engines,
      cases: cases.map(({ pdf, ...c }) => ({ ...c, pdf: pdf ? path.basename(pdf) : null })) }, null, 1));
    console.log(`# wrote ${path.relative(ROOT, path.resolve(json))}`);
  }
  fs.rmSync(dir, { recursive: true, force: true });
}

main();
