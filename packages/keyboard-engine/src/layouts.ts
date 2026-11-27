/**
 * Layouts are data, not code (FR-KB-01). A renderer maps KeySpec → visual key; the reducer maps
 * KeySpec → effects. Widths are in "key units" (1 = a standard letter key).
 */

export type LayerId = 'letters' | 'symbols' | 'symbols2';

export type KeyKind =
  | 'char'
  | 'shift'
  | 'backspace'
  | 'layer'
  | 'emoji'
  | 'mic'
  | 'space'
  | 'enter';

export interface KeySpec {
  /** Stable id, used for DOM lookup (`data-key`) and tests. */
  id: string;
  kind: KeyKind;
  /** Character emitted (for `char`). Letters are lowercase; shift is applied by the reducer. */
  value?: string;
  /** Visual label when different from value (e.g. "123", "space"). */
  label?: string;
  /** Long-press output, rendered as a superscript hint (FR-KB-03). */
  hint?: string;
  /** Target layer for `layer` keys. */
  toLayer?: LayerId;
  /** Width in key units (default 1). */
  width?: number;
  /** Rendering role: modifiers are inverted (black on light, white on dark) per the reference design. */
  role: 'key' | 'mod';
  /** Accessible name. */
  a11y: string;
}

export interface KeyRow {
  keys: KeySpec[];
  /** Horizontal inset on each side, in key units (row 2 of QWERTY is inset by 0.5). */
  inset?: number;
}

export interface Layout {
  id: LayerId;
  rows: KeyRow[];
}

const char = (value: string, hint?: string, a11y?: string): KeySpec => ({
  id: `k:${value}`,
  kind: 'char',
  value,
  role: 'key',
  a11y: a11y ?? value,
  ...(hint !== undefined ? { hint } : {}),
});

const shift: KeySpec = { id: 'shift', kind: 'shift', role: 'mod', width: 1.5, a11y: 'Shift' };
const backspace: KeySpec = { id: 'backspace', kind: 'backspace', role: 'mod', width: 1.5, a11y: 'Delete' };
const emoji: KeySpec = { id: 'emoji', kind: 'emoji', role: 'mod', width: 1.25, a11y: 'Emoji' };
const mic: KeySpec = { id: 'mic', kind: 'mic', role: 'key', width: 1, a11y: 'Voice typing' };
const space: KeySpec = { id: 'space', kind: 'space', label: 'space', role: 'key', width: 4.5, a11y: 'Space' };
const enter: KeySpec = { id: 'enter', kind: 'enter', label: 'return', role: 'mod', width: 2.5, a11y: 'Return' };

const toLayer = (layer: LayerId, label: string, width: number, a11y: string): KeySpec => ({
  id: `layer:${layer}:${label}`,
  kind: 'layer',
  toLayer: layer,
  label,
  width,
  role: 'mod',
  a11y,
});

/** Long-press hints for QWERTY, matching the dark reference design. */
export const QWERTY_HINTS: Readonly<Record<string, string>> = {
  q: '1', w: '2', e: '3', r: '4', t: '5', y: '6', u: '7', i: '8', o: '9', p: '0',
  a: '@', s: '#', d: '$', f: '-', g: '~', h: '[', j: '*', k: '(', l: ')',
  z: '-', x: '.', c: "'", v: ':', b: ';', n: '!', m: '?',
};

const lettersRow = (letters: string): KeySpec[] =>
  [...letters].map((l) => char(l, QWERTY_HINTS[l]));

const bottomRow = (layerKey: KeySpec): KeyRow => ({ keys: [layerKey, emoji, mic, space, enter] });

export const QWERTY: Layout = {
  id: 'letters',
  rows: [
    { keys: lettersRow('qwertyuiop') },
    { keys: lettersRow('asdfghjkl'), inset: 0.5 },
    { keys: [shift, ...lettersRow('zxcvbnm'), backspace] },
    bottomRow(toLayer('symbols', '123', 1.25, 'Numbers and symbols')),
  ],
};

export const SYMBOLS: Layout = {
  id: 'symbols',
  rows: [
    { keys: [...'1234567890'].map((d) => char(d)) },
    { keys: ['-', '/', ':', ';', '(', ')', '$', '&', '@', '"'].map((c) => char(c)) },
    {
      keys: [
        toLayer('symbols2', '#+=', 1.5, 'More symbols'),
        ...['.', ',', '?', '!', "'"].map((c) => ({ ...char(c), width: 1.4 })),
        backspace,
      ],
    },
    bottomRow(toLayer('letters', 'ABC', 1.25, 'Letters')),
  ],
};

export const SYMBOLS2: Layout = {
  id: 'symbols2',
  rows: [
    { keys: ['[', ']', '{', '}', '#', '%', '^', '*', '+', '='].map((c) => char(c)) },
    { keys: ['_', '\\', '|', '~', '<', '>', '€', '£', '¥', '•'].map((c) => char(c)) },
    {
      keys: [
        toLayer('symbols', '123', 1.5, 'Numbers'),
        ...['.', ',', '?', '!', "'"].map((c) => ({ ...char(c), width: 1.4 })),
        backspace,
      ],
    },
    bottomRow(toLayer('letters', 'ABC', 1.25, 'Letters')),
  ],
};

export const NUMBER_ROW: KeyRow = { keys: [...'1234567890'].map((d) => char(d)) };

export const LAYOUTS: Readonly<Record<LayerId, Layout>> = {
  letters: QWERTY,
  symbols: SYMBOLS,
  symbols2: SYMBOLS2,
};

/** Resolve the rows to render for a layer, optionally with the number row (FR-KB-09). */
export function rowsFor(layer: LayerId, opts: { numberRow: boolean }): KeyRow[] {
  const layout = LAYOUTS[layer];
  return opts.numberRow && layer === 'letters' ? [NUMBER_ROW, ...layout.rows] : layout.rows;
}

/** O(1) key lookup by id across all layers (used by the delegated pointer handler). */
const KEY_INDEX: ReadonlyMap<string, KeySpec> = (() => {
  const map = new Map<string, KeySpec>();
  for (const layout of [QWERTY, SYMBOLS, SYMBOLS2]) {
    for (const row of layout.rows) for (const key of row.keys) map.set(key.id, key);
  }
  for (const key of NUMBER_ROW.keys) map.set(key.id, key);
  return map;
})();

export function keyById(id: string): KeySpec | undefined {
  return KEY_INDEX.get(id);
}
