import { describe, expect, it } from 'vitest';
import type { EditorState } from '@ak/protocol';
import {
  commitText,
  createEngineState,
  deleteSurrounding,
  enterBehavior,
  reduce,
  type EditorSnapshot,
  type Effect,
  type EngineEvent,
  type EngineState,
} from '../src';

const field = (over: Partial<EditorSnapshot['field']> = {}): EditorSnapshot['field'] => ({
  kind: 'text',
  capsMode: 'sentences',
  imeAction: 'send',
  ...over,
});

/** Tiny harness: runs events through the reducer and applies text effects to a simulated editor. */
function harness(initial: Partial<EditorState> = {}, f = field()) {
  let state: EngineState = createEngineState();
  let editor: EditorState = { before: '', after: '', selected: '', ...initial };
  const log: Effect[] = [];
  // Mirror the shell: sync auto-shift with the initial editor state.
  state = reduce(state, { type: 'editorChanged' }, { state: editor, field: f }).state;

  const run = (event: EngineEvent) => {
    const r = reduce(state, event, { state: editor, field: f });
    state = r.state;
    for (const e of r.effects) {
      log.push(e);
      if (e.type === 'commit') editor = commitText(editor, e.text);
      if (e.type === 'delete') editor = deleteSurrounding(editor, e.before);
    }
    return r;
  };
  let t = 1_000;
  const tap = (keyId: string, dt = 100) => run({ type: 'tap', keyId, at: (t += dt) });
  const type = (text: string) => {
    for (const ch of text) {
      if (ch === ' ') tap('space', 500);
      else tap(`k:${ch.toLowerCase()}`);
    }
  };
  return {
    run,
    tap,
    type,
    get text() {
      return editor.before + editor.after;
    },
    get state() {
      return state;
    },
    log,
  };
}

describe('auto-capitalisation (FR-KB-04)', () => {
  it('capitalises the first letter of the field and of each sentence', () => {
    const h = harness();
    expect(h.state.shift).toBe('once');
    h.type('hi there');
    h.tap('k:.');
    h.tap('space', 600);
    h.type('ok');
    expect(h.text).toBe('Hi there. Ok');
  });

  it('never auto-capitalises email fields', () => {
    const h = harness({}, field({ kind: 'email', capsMode: 'sentences' }));
    expect(h.state.shift).toBe('off');
    h.type('me');
    expect(h.text).toBe('me');
  });

  it('respects capsMode none', () => {
    const h = harness({}, field({ capsMode: 'none' }));
    h.type('abc');
    expect(h.text).toBe('abc');
  });
});

describe('shift state machine (FR-KB-04)', () => {
  it('one-shot shift applies to one character only', () => {
    const h = harness({ before: 'x ' }, field({ capsMode: 'none' }));
    h.tap('shift');
    expect(h.state.shift).toBe('once');
    h.type('ab');
    expect(h.text).toBe('x Ab');
  });

  it('double tap locks caps; next tap unlocks', () => {
    const h = harness({ before: 'x ' }, field({ capsMode: 'none' }));
    h.tap('shift', 1000);
    h.tap('shift', 150);
    expect(h.state.shift).toBe('locked');
    h.type('abc');
    expect(h.text).toBe('x ABC');
    h.tap('shift', 1000);
    expect(h.state.shift).toBe('off');
  });

  it('double tap from auto-cap state also locks', () => {
    const h = harness();
    expect(h.state.shift).toBe('once');
    h.tap('shift', 1000);
    expect(h.state.shift).toBe('off');
    h.tap('shift', 120);
    expect(h.state.shift).toBe('locked');
  });
});

describe('space and double-space period (FR-KB-06)', () => {
  it('turns two quick spaces after a word into ". "', () => {
    const h = harness({ before: 'Hello' });
    h.tap('space', 500);
    h.tap('space', 200);
    expect(h.text).toBe('Hello. ');
    expect(h.state.shift).toBe('once');
  });

  it('does not add a period when spaces are slow', () => {
    const h = harness({ before: 'Hello' });
    h.tap('space', 500);
    h.tap('space', 900);
    expect(h.text).toBe('Hello  ');
  });

  it('a third quick space does not add another period', () => {
    const h = harness({ before: 'Hello' });
    h.tap('space', 500);
    h.tap('space', 100);
    h.tap('space', 100);
    expect(h.text).toBe('Hello.  ');
  });

  it('returns from the symbols layer to letters after space (FR-KB-02)', () => {
    const h = harness({ before: 'a' });
    h.tap('layer:symbols:123');
    expect(h.state.layer).toBe('symbols');
    h.tap('k:1');
    h.tap('space', 500);
    expect(h.state.layer).toBe('letters');
    expect(h.text).toBe('a1 ');
  });
});

describe('backspace (FR-KB-05)', () => {
  it('deletes a full emoji grapheme cluster', () => {
    const family = '👨‍👩‍👧';
    const h = harness({ before: `hi ${family}` });
    h.tap('backspace');
    expect(h.text).toBe('hi ');
  });

  it('word mode deletes trailing spaces and the previous word', () => {
    const h = harness({ before: 'hello big world  ' });
    h.run({ type: 'backspace', mode: 'word', at: 5000 });
    expect(h.text).toBe('hello big ');
  });

  it('deletes the selection by committing an empty string', () => {
    const h = harness({ before: 'a ', selected: 'chunk', after: ' b' });
    const r = h.tap('backspace');
    expect(r.effects[0]).toEqual({ type: 'commit', text: '' });
  });

  it('is a no-op (feedback only) on an empty field', () => {
    const h = harness();
    const r = h.tap('backspace');
    expect(r.effects).toEqual([{ type: 'feedback', kind: 'delete' }]);
  });
});

describe('return key (FR-KB-07)', () => {
  it('performs the editor action for single-line fields', () => {
    const h = harness({ before: 'hi' });
    const r = h.tap('enter');
    expect(r.effects[0]).toEqual({ type: 'action', action: 'send' });
  });

  it('inserts a newline in multiline fields', () => {
    expect(enterBehavior(field({ kind: 'multiline', imeAction: 'send' }))).toBe('newline');
    const h = harness({ before: 'line' }, field({ kind: 'multiline' }));
    h.tap('enter');
    expect(h.text).toBe('line\n');
    expect(h.state.shift).toBe('once');
  });
});

describe('long-press hints (FR-KB-03)', () => {
  it('emits the hint literally', () => {
    const h = harness({ before: 'mail ' }, field({ capsMode: 'none' }));
    h.run({ type: 'longpress', keyId: 'k:a', at: 9000 });
    h.run({ type: 'longpress', keyId: 'k:q', at: 9100 });
    expect(h.text).toBe('mail @1');
  });
});

describe('suggestions (FR-KB-08)', () => {
  it('replaces the current word and appends a space', () => {
    const h = harness({ before: 'see you tomor' });
    h.run({ type: 'suggestion', text: 'tomorrow', at: 9000 });
    expect(h.text).toBe('see you tomorrow ');
  });

  it('inserts after a space when there is no current word', () => {
    const h = harness({ before: 'thank ' });
    h.run({ type: 'suggestion', text: 'you', at: 9000 });
    expect(h.text).toBe('thank you ');
  });
});

describe('panels', () => {
  it('emoji and mic keys request panels', () => {
    const h = harness();
    expect(h.tap('emoji').effects).toContainEqual({ type: 'panel', panel: 'emoji' });
    expect(h.tap('mic').effects).toContainEqual({ type: 'panel', panel: 'voice' });
  });
});

describe('purity', () => {
  it('does not mutate the input state', () => {
    const s = createEngineState();
    const frozen = Object.freeze({ ...s, settings: Object.freeze({ ...s.settings }) });
    expect(() =>
      reduce(frozen, { type: 'tap', keyId: 'shift', at: 1 }, { state: { before: '', after: '', selected: '' }, field: field() }),
    ).not.toThrow();
  });

  it('throws on unknown key ids (programming error, never user input)', () => {
    const s = createEngineState();
    expect(() =>
      reduce(s, { type: 'tap', keyId: 'nope', at: 1 }, { state: { before: '', after: '', selected: '' }, field: field() }),
    ).toThrow(/Unknown key/);
  });
});
