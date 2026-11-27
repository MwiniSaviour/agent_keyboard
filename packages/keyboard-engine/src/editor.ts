/**
 * A minimal, pure model of a host text field. Used by:
 *  - the web playground (simulated InputConnection), and
 *  - the shell's optimistic editor mirror, reconciled on every native `editor.state` (SPEC §7.3.5).
 */
import type { EditorState } from '@ak/protocol';
import { currentWord, firstGraphemeLength, lastGraphemeLength } from './text';

export const EMPTY_EDITOR: EditorState = Object.freeze({ before: '', after: '', selected: '' });

/** Replace the selection with `text`, cursor after it (InputConnection.commitText semantics). */
export function commitText(s: EditorState, text: string): EditorState {
  return { before: s.before + text, after: s.after, selected: '' };
}

/**
 * Delete `before` UTF-16 units before the cursor and `after` units after it (deleteSurroundingText).
 * The shell's mirror and the engine count in UTF-16 units; the wire does not (see below).
 */
export function deleteSurrounding(s: EditorState, before: number, after = 0): EditorState {
  return {
    before: s.before.slice(0, Math.max(0, s.before.length - before)),
    after: s.after.slice(Math.min(s.after.length, after)),
    selected: s.selected,
  };
}

/**
 * `text.delete` counts Unicode code points on the wire (SPEC §8.2), matching Android's
 * `InputConnection.deleteSurroundingTextInCodePoints`. These convert at that boundary, so an emoji
 * (2 UTF-16 units, 1 code point) is never sent as "delete 2" and eats the character before it.
 */
export function codePointLength(text: string): number {
  let n = 0;
  for (let i = 0; i < text.length; i++, n++) {
    const c = text.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < text.length) {
      const d = text.charCodeAt(i + 1);
      if (d >= 0xdc00 && d <= 0xdfff) i++;
    }
  }
  return n;
}

/** Wire counts for deleting `before`/`after` UTF-16 units around the cursor of `s`. */
export function toWireDelete(s: EditorState, before: number, after = 0): { before: number; after: number } {
  return {
    before: codePointLength(s.before.slice(Math.max(0, s.before.length - before))),
    after: codePointLength(s.after.slice(0, Math.max(0, after))),
  };
}

/** Inverse of `toWireDelete`: apply a wire (code point) delete to `s`. Used by the web mock host. */
export function deleteSurroundingCodePoints(s: EditorState, before: number, after = 0): EditorState {
  const head = Array.from(s.before);
  const tail = Array.from(s.after);
  return {
    before: head.slice(0, Math.max(0, head.length - before)).join(''),
    after: tail.slice(Math.min(tail.length, Math.max(0, after))).join(''),
    selected: s.selected,
  };
}

export function replaceText(s: EditorState, scope: 'selection' | 'field' | 'word', text: string): EditorState {
  switch (scope) {
    case 'field':
      return { before: text, after: '', selected: '' };
    case 'selection':
      return commitText(s, text);
    case 'word': {
      const w = currentWord(s.before);
      return { before: s.before.slice(0, s.before.length - w.length) + text, after: s.after, selected: '' };
    }
  }
}

/**
 * Move a collapsed cursor by `delta` grapheme clusters (clamped), matching the wire unit of
 * `editor.cursor`: Android applies one D-pad step per unit, and a D-pad step never lands inside a
 * surrogate pair or an emoji ZWJ sequence. A selection collapses first (to its start when moving left,
 * its end when moving right), like a D-pad press, and that collapse counts as one step.
 */
export function moveCursor(s: EditorState, delta: number): EditorState {
  let before = s.before;
  let after = s.after;
  let steps = Math.trunc(Math.abs(delta));
  if (s.selected && steps > 0) {
    if (delta < 0) after = s.selected + after;
    else before = before + s.selected;
    steps--;
  } else if (s.selected) {
    before += s.selected;
  }
  for (; steps > 0; steps--) {
    if (delta < 0) {
      const n = lastGraphemeLength(before);
      if (n === 0) break;
      after = before.slice(-n) + after;
      before = before.slice(0, -n);
    } else {
      const n = firstGraphemeLength(after);
      if (n === 0) break;
      before += after.slice(0, n);
      after = after.slice(n);
    }
  }
  return { before, after, selected: '' };
}

export function fullText(s: EditorState): string {
  return s.before + s.selected + s.after;
}
