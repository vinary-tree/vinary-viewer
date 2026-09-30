'use strict';

// Flexible in-page find. The one libdictenstein dictionary is shared by the
// liblevenshtein transducer and Duallity's lling-llang-backed FZF WFST.
// Postings contain document positions, which the dictionary's u64 value cannot.
// Future review: libdictenstein's vocabulary term IDs could feed a second
// n-gram dictionary; lling-llang could then compose spelling correction with
// n-gram correction. Benchmark that against block-posting intersection before
// adding the extra index or changing multi-term semantics.
const raw = require('@vinary-tree/search-runtime');
const { createRuntime } = require('@vinary-tree/search-runtime-factory');

let runtime = null;
let current = null;
let latestRequest = 0;
let phoneticRules = null;
let orthographyRules = null;

const wordPattern = /[\p{L}\p{N}_]+/gu;
const asciiWord = /^[a-z]+$/;

function foldStrict(value) {
  if (!/[^\x00-\x7f]/.test(value)) return value.toLowerCase();
  const out = new Array(value.length);
  for (let i = 0; i < value.length; i++) {
    const c = value.charAt(i);
    const lower = c.toLowerCase();
    out[i] = lower.length === 1 ? lower : c;
  }
  return out.join('');
}

function words(value) {
  return foldStrict(value).trim().split(/\s+/u).filter(Boolean);
}

function phoneticKey(term) {
  return phoneticRules.apply(orthographyRules.apply(term));
}

function closeIndex() {
  if (!current) return;
  current.transducer.close();
  current.dictionary.close();
  current = null;
}

function inExcludedRange(position, ranges) {
  let low = 0;
  let high = ranges.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (ranges[middle][0] <= position) low = middle + 1;
    else high = middle;
  }
  return low > 0 && position < ranges[low - 1][1];
}

function buildIndex(message) {
  closeIndex();
  const { text, docId, kind } = message;
  const excluded = (message.noPhoneticRanges || []).sort((a, b) => a[0] - b[0]);
  const postings = new Map();
  const blockStarts = [0];
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) blockStarts.push(i + 1);
  const blockFor = (position) => {
    let low = 0;
    let high = blockStarts.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (blockStarts[middle] <= position) low = middle + 1;
      else high = middle;
    }
    return low - 1;
  };
  for (const match of text.matchAll(wordPattern)) {
    const term = foldStrict(match[0]);
    const start = match.index;
    const entry = { start, end: start + match[0].length, block: blockFor(start),
      prose: kind === 'prose' && !inExcludedRange(start, excluded) };
    if (!postings.has(term)) postings.set(term, []);
    postings.get(term).push(entry);
  }
  const terms = Array.from(postings.keys()).sort();
  const dictionary = runtime.libdictenstein.doubleArrayTrie(
    terms.map((term, id) => ({ term, value: BigInt(id) })), 'unicode');
  const transducer = runtime.liblevenshtein.transducer(dictionary, 'transposition');
  const phonetic = new Map();
  if (phoneticRules && orthographyRules) {
    for (const term of terms) {
      if (!asciiWord.test(term)) continue;
      const key = phoneticKey(term);
      if (!phonetic.has(key)) phonetic.set(key, []);
      phonetic.get(key).push(term);
    }
  }
  current = { docId, text, kind, terms, dictionary, transducer, postings, phonetic };
  return { docId, lexemes: terms.length, blocks: blockStarts.length };
}

function editCandidates(index, query) {
  const size = Array.from(query).length;
  const distance = size < 3 ? 0 : size < 6 ? 1 : 2;
  const cursor = index.transducer.query(query, distance, 'distance-then-term');
  const found = new Map();
  try {
    for (;;) {
      const batch = cursor.nextBatch(256);
      if (!batch.length) break;
      for (const match of batch) {
        const term = index.terms[Number(match.id)];
        if (term && (!found.has(term) || found.get(term).cost > match.distance)) {
          found.set(term, { term, cost: match.distance * 10, kind: match.distance ? 'typo' : 'exact' });
        }
      }
    }
  } finally { cursor.close(); }
  return found;
}

function phoneticCandidates(index, query, found) {
  if (!asciiWord.test(query) || !phoneticRules || !orthographyRules) return;
  const key = phoneticKey(query);
  for (const term of index.phonetic.get(key) || []) {
    if (!found.has(term)) found.set(term, { term, cost: 100, kind: 'sound-alike' });
  }
}

const nextTurn = () => new Promise((resolve) => setTimeout(resolve, 0));

async function fzfCandidates(index, query, requestId) {
  const graph = runtime.duallity.wfst(index.dictionary, query, 0, 'standard', 'fzf');
  const pending = [{ state: graph.start(), term: '', score: 0 }];
  const visited = new Map();
  const accepted = new Map();
  let steps = 0;
  try {
    while (pending.length) {
      if (requestId !== latestRequest) return null;
      const item = pending.pop();
      const key = `${String(item.state)}\u0000${item.term}`;
      if ((visited.get(key) ?? -Infinity) >= item.score) continue;
      visited.set(key, item.score);
      const info = graph.state(item.state);
      if (!info.valid) continue;
      if (info.final) {
        const score = item.score + (info.finalWeight || 0);
        accepted.set(item.term, Math.max(accepted.get(item.term) ?? -Infinity, score));
      }
      for (const arc of info.arcs || []) {
        pending.push({ state: arc.target, term: item.term + (arc.output || ''),
          score: item.score + (arc.weight || 0) });
      }
      if (++steps % 2048 === 0) await nextTurn();
    }
  } finally { graph.close(); }
  return new Map(Array.from(accepted, ([term, score]) =>
    [term, { term, cost: -score, kind: 'fzf' }]));
}

function alignCharacters(query, term, start) {
  const spans = [];
  let offset = 0;
  for (const character of Array.from(query)) {
    const position = term.indexOf(character, offset);
    if (position < 0) return null;
    spans.push([start + position, start + position + character.length]);
    offset = position + character.length;
  }
  return spans;
}

function postingsFor(index, query, candidates, mode, phonetic) {
  const byBlock = new Map();
  for (const candidate of candidates.values()) {
    for (const occurrence of index.postings.get(candidate.term) || []) {
      if (candidate.kind === 'sound-alike' && (!phonetic || !occurrence.prose)) continue;
      const spans = mode === 'fzf'
        ? alignCharacters(query, candidate.term, occurrence.start)
        : [[occurrence.start, occurrence.end]];
      if (!spans) continue;
      if (!byBlock.has(occurrence.block)) byBlock.set(occurrence.block, []);
      byBlock.get(occurrence.block).push({ ...occurrence, spans,
        cost: candidate.cost, kind: candidate.kind });
    }
  }
  for (const entries of byBlock.values())
    entries.sort((a, b) => a.cost - b.cost || a.start - b.start || a.end - b.end);
  return byBlock;
}

function chooseBlock(groups) {
  // Minimum-cost bipartite assignment: one distinct lexeme occurrence per
  // query term. This stays polynomial even for a dense block and repeated terms.
  const occurrenceIds = new Map();
  for (const entries of groups) for (const entry of entries)
    if (!occurrenceIds.has(entry.start)) occurrenceIds.set(entry.start, occurrenceIds.size);
  if (occurrenceIds.size < groups.length) return null;
  const source = 0;
  const firstTerm = 1;
  const firstOccurrence = firstTerm + groups.length;
  const sink = firstOccurrence + occurrenceIds.size;
  const graph = Array.from({ length: sink + 1 }, () => []);
  const add = (from, to, cost, entry = null) => {
    const forward = { to, reverse: graph[to].length, capacity: 1, cost, entry };
    const backward = { to: from, reverse: graph[from].length, capacity: 0, cost: -cost, entry: null };
    graph[from].push(forward);
    graph[to].push(backward);
  };
  for (let term = 0; term < groups.length; term++) {
    add(source, firstTerm + term, 0);
    for (const entry of groups[term])
      add(firstTerm + term, firstOccurrence + occurrenceIds.get(entry.start), entry.cost, entry);
  }
  for (let occurrence = 0; occurrence < occurrenceIds.size; occurrence++)
    add(firstOccurrence + occurrence, sink, 0);

  let totalCost = 0;
  for (let assigned = 0; assigned < groups.length; assigned++) {
    const distance = Array(sink + 1).fill(Infinity);
    const previous = Array(sink + 1).fill(null);
    const queued = Array(sink + 1).fill(false);
    const queue = [source];
    distance[source] = 0;
    queued[source] = true;
    for (let head = 0; head < queue.length; head++) {
      const from = queue[head];
      queued[from] = false;
      for (let edgeIndex = 0; edgeIndex < graph[from].length; edgeIndex++) {
        const edge = graph[from][edgeIndex];
        if (!edge.capacity || distance[from] + edge.cost >= distance[edge.to]) continue;
        distance[edge.to] = distance[from] + edge.cost;
        previous[edge.to] = [from, edgeIndex];
        if (!queued[edge.to]) { queued[edge.to] = true; queue.push(edge.to); }
      }
    }
    if (!previous[sink]) return null;
    totalCost += distance[sink];
    for (let node = sink; node !== source;) {
      const [from, edgeIndex] = previous[node];
      const edge = graph[from][edgeIndex];
      edge.capacity = 0;
      graph[node][edge.reverse].capacity = 1;
      node = from;
    }
  }
  const selected = [];
  for (let term = 0; term < groups.length; term++) {
    const edge = graph[firstTerm + term].find((candidate) => candidate.entry && !candidate.capacity);
    if (!edge) return null;
    selected.push(edge.entry);
  }
  const spans = selected.flatMap((entry) => entry.spans).sort((a, b) => a[0] - b[0]);
  const kinds = selected.map((entry) => entry.kind);
  return { anchor: spans[0][0], spans, cost: totalCost,
    kind: kinds.includes('sound-alike') ? 'sound-alike' : kinds.includes('typo') ? 'typo' :
      kinds.includes('fzf') ? 'fzf' : 'exact' };
}

async function search(message) {
  const index = current;
  if (!index || index.docId !== message.docId) return null;
  const queryTerms = words(message.query);
  if (!queryTerms.length) return [];
  const lists = [];
  for (const term of queryTerms) {
    if (message.requestId !== latestRequest) return null;
    let candidates;
    if (message.mode === 'fzf') candidates = await fzfCandidates(index, term, message.requestId);
    else {
      candidates = editCandidates(index, term);
      if (message.phonetic) phoneticCandidates(index, term, candidates);
    }
    if (!candidates) return null;
    lists.push(postingsFor(index, term, candidates, message.mode, message.phonetic));
  }
  const smallest = lists.reduce((a, b) => a.size <= b.size ? a : b);
  const results = [];
  for (const block of smallest.keys()) {
    if (message.requestId !== latestRequest) return null;
    if (!lists.every((list) => list.has(block))) continue;
    const result = chooseBlock(lists.map((list) => list.get(block)));
    if (result) results.push({ block, ...result });
  }
  results.sort((a, b) => a.anchor - b.anchor);
  return results;
}

self.onmessage = async (event) => {
  const message = event.data;
  try {
    if (message.type === 'init') {
      await raw.default(message.wasm);
      runtime = createRuntime(raw);
      orthographyRules = runtime.liblevenshtein.phoneticRules('english-orthography');
      phoneticRules = runtime.liblevenshtein.phoneticRules('english-phonetic');
      self.postMessage({ type: 'ready' });
    } else if (message.type === 'index') {
      latestRequest++;
      self.postMessage({ type: 'indexed', ...buildIndex(message) });
    } else if (message.type === 'search') {
      latestRequest = message.requestId;
      const results = await search(message);
      if (results && message.requestId === latestRequest)
        self.postMessage({ type: 'results', requestId: message.requestId,
          docId: message.docId, results });
    } else if (message.type === 'cancel') {
      latestRequest++;
    }
  } catch (error) {
    self.postMessage({ type: 'error', operation: message.type,
      requestId: message.requestId, docId: message.docId,
      message: error && error.message ? error.message : String(error) });
  }
};
