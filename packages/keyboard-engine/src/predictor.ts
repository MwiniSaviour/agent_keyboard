/**
 * Prediction interface + a tiny *development* predictor.
 *
 * In production the native PredictionEngine (n-gram LM + personal dictionary, SPEC §12.4) pushes
 * `suggestions.update`; this implementation exists so the web playground and tests behave
 * realistically without native code. It is intentionally small (≈ 2 KB of data).
 */
import type { Suggestion } from '@ak/protocol';
import { currentWord, matchCase } from './text';

export interface Predictor {
  suggest(before: string, limit?: number): Suggestion[];
  learn(word: string): void;
}

/** Keyboard-adjacency on QWERTY, used to weight substitutions in corrections. */
const ADJACENT: Readonly<Record<string, string>> = {
  q: 'wa', w: 'qeas', e: 'wrsd', r: 'etdf', t: 'ryfg', y: 'tugh', u: 'yihj', i: 'uojk', o: 'ipkl', p: 'ol',
  a: 'qwsz', s: 'awedxz', d: 'serfcx', f: 'drtgvc', g: 'ftyhbv', h: 'gyujnb', j: 'huikmn', k: 'jiolm', l: 'kop',
  z: 'asx', x: 'zsdc', c: 'xdfv', v: 'cfgb', b: 'vghn', n: 'bhjm', m: 'njk',
};

/** Weighted Damerau-Levenshtein, capped at `max` for speed. Adjacent-key substitutions cost 0.5. */
export function editDistance(a: string, b: string, max = 2): number {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  const rows = a.length + 1;
  const cols = b.length + 1;
  const d: number[][] = Array.from({ length: rows }, () => new Array<number>(cols).fill(0));
  for (let i = 0; i < rows; i++) d[i]![0] = i;
  for (let j = 0; j < cols; j++) d[0]![j] = j;
  for (let i = 1; i < rows; i++) {
    let rowMin = Infinity;
    for (let j = 1; j < cols; j++) {
      const ca = a[i - 1]!;
      const cb = b[j - 1]!;
      const sub = ca === cb ? 0 : ADJACENT[ca]?.includes(cb) ? 0.5 : 1;
      let v = Math.min(d[i - 1]![j]! + 1, d[i]![j - 1]! + 1, d[i - 1]![j - 1]! + sub);
      if (i > 1 && j > 1 && ca === b[j - 2] && a[i - 2] === cb) v = Math.min(v, d[i - 2]![j - 2]! + 1);
      d[i]![j] = v;
      rowMin = Math.min(rowMin, v);
    }
    if (rowMin > max) return max + 1;
  }
  return d[a.length]![b.length]!;
}

export interface Lexicon {
  /** word → relative frequency (higher = more common). */
  unigrams: ReadonlyMap<string, number>;
  /** previous word → likely next words, ordered. */
  bigrams: ReadonlyMap<string, readonly string[]>;
}

export function createDevPredictor(lexicon: Lexicon): Predictor {
  const unigrams = new Map(lexicon.unigrams);
  const words = (): string[] => [...unigrams.keys()];

  function previousWord(before: string): string | undefined {
    const m = /([\p{L}'’]+)[^\p{L}'’]*$/u.exec(before.trimEnd());
    return m?.[1]?.toLowerCase();
  }

  return {
    suggest(before, limit = 3) {
      const typed = currentWord(before);
      const lower = typed.toLowerCase();

      if (lower.length === 0) {
        // Next-word prediction after a space.
        if (!/\s$/.test(before) && before.length > 0) return [];
        const prev = previousWord(before);
        const next = (prev && lexicon.bigrams.get(prev)) || lexicon.bigrams.get('<s>') || [];
        const sentenceStart = before.trim().length === 0 || /[.!?]\s*$/.test(before);
        return next.slice(0, limit).map((w) => ({
          text: sentenceStart ? w.charAt(0).toUpperCase() + w.slice(1) : w,
          kind: 'next' as const,
        }));
      }

      const scored: { w: string; s: number; kind: Suggestion['kind'] }[] = [];
      for (const w of words()) {
        const f = unigrams.get(w) ?? 1;
        if (w.startsWith(lower) && w !== lower) scored.push({ w, s: 2 + Math.log(f), kind: 'completion' });
        else if (lower.length >= 3 && w !== lower && Math.abs(w.length - lower.length) <= 2) {
          const dist = editDistance(lower, w, 1.5);
          if (dist <= 1.5) scored.push({ w, s: 1.5 + Math.log(f) - dist * 2, kind: 'correction' });
        }
      }
      scored.sort((a, b) => b.s - a.s);

      const out: Suggestion[] = [{ text: typed, kind: 'verbatim' }];
      const seen = new Set([lower]);
      for (const c of scored) {
        if (out.length >= limit) break;
        if (seen.has(c.w)) continue;
        seen.add(c.w);
        out.push({ text: matchCase(c.w, typed), kind: c.kind });
      }
      // Mark the best non-verbatim candidate as primary when the typed word is unknown.
      const known = unigrams.has(lower);
      const best = out[1];
      if (!known && best && best.kind === 'correction') best.primary = true;
      // Present as [alt, primary-or-verbatim, alt] like mainstream keyboards: verbatim in the middle.
      if (out.length === 3) return [out[1]!, out[0]!, out[2]!];
      return out;
    },
    learn(word) {
      const w = word.toLowerCase();
      if (w.length < 2) return;
      unigrams.set(w, (unigrams.get(w) ?? 0) + 5);
    },
  };
}
