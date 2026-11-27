/**
 * Grapheme-safe text helpers (NFR-I). Pure, no DOM.
 */
import type { CapsMode, InputKind } from '@ak/protocol';

type SegmenterLike = { segment(input: string): Iterable<{ segment: string }> };

let segmenter: SegmenterLike | null | undefined;
function getSegmenter(): SegmenterLike | null {
  if (segmenter === undefined) {
    const Seg = (Intl as unknown as { Segmenter?: new (l?: string, o?: object) => SegmenterLike }).Segmenter;
    segmenter = Seg ? new Seg(undefined, { granularity: 'grapheme' }) : null;
  }
  return segmenter;
}

/** Length (UTF-16 code units) of the last grapheme cluster in `text` (emoji ZWJ sequences count as one). */
export function lastGraphemeLength(text: string): number {
  if (text.length === 0) return 0;
  const seg = getSegmenter();
  if (seg) {
    // Only segment the tail; grapheme clusters are short. 32 units covers long ZWJ family emoji.
    const tail = text.slice(-32);
    let last = '';
    for (const s of seg.segment(tail)) last = s.segment;
    return last.length;
  }
  // Fallback: surrogate pair awareness.
  const code = text.charCodeAt(text.length - 1);
  return code >= 0xdc00 && code <= 0xdfff && text.length >= 2 ? 2 : 1;
}

/** Length (UTF-16 code units) of the first grapheme cluster in `text`. */
export function firstGraphemeLength(text: string): number {
  if (text.length === 0) return 0;
  const seg = getSegmenter();
  if (seg) {
    for (const s of seg.segment(text.slice(0, 32))) return s.segment.length;
  }
  const code = text.charCodeAt(0);
  return code >= 0xd800 && code <= 0xdbff && text.length >= 2 ? 2 : 1;
}

const WORD_CHAR = /[\p{L}\p{M}\p{N}'’-]/u;

/** The word currently being typed: trailing word characters before the cursor. */
export function currentWord(before: string): string {
  let i = before.length;
  while (i > 0 && WORD_CHAR.test(before[i - 1] as string)) i--;
  return before.slice(i);
}

/** Number of UTF-16 units to delete for "delete previous word" (trailing spaces + word, FR-KB-05). */
export function previousWordDeleteLength(before: string): number {
  let i = before.length;
  while (i > 0 && /\s/.test(before[i - 1] as string)) i--;
  if (i > 0 && !WORD_CHAR.test(before[i - 1] as string)) return before.length - (i - 1);
  while (i > 0 && WORD_CHAR.test(before[i - 1] as string)) i--;
  return before.length - i;
}

const NO_AUTOCAP_KINDS: ReadonlySet<InputKind> = new Set(['email', 'url', 'password', 'otp', 'number', 'phone']);

/** Should the next character be capitalised automatically? (FR-KB-04) */
export function shouldAutoCapitalize(before: string, caps: CapsMode, kind: InputKind): boolean {
  if (caps === 'none' || NO_AUTOCAP_KINDS.has(kind)) return false;
  if (caps === 'characters') return true;
  if (before.length === 0) return true;
  if (caps === 'words') return /\s$/.test(before);
  // sentences
  if (/\n\s*$/.test(before)) return true;
  return /[.!?…]["')\]]*\s+$/.test(before);
}

/** Apply the case of `pattern` (as typed) to `word` (dictionary form). */
export function matchCase(word: string, pattern: string): string {
  if (pattern.length === 0) return word;
  if (pattern.length > 1 && pattern === pattern.toUpperCase() && pattern !== pattern.toLowerCase()) {
    return word.toUpperCase();
  }
  const first = pattern[0] as string;
  if (first !== first.toLowerCase()) return word.charAt(0).toUpperCase() + word.slice(1);
  return word;
}
