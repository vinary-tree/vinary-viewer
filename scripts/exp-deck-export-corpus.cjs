'use strict';
// Slide-deck detection on REAL slide-tool exports: LibreOffice Impress at each of its slide-size presets
// (docs/scientific/12, ADR-0045). Pre-registered in docs/scientific/data/12/export-canvas-prediction.txt.
//
// QUESTION. Which page canvas does Impress write for each slide-size preset, and does the detector
// (vinary.slides.deck, run by `vv --cli --deck-info`) call each export a deck?
//
// METHOD. One flat-XML presentation (.fodp) per preset: three slides, each with a title, and the master page laid
// out at the preset's size. Impress ignores a page layout it does not recognize as its own serialization: the first
// version of this instrument wrote a minimal .fodp, and all seven exports came out at Impress's default
// (793.7 × 446.5, 28 × 15.75 cm) whatever they declared. So a seed presentation is round-tripped through soffice
// once, and each preset's source is LibreOffice's OWN output with the size of the master page's layout replaced.
// `soffice --headless --convert-to pdf` exports them all under a throwaway user profile (-env:UserInstallation), so
// the developer's own LibreOffice profile is never read or written. The PDFs are
// classified in one `vv-cli --deck-info` call. Each export's size is compared with P1 (cm × 72 / 2.54, within the
// detector's 0.5 bp tolerance), and its verdict with P2 (`--expect=before`: the classifier before export-canvases
// learned Impress's sizes) or P3 (`--expect=after`).
//
//   npm run compile:cli:test && node scripts/exp-deck-export-corpus.cjs --expect=after --json=out.json | tee out.txt
//
// Needs LibreOffice (soffice).

const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const CLI = path.join(ROOT, 'dist', 'cli', 'vv-cli.js');
const BP_PER_CM = 72 / 2.54;
const TOLERANCE = 0.5;                                   // vinary.slides.deck/size-tolerance

// [preset, width cm, height cm, a deck before the change?]
const PRESETS = [
  ['Screen 4:3', 28, 21, false],
  ['Screen 16:9', 28, 15.75, false],
  ['Screen 16:10', 28, 17.5, false],
  ['Widescreen', 33.867, 19.05, true],
  ['On-screen Show (4:3)', 25.4, 19.05, true],
  ['On-screen Show (16:9)', 25.4, 14.288, true],
  ['On-screen Show (16:10)', 25.4, 15.875, false],
];

const fodp = (wCm, hCm) => `<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
 xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
 xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
 xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
 xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
 office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.presentation">
 <office:automatic-styles>
  <style:page-layout style:name="PM1">
   <style:page-layout-properties fo:margin-top="0cm" fo:margin-bottom="0cm" fo:margin-left="0cm" fo:margin-right="0cm"
     fo:page-width="${wCm}cm" fo:page-height="${hCm}cm" style:print-orientation="landscape"/>
  </style:page-layout>
 </office:automatic-styles>
 <office:master-styles>
  <style:master-page style:name="Default" style:page-layout-name="PM1"/>
 </office:master-styles>
 <office:body>
  <office:presentation>
${[1, 2, 3].map((i) => `   <draw:page draw:name="page${i}" draw:master-page-name="Default">
    <draw:frame svg:x="1cm" svg:y="1cm" svg:width="${(wCm - 2).toFixed(3)}cm" svg:height="3cm">
     <draw:text-box><text:p>Slide ${i}</text:p></draw:text-box>
    </draw:frame>
   </draw:page>`).join('\n')}
  </office:presentation>
 </office:body>
</office:document>
`;

function main() {
  const opt = (k) => (process.argv.find((a) => a.startsWith(`--${k}=`)) || '').slice(k.length + 3) || null;
  const expect = opt('expect') || 'after';
  if (!['before', 'after'].includes(expect)) throw new Error('--expect=before|after');
  const json = opt('json');
  if (!fs.existsSync(CLI)) throw new Error('build the CLI first: npm run compile:cli:test');
  const version = cp.execFileSync('soffice', ['--version'], { encoding: 'utf8' }).trim();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-deck-export-'));
  const profile = path.join(dir, 'profile');
  const soffice = (args) => cp.execFileSync('soffice', [`-env:UserInstallation=file://${profile}`, '--headless', ...args],
    { stdio: 'ignore', timeout: 300000 });
  const seed = path.join(dir, 'seed.fodp');
  fs.writeFileSync(seed, fodp(28, 15.75));
  const baseDir = path.join(dir, 'base');
  fs.mkdirSync(baseDir);
  soffice(['--convert-to', 'fodp', '--outdir', baseDir, seed]);
  const base = fs.readFileSync(path.join(baseDir, 'seed.fodp'), 'utf8');
  const layoutName = (base.match(/<style:master-page style:name="Default" style:page-layout-name="([^"]+)"/) || [])[1];
  const layoutRe = new RegExp(`(<style:page-layout style:name="${layoutName}">\\s*<style:page-layout-properties[^>]*?)`
    + 'fo:page-width="[^"]*" fo:page-height="[^"]*"');
  if (!layoutName || !layoutRe.test(base)) throw new Error("the round-tripped seed has no master page layout to resize");
  const sources = PRESETS.map(([, w, h], i) => {
    const file = path.join(dir, `impress-${i + 1}.fodp`);
    fs.writeFileSync(file, base.replace(layoutRe, `$1fo:page-width="${w}cm" fo:page-height="${h}cm"`));
    return file;
  });
  soffice(['--convert-to', 'pdf', '--outdir', dir, ...sources]);
  const pdfs = sources.map((s) => s.replace(/\.fodp$/, '.pdf'));
  const out = cp.spawnSync('node', [CLI, '--deck-info', ...pdfs.filter((p) => fs.existsSync(p))],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  const byPath = new Map(out.stdout.trim().split('\n').filter(Boolean).map((l) => JSON.parse(l)).map((r) => [r.path, r]));

  console.log(`# exp-deck-export-corpus ${new Date().toISOString()}  ${version}  expectations: ${expect === 'before' ? 'P2' : 'P3'}`);
  console.log('preset                    predicted (bp)     exported (bp)      size  pages  creator / producer                 verdict                         as expected');
  const cases = [];
  for (let i = 0; i < PRESETS.length; i++) {
    const [name, wCm, hCm, deckBefore] = PRESETS[i];
    const predicted = [wCm * BP_PER_CM, hCm * BP_PER_CM];
    const r = byPath.get(pdfs[i]) || null;
    const size = r && r.sizes && r.sizes[0];
    const sizeOk = Boolean(size && r.distinctSizes === 1
      && Math.abs(size[0] - predicted[0]) <= TOLERANCE && Math.abs(size[1] - predicted[1]) <= TOLERANCE);
    const wantDeck = expect === 'before' ? deckBefore : true;
    const verdictOk = Boolean(r && (wantDeck ? (r.deck && r.tier === 'probable' && r.source === 'slide-export') : !r.deck));
    const verdict = r ? (r.deck ? `deck ${r.tier}/${r.source}` : 'not a deck') : 'NOT EXPORTED';
    const fmt = (s) => (s ? `${s[0].toFixed(1)} × ${s[1].toFixed(1)}` : '—');
    console.log(`${name.padEnd(25)} ${fmt(predicted).padEnd(18)} ${fmt(size).padEnd(18)} ${(sizeOk ? 'P1 ✓' : 'P1 ✗').padEnd(5)} ${String(r ? r.pages : '—').padEnd(6)} `
      + `${`${r ? r.creator : ''} / ${r ? r.producer : ''}`.slice(0, 34).padEnd(35)} ${verdict.padEnd(31)} ${verdictOk ? 'yes' : 'NO'}`);
    cases.push({ preset: name, cm: [wCm, hCm], predicted, exported: size || null, pages: r ? r.pages : null,
                 creator: r ? r.creator : null, producer: r ? r.producer : null,
                 deck: r ? r.deck : null, tier: r ? r.tier : null, source: r ? r.source : null, sizeOk, verdictOk });
  }
  const sizes = cases.filter((c) => c.sizeOk).length;
  const verdicts = cases.filter((c) => c.verdictOk).length;
  console.log(`\n${cases.length} presets: exported size as predicted (P1) in ${sizes}; verdict as expected (${expect === 'before' ? 'P2' : 'P3'}) in ${verdicts}`);
  if (json) fs.writeFileSync(json, JSON.stringify({ date: new Date().toISOString(), soffice: version, expect, cases }, null, 1));
  fs.rmSync(dir, { recursive: true, force: true });
  process.exitCode = sizes === cases.length && verdicts === cases.length ? 0 : 1;
}

main();
