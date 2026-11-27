/**
 * Agent avatar monogram. Avatars are text in the UI font rather than symbol/emoji glyphs, which
 * render inconsistently (or not at all) across OEM fonts and break the monochrome system.
 */
const LETTERS = /^[\p{L}\p{N}]{1,2}$/u;

export function agentMonogram(agent: { icon?: string; name: string }): string {
  const custom = agent.icon?.trim() ?? '';
  if (LETTERS.test(custom)) return custom;
  const words = agent.name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  const initials = words
    .slice(0, 2)
    .map((w) => [...w][0] ?? '')
    .join('');
  return initials.toUpperCase();
}
