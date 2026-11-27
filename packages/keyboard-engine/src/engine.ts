/**
 * The keyboard input engine: a pure reducer (ADR-0008).
 *
 *   reduce(state, event, editor) → { state, effects }
 *
 * - `editor` is the latest known snapshot of the host field (text around cursor + field context).
 * - `effects` are the ONLY way the engine touches the outside world. The shell maps them to bridge
 *   messages (text.*, editor.action, feedback.key) and to UI actions (open panels).
 *
 * Determinism makes every behaviour unit-testable and every bug report replayable from an event log.
 */
import type { EditorState, FieldContext, ImeAction } from '@ak/protocol';
import { keyById, type KeySpec, type LayerId } from './layouts';
import { currentWord, lastGraphemeLength, previousWordDeleteLength, shouldAutoCapitalize } from './text';

export type ShiftState = 'off' | 'once' | 'locked';

export interface EngineSettings {
  autoCapitalize: boolean;
  doubleSpacePeriod: boolean;
  /** Max interval for shift double-tap → caps lock (FR-KB-04). */
  capsLockIntervalMs: number;
  /** Max interval between spaces for ". " (FR-KB-06). */
  doubleSpaceIntervalMs: number;
}

export const DEFAULT_ENGINE_SETTINGS: EngineSettings = {
  autoCapitalize: true,
  doubleSpacePeriod: true,
  capsLockIntervalMs: 300,
  doubleSpaceIntervalMs: 450,
};

export interface EngineState {
  layer: LayerId;
  shift: ShiftState;
  lastShiftTapAt: number;
  lastSpaceAt: number;
  settings: EngineSettings;
}

export interface EditorSnapshot {
  state: EditorState;
  field: Pick<FieldContext, 'kind' | 'capsMode' | 'imeAction'>;
}

export type EngineEvent =
  | { type: 'tap'; keyId: string; at: number }
  | { type: 'longpress'; keyId: string; at: number }
  | { type: 'backspace'; mode: 'grapheme' | 'word'; at: number }
  | { type: 'suggestion'; text: string; at: number }
  | { type: 'insert'; text: string; at: number }
  | { type: 'layer'; layer: LayerId }
  | { type: 'editorChanged' };

export type UiPanel = 'emoji' | 'voice';

export type Effect =
  | { type: 'commit'; text: string }
  | { type: 'delete'; before: number }
  | { type: 'action'; action: Exclude<ImeAction, 'none'> }
  | { type: 'feedback'; kind: 'tap' | 'delete' | 'space' | 'return' | 'mod' }
  | { type: 'panel'; panel: UiPanel };

export interface Reduction {
  state: EngineState;
  effects: Effect[];
}

export function createEngineState(settings: Partial<EngineSettings> = {}): EngineState {
  return {
    layer: 'letters',
    shift: 'off',
    lastShiftTapAt: -Infinity,
    lastSpaceAt: -Infinity,
    settings: { ...DEFAULT_ENGINE_SETTINGS, ...settings },
  };
}

/** Text before cursor after applying a selection replacement (selection is replaced by typing). */
function beforeAfterCommit(editor: EditorSnapshot, text: string): string {
  return editor.state.before + text;
}

/** Recompute auto-shift for a hypothetical `before`, unless caps lock is on. */
function autoShift(state: EngineState, before: string, editor: EditorSnapshot): ShiftState {
  if (state.shift === 'locked') return 'locked';
  if (!state.settings.autoCapitalize) return 'off';
  return shouldAutoCapitalize(before, editor.field.capsMode, editor.field.kind) ? 'once' : 'off';
}

const ACTIONABLE: ReadonlySet<ImeAction> = new Set(['send', 'search', 'go', 'next', 'done']);

/** What the return key does for this field (FR-KB-07). */
export function enterBehavior(field: EditorSnapshot['field']): Exclude<ImeAction, 'none'> {
  if (field.kind === 'multiline') return 'newline';
  return ACTIONABLE.has(field.imeAction) ? (field.imeAction as Exclude<ImeAction, 'none'>) : 'newline';
}

function commitChar(state: EngineState, editor: EditorSnapshot, raw: string): Reduction {
  const upper = state.shift !== 'off';
  const text = upper ? raw.toUpperCase() : raw;
  const nextBefore = beforeAfterCommit(editor, text);
  const shift = state.shift === 'locked' ? 'locked' : autoShift({ ...state, shift: 'off' }, nextBefore, editor);
  return {
    state: { ...state, shift },
    effects: [{ type: 'commit', text }, { type: 'feedback', kind: 'tap' }],
  };
}

function handleSpace(state: EngineState, editor: EditorSnapshot, at: number): Reduction {
  const { before, selected } = editor.state;
  // Symbol layers return to letters after space (FR-KB-02).
  const returnToLetters: LayerId = 'letters';
  const canPeriod =
    state.settings.doubleSpacePeriod &&
    selected.length === 0 &&
    at - state.lastSpaceAt <= state.settings.doubleSpaceIntervalMs &&
    /[\p{L}\p{N}] $/u.test(before);

  if (canPeriod) {
    const nextBefore = before.slice(0, -1) + '. ';
    return {
      state: {
        ...state,
        layer: returnToLetters,
        lastSpaceAt: -Infinity, // a third space must not produce another period
        shift: autoShift(state, nextBefore, editor),
      },
      effects: [{ type: 'delete', before: 1 }, { type: 'commit', text: '. ' }, { type: 'feedback', kind: 'space' }],
    };
  }

  const nextBefore = before + ' ';
  return {
    state: { ...state, layer: returnToLetters, lastSpaceAt: at, shift: autoShift(state, nextBefore, editor) },
    effects: [{ type: 'commit', text: ' ' }, { type: 'feedback', kind: 'space' }],
  };
}

function handleEnter(state: EngineState, editor: EditorSnapshot): Reduction {
  const behavior = enterBehavior(editor.field);
  if (behavior === 'newline') {
    const nextBefore = editor.state.before + '\n';
    return {
      state: { ...state, layer: 'letters', shift: autoShift(state, nextBefore, editor) },
      effects: [{ type: 'commit', text: '\n' }, { type: 'feedback', kind: 'return' }],
    };
  }
  return {
    state: { ...state, layer: 'letters' },
    effects: [{ type: 'action', action: behavior }, { type: 'feedback', kind: 'return' }],
  };
}

function handleShift(state: EngineState, at: number): Reduction {
  // Two taps within the interval lock caps regardless of the starting state (incl. auto-cap "once").
  if (state.shift !== 'locked' && at - state.lastShiftTapAt <= state.settings.capsLockIntervalMs) {
    return {
      state: { ...state, shift: 'locked', lastShiftTapAt: -Infinity },
      effects: [{ type: 'feedback', kind: 'mod' }],
    };
  }
  const shift: ShiftState = state.shift === 'off' ? 'once' : 'off';
  return { state: { ...state, shift, lastShiftTapAt: at }, effects: [{ type: 'feedback', kind: 'mod' }] };
}

function handleBackspace(state: EngineState, editor: EditorSnapshot, mode: 'grapheme' | 'word'): Reduction {
  const { before, selected } = editor.state;
  if (selected.length > 0) {
    // Committing an empty string replaces (deletes) the selection.
    return {
      state: { ...state, shift: autoShift(state, before, editor) },
      effects: [{ type: 'commit', text: '' }, { type: 'feedback', kind: 'delete' }],
    };
  }
  const n = mode === 'word' ? previousWordDeleteLength(before) : lastGraphemeLength(before);
  if (n === 0) return { state, effects: [{ type: 'feedback', kind: 'delete' }] };
  const nextBefore = before.slice(0, before.length - n);
  return {
    state: { ...state, shift: autoShift(state, nextBefore, editor) },
    effects: [{ type: 'delete', before: n }, { type: 'feedback', kind: 'delete' }],
  };
}

function handleSuggestion(state: EngineState, editor: EditorSnapshot, text: string): Reduction {
  const word = currentWord(editor.state.before);
  const committed = `${text} `;
  const nextBefore = editor.state.before.slice(0, editor.state.before.length - word.length) + committed;
  const effects: Effect[] = [];
  if (word.length > 0) effects.push({ type: 'delete', before: word.length });
  effects.push({ type: 'commit', text: committed }, { type: 'feedback', kind: 'tap' });
  return { state: { ...state, shift: autoShift(state, nextBefore, editor), lastSpaceAt: -Infinity }, effects };
}

function keyFor(id: string): KeySpec {
  const key = keyById(id);
  if (!key) throw new Error(`Unknown key id: ${id}`);
  return key;
}

export function reduce(state: EngineState, event: EngineEvent, editor: EditorSnapshot): Reduction {
  switch (event.type) {
    case 'tap': {
      const key = keyFor(event.keyId);
      switch (key.kind) {
        case 'char':
          return commitChar(state, editor, key.value ?? '');
        case 'space':
          return handleSpace(state, editor, event.at);
        case 'enter':
          return handleEnter(state, editor);
        case 'shift':
          return handleShift(state, event.at);
        case 'backspace':
          return handleBackspace(state, editor, 'grapheme');
        case 'layer':
          return {
            state: { ...state, layer: key.toLayer ?? 'letters', shift: key.toLayer === 'letters' ? autoShift(state, editor.state.before, editor) : state.shift },
            effects: [{ type: 'feedback', kind: 'mod' }],
          };
        case 'emoji':
          return { state, effects: [{ type: 'feedback', kind: 'mod' }, { type: 'panel', panel: 'emoji' }] };
        case 'mic':
          return { state, effects: [{ type: 'feedback', kind: 'mod' }, { type: 'panel', panel: 'voice' }] };
      }
      return { state, effects: [] };
    }
    case 'longpress': {
      const key = keyFor(event.keyId);
      if (key.kind === 'char' && key.hint) {
        // Hints are literal (digits/symbols): bypass shift casing but still consume one-shot shift.
        const nextBefore = editor.state.before + key.hint;
        return {
          state: { ...state, shift: state.shift === 'locked' ? 'locked' : autoShift({ ...state, shift: 'off' }, nextBefore, editor) },
          effects: [{ type: 'commit', text: key.hint }, { type: 'feedback', kind: 'tap' }],
        };
      }
      return { state, effects: [] };
    }
    case 'backspace':
      return handleBackspace(state, editor, event.mode);
    case 'suggestion':
      return handleSuggestion(state, editor, event.text);
    case 'insert': {
      const nextBefore = editor.state.before + event.text;
      return {
        state: { ...state, shift: autoShift(state, nextBefore, editor), lastSpaceAt: -Infinity },
        effects: [{ type: 'commit', text: event.text }],
      };
    }
    case 'layer':
      return { state: { ...state, layer: event.layer }, effects: [] };
    case 'editorChanged':
      return { state: { ...state, shift: autoShift(state, editor.state.before, editor) }, effects: [] };
  }
}
