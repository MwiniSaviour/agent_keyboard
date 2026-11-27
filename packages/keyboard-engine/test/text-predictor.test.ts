import { describe, expect, it } from 'vitest';
import {
  createDevPredictor,
  currentWord,
  editDistance,
  englishDevLexicon,
  lastGraphemeLength,
  matchCase,
  moveCursor,
  previousWordDeleteLength,
  replaceText,
  rowsFor,
  shouldAutoCapitalize,
  keyById,
} from '../src';

describe('text utils', () => {
  it('currentWord handles apostrophes and punctuation', () => {
    expect(currentWord("hello don't")).toBe("don't");
    expect(currentWord('hello, wor')).toBe('wor');
    expect(currentWord('hello ')).toBe('');
  });

  it('lastGraphemeLength handles surrogate pairs and flags', () => {
    expect(lastGraphemeLength('a😀')).toBe(2);
    expect(lastGraphemeLength('🇬🇭')).toBe(4);
    expect(lastGraphemeLength('')).toBe(0);
  });

  it('previousWordDeleteLength deletes punctuation one at a time', () => {
    expect(previousWordDeleteLength('hello!!')).toBe(1);
    expect(previousWordDeleteLength('hello world')).toBe(5);
  });

  it('shouldAutoCapitalize sentence rules', () => {
    expect(shouldAutoCapitalize('', 'sentences', 'text')).toBe(true);
    expect(shouldAutoCapitalize('Hi. ', 'sentences', 'text')).toBe(true);
    expect(shouldAutoCapitalize('Hi "there." ', 'sentences', 'text')).toBe(true);
    expect(shouldAutoCapitalize('Hi ', 'sentences', 'text')).toBe(false);
    expect(shouldAutoCapitalize('Hi\n', 'sentences', 'multiline')).toBe(true);
    expect(shouldAutoCapitalize('Ama ', 'words', 'name')).toBe(true);
    expect(shouldAutoCapitalize('', 'sentences', 'url')).toBe(false);
  });

  it('matchCase', () => {
    expect(matchCase('tomorrow', 'Tom')).toBe('Tomorrow');
    expect(matchCase('tomorrow', 'TOM')).toBe('TOMORROW');
    expect(matchCase('tomorrow', 'tom')).toBe('tomorrow');
  });
});

describe('editor model', () => {
  it('replaceText scopes', () => {
    const s = { before: 'hello wor', after: ' end', selected: '' };
    expect(replaceText(s, 'word', 'world').before).toBe('hello world');
    expect(replaceText(s, 'field', 'X')).toEqual({ before: 'X', after: '', selected: '' });
  });
  it('moveCursor clamps', () => {
    const s = { before: 'ab', after: 'cd', selected: '' };
    expect(moveCursor(s, -5)).toEqual({ before: '', after: 'abcd', selected: '' });
    expect(moveCursor(s, 1)).toEqual({ before: 'abc', after: 'd', selected: '' });
  });
});

describe('layouts', () => {
  it('QWERTY is the default letters layer with hints', () => {
    const rows = rowsFor('letters', { numberRow: false });
    expect(rows[0]!.keys.map((k) => k.value).join('')).toBe('qwertyuiop');
    expect(keyById('k:q')?.hint).toBe('1');
    expect(keyById('k:m')?.hint).toBe('?');
  });
  it('number row is prepended when enabled', () => {
    expect(rowsFor('letters', { numberRow: true })).toHaveLength(5);
    expect(rowsFor('symbols', { numberRow: true })).toHaveLength(4);
  });
});

describe('dev predictor', () => {
  const p = createDevPredictor(englishDevLexicon());

  it('completes prefixes', () => {
    const s = p.suggest('see you tomo').map((x) => x.text);
    expect(s).toContain('tomorrow');
  });

  it('corrects adjacent-key typos and marks primary', () => {
    const out = p.suggest('the prixe');
    const primary = out.find((s) => s.primary);
    expect(primary?.text).toBe('price');
  });

  it('keeps the typed casing', () => {
    expect(p.suggest('Tomo').map((s) => s.text)).toContain('Tomorrow');
  });

  it('predicts next words after a space', () => {
    expect(p.suggest('thank ').map((s) => s.text)).toContain('you');
  });

  it('capitalises predictions at sentence start', () => {
    expect(p.suggest('').every((s) => s.text[0] === s.text[0]!.toUpperCase())).toBe(true);
  });

  it('learns new words', () => {
    const q = createDevPredictor(englishDevLexicon());
    q.learn('kumasi');
    expect(q.suggest('kuma').map((s) => s.text)).toContain('kumasi');
  });

  it('editDistance weights adjacent keys', () => {
    expect(editDistance('prixe', 'price')).toBe(0.5);
    expect(editDistance('teh', 'the')).toBe(1);
  });
});
