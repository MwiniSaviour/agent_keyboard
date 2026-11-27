import { describe, expect, it } from 'vitest';
import { agentMonogram } from './monogram';

describe('agentMonogram', () => {
  it('keeps a valid 1–2 character custom monogram', () => {
    expect(agentMonogram({ icon: 'Aa', name: 'Vocabulary' })).toBe('Aa');
  });
  it('derives initials when the icon is empty or a symbol glyph', () => {
    expect(agentMonogram({ icon: '', name: 'Sales Bot' })).toBe('SB');
    expect(agentMonogram({ icon: '●', name: 'General' })).toBe('G');
    expect(agentMonogram({ icon: '✦', name: 'customer care desk' })).toBe('CC');
  });
  it('never returns an empty avatar', () => {
    expect(agentMonogram({ icon: '', name: '   ' })).toBe('?');
  });
});
