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

/** Delete `before` units before the cursor and `after` units after it (deleteSurroundingText). */
export function deleteSurrounding(s: EditorState, before: number, after = 0): EditorState {
  return {
    before: s.before.slice(0, Math.max(0, s.before.length - before)),
    after: s.after.slice(Math.min(s.after.length, after)),
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
