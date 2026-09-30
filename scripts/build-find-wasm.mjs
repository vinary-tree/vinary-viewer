// Build the rc.6 combined search runtime from pinned source, then bundle the
// worker. Generated WASM/JS remain ignored build assets, as with grammars/PDF.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { build, version as esbuildVersion } from 'esbuild';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const parent = path.dirname(root);
const pinsPath = path.join(root, 'scripts', 'find-wasm.sources.json');
const pins = JSON.parse(fs.readFileSync(pinsPath, 'utf8'));
const staging = path.join(root, '.cache', 'find-wasm-src');
const output = path.join(root, 'resources', 'public', 'js');
const stampPath = path.join(root, '.cache', 'find-wasm-stamp.json');
const fingerprint = crypto.createHash('sha256').update(fs.readFileSync(pinsPath))
  .update(fs.readFileSync(path.join(root, 'src', 'vinary', 'renderer', 'find_worker.js')))
  .update(fs.readFileSync(fileURLToPath(import.meta.url)))
  .update(esbuildVersion).digest('hex');
const checkOnly = process.argv.includes('--check');
const sha256File = (filename) => crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex');

function run(command, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => code === 0 ? resolve() : reject(new Error(`${command} exited ${code}`)));
  });
}

async function archive(repository, revision, destination) {
  fs.mkdirSync(destination, { recursive: true });
  await new Promise((resolve, reject) => {
    const git = spawn('git', ['-C', repository, 'archive', '--format=tar', revision],
      { stdio: ['ignore', 'pipe', 'inherit'] });
    const tar = spawn('tar', ['-x', '-C', destination], { stdio: ['pipe', 'inherit', 'inherit'] });
    git.stdout.pipe(tar.stdin);
    let gitCode = null;
    let tarCode = null;
    let settled = false;
    const finish = () => {
      if (settled || gitCode === null || tarCode === null) return;
      settled = true;
      gitCode === 0 && tarCode === 0 ? resolve() : reject(new Error(`archive failed: ${repository}`));
    };
    git.on('error', reject);
    tar.on('error', reject);
    git.on('exit', (code) => { gitCode = code; finish(); });
    tar.on('exit', (code) => { tarCode = code; finish(); });
  });
}

async function sourceFor(name, spec) {
  const local = path.join(parent, spec.local || name);
  if (fs.existsSync(path.join(local, '.git'))) return local;
  const checkout = path.join(root, '.cache', 'find-wasm-git', name);
  if (!fs.existsSync(path.join(checkout, '.git'))) {
    fs.mkdirSync(path.dirname(checkout), { recursive: true });
    await run('git', ['clone', '--filter=blob:none', `https://github.com/vinary-tree/${spec.repo}.git`, checkout], root);
  }
  await run('git', ['fetch', 'origin', spec.revision], checkout);
  return checkout;
}

async function main() {
  fs.mkdirSync(output, { recursive: true });
  const wasmOut = path.join(output, 'find-wasm.wasm');
  const workerOut = path.join(output, 'find-worker.js');
  const stamp = fs.existsSync(stampPath) ? JSON.parse(fs.readFileSync(stampPath, 'utf8')) : null;
  const current = stamp && fs.existsSync(wasmOut) && fs.existsSync(workerOut)
    && stamp.fingerprint === fingerprint
    && stamp.wasmSha256 === sha256File(wasmOut)
    && stamp.workerSha256 === sha256File(workerOut);
  if (current) {
    console.log('find-wasm: pinned WASM and worker are up to date');
    return;
  }
  if (checkOnly) throw new Error('pinned search WASM/worker missing or stale; run npm run find-wasm:sync');
  for (const [name, spec] of Object.entries(pins)) {
    const destination = path.join(staging, name);
    const marker = path.join(destination, '.vinary-source-revision');
    if (fs.existsSync(marker) && fs.readFileSync(marker, 'utf8').trim() === spec.revision) continue;
    // The staging directory is ignored generated output. Avoid reading dirty
    // worktrees: git archive extracts exactly the committed source revision.
    fs.rmSync(destination, { recursive: true, force: true });
    await archive(await sourceFor(name, spec), spec.revision, destination);
    fs.writeFileSync(marker, `${spec.revision}\n`);
  }
  const runtime = path.join(staging, 'javascript-runtime');
  const patchDir = path.join(runtime, '.cargo');
  fs.mkdirSync(patchDir, { recursive: true });
  const patchNames = ['duallity', 'llattice', 'libdictenstein', 'liblevenshtein',
    'lling-llang', 'vinary-tree-interop'];
  const patchPaths = ['duallity', 'llattice', 'libdictenstein', 'liblevenshtein-rust',
    'lling-llang', 'vinary-tree-interop'];
  fs.writeFileSync(path.join(patchDir, 'config.toml'),
    '[patch.crates-io]\n' + patchNames.map((name, i) =>
      `${name} = { path = ${JSON.stringify(path.join(staging, patchPaths[i]))} }`).join('\n') + '\n');
  await run('wasm-pack', ['build', '--target', 'web', '--out-dir', '../generated/wasm',
    '--out-name', 'vinary_tree', 'rust', '--locked'], runtime);
  await run('node', ['scripts/prepare-generated-wasm.mjs'], runtime);
  const generated = path.join(runtime, 'generated', 'wasm');
  const sourceWasm = path.join(generated, 'vinary_tree_bg.wasm');
  const runtimeAlias = path.join(generated, 'vinary_tree.js');
  const factoryAlias = path.join(runtime, 'runtime-factory.mjs');
  const interopAlias = path.join(staging, 'vinary-tree-interop', 'bindings', 'javascript', 'index.mjs');
  await build({
    entryPoints: [path.join(root, 'src', 'vinary', 'renderer', 'find_worker.js')],
    outfile: workerOut, bundle: true, format: 'esm', platform: 'browser', target: 'es2022',
    alias: {
      '@vinary-tree/search-runtime': runtimeAlias,
      '@vinary-tree/search-runtime-factory': factoryAlias,
      '@vinary-tree/vinary-tree-interop': interopAlias
    },
    logLevel: 'info'
  });
  fs.copyFileSync(sourceWasm, wasmOut);
  const sha256 = sha256File(wasmOut);
  fs.mkdirSync(path.dirname(stampPath), { recursive: true });
  fs.writeFileSync(stampPath, JSON.stringify({ fingerprint, wasmSha256: sha256,
    workerSha256: sha256File(workerOut), pins }, null, 2) + '\n');
  console.log(`find-wasm: rc.6 ${sha256} (${fs.statSync(wasmOut).size} bytes)`);
}

main().catch((error) => { console.error(`find-wasm: ${error.message}`); process.exitCode = 1; });
