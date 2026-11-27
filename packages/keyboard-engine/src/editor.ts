/**
 * A minimal, pure model of a host text field. Used by:
 *  - the web playground (simulated InputConnection), and
 *  - the shell's optimistic editor mirror, reconciled on every native `editor.state` (SPEC §7.3.5).
 */
import type { EditorState } from '@ak/protocol';
import { currentWord } from './text';

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

/** Move a collapsed cursor by `delta` code units (clamped). */
export function moveCursor(s: EditorState, delta: number): EditorState {
  const text = s.before + s.selected + s.after;
  const pos = Math.max(0, Math.min(text.length, s.before.length + delta));
  return { before: text.slice(0, pos), after: text.slice(pos), selected: '' };
}

export function fullText(s: EditorState): string {
  return s.before + s.selected + s.after;
}
