# 04 — Sanitizer context-freedom

**Status: Proven + Tested.** This page establishes the property that makes the
[streaming pipeline](../theory/09-document-streaming-and-the-wpda.md) *safe*: sanitizing a document one block
at a time yields exactly the same result as sanitizing the whole document at once. Because the streaming sink
sanitizes each block as it is appended, this equivalence is what guarantees a streamed document is neither
more nor less safe than its batch render.

---

## 1. Terms

| Term | Definition |
|------|------------|
| **Sanitizer** | A function that removes anything not on an **allowlist** from an HTML/HAST tree — disallowed tags, attributes, and URL protocols — before the tree is trusted enough to insert into the DOM. vinary-viewer uses one schema, the GitHub allowlist, in `vinary.ir.backend.sanitize`. |
| **Allowlist** | The explicit set of permitted tags, per-tag permitted attributes, and permitted URL protocols. Everything not listed is stripped. |
| **Context-free (of a sanitizer)** | The sanitizer's decision for a node depends **only on that node** — its tag, its attributes, its protocols — and never on the node's ancestors, siblings, or position. |
| **Block** | A top-level child of the IR document; the unit the streaming sink lowers, sanitizes, and appends ([theory/09 §6](../theory/09-document-streaming-and-the-wpda.md)). |

---

## 2. The property

Let `$`\sigma`$` be the sanitizer and let a document be a sequence of top-level blocks
`$`d = b_1 \, b_2 \cdots b_n`$`. Write `$`\mathbin{\Vert}`$` for concatenation of rendered/serialized output.

> **Proposition (per-block ≡ whole-document sanitization).** If `$`\sigma`$` is context-free, then
> ```math
> \sigma(b_1 \, b_2 \cdots b_n) \;=\; \sigma(b_1) \mathbin{\Vert} \sigma(b_2) \mathbin{\Vert} \cdots \mathbin{\Vert} \sigma(b_n).
> ```
> That is, sanitizing the whole document equals concatenating the independently-sanitized blocks.

**Proof.** A context-free `$`\sigma`$` is, by definition, a *per-node* map: it visits each node and keeps,
strips, or rewrites it using only that node's own tag/attributes/protocol. Formally `$`\sigma`$` is a tree
homomorphism `$`\sigma(t) = h(\text{root}(t),\, \sigma(c_1), \dots, \sigma(c_k))`$` where the local decision
`$`h`$` reads no context outside the node. For the document root, whose children are exactly the top-level
blocks `$`b_1 \dots b_n`$`, the root is a transparent container (a document fragment) that the serializer emits
as the concatenation of its children. Hence
`$`\sigma(d) = \sigma(b_1) \mathbin{\Vert} \cdots \mathbin{\Vert} \sigma(b_n)`$`. Because no `$`\sigma(b_i)`$`
reads any `$`b_{j\neq i}`$`, evaluating them separately and concatenating cannot differ from evaluating them
together. `$`\qquad\blacksquare`$`

The GitHub allowlist `$`\sigma`$` **is** context-free: every rule in the schema is a predicate on a single
element — "`<a>` may keep `href` if its protocol is in {http, https, mailto, …}", "`<span>` loses `className`
unless it is on the small allowed set", "`<script>` is stripped". None consults an ancestor or sibling. So the
proposition applies, and it applies *byte-for-byte* because the serializer is itself a per-node fold over the
sanitized tree.

---

## 3. Why the streaming pipeline needs exactly this

The [append sink](../theory/09-document-streaming-and-the-wpda.md#6--the-append-sink-and-byte-parity)
(`vinary.stream.sink`) sanitizes **each block** with the same schema before `insertAdjacentHTML`. The security
requirement is that this per-block regime not open a hole the batch regime closes — a streamed document must
not, by virtue of being chunked, admit markup a whole-document sanitize would have stripped (nor gratuitously
strip markup the batch render keeps, which would be a correctness, not safety, regression). The proposition
guarantees both directions at once: streamed sanitized output *equals* batch sanitized output. Sanitizing per
block is therefore not a weaker approximation of whole-document sanitizing — it is the *same function*,
factored across the stream.

This is also why the migration could route **office documents** through the same schema and *strengthen* their
security: the previous main-process regex sanitizer was replaced by this context-free IR allowlist
([`CHANGELOG`](../../CHANGELOG.txt), `[0.3.0-dev]`), and the streaming proof carried over unchanged because it
depends only on context-freedom, not on the format.

---

## 4. What context-freedom does *not* cover, and how that is handled

Context-freedom is a property of the *allowlist*, not of everything the pipeline does to a block. Two passes
are intentionally **outside** `$`\sigma`$` and must be reasoned about separately:

1. **MathJax SVG injection.** Rendered math SVG is injected *post-sanitize*, keyed off a marker node, precisely
   so that the sanitizer never has to allow the large SVG surface. The marker is context-free; the injection
   is a controlled, trusted rewrite. (See [ADR-0024](../design-decisions/0024-org-export-blocks-front-matter-and-math.md).)
2. **Heading-slug uniqueness.** `rehype-slug` dedups heading ids *across the whole document*, which is a
   genuinely document-global operation — and it is exactly why Markdown/Org use the *progressive block-commit*
   engine (which parses the whole document for full context) rather than a bounded byte-stream for these
   formats ([theory/09 §6.1](../theory/09-document-streaming-and-the-wpda.md)). Slugging is a correctness
   concern, not a safety one, so it does not affect the sanitization proof.

Neither weakens §2: the *sanitizer* remains context-free, and it is the sanitizer whose per-block equivalence
the streaming pipeline's safety rests on.

---

## 5. Verification status

- **Proven** — §2, resting on the per-node structure of the GitHub allowlist schema in
  `vinary.ir.backend.sanitize`.
- **Tested (indirectly, byte-parity)** — the streamed-vs-batch `innerHTML` equalities of
  [01 — Byte-parity verification](01-byte-parity-verification.md) exercise the *whole* per-block pipeline,
  sanitizer included: if per-block sanitization diverged from whole-document sanitization, the streamed and
  batch `innerHTML` would differ and the smoke would fail. Byte-parity is thus an executable witness of the
  proposition on every corpus it runs.
- **Tested (XSS strip)** — the Electron smoke's XSS-strip assertion confirms the schema actually removes a
  script/`javascript:`-protocol payload, i.e. that the allowlist is enforced at all.

## 6. See also

- [theory/09 §6 — the append sink and byte-parity](../theory/09-document-streaming-and-the-wpda.md).
- [security/threat-model](../security/threat-model.md) — the sanitizer's place in the trust model.
- [01 — Byte-parity verification](01-byte-parity-verification.md) — the executable witness.
- [ADR-0017 — Common document IR](../design-decisions/0017-common-document-ir.md) — the single-sanitizer decision.
