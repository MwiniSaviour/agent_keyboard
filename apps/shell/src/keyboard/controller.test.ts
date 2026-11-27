/**
 * Integration: KeyboardController ↔ WebMockBridge ↔ MockHost, i.e. the same message flow the IME
 * runs over WebMessagePort, executed headlessly.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { fullText } from '@ak/keyboard-engine';
import { WebMockBridge } from '@/bridge/web-mock/bridge';
import { KeyboardController } from './controller';
import { createShellStore } from './shell-store';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

async function setup() {
  const bridge = new WebMockBridge();
  const store = createShellStore();
  const c = new KeyboardController(bridge, store);
  const dispose = c.init();
  await tick(5); // hello → editor.start replay
  return { bridge, store, c, dispose, host: bridge.host };
}

let cleanup: (() => void) | null = null;
afterEach(() => cleanup?.());

describe('KeyboardController', () => {
  it('receives the field on hello and auto-capitalises the first letter', async () => {
    const { c, store, host, dispose } = await setup();
    cleanup = dispose;
    expect(store.get().field?.imeAction).toBe('send');
    for (const ch of 'hi') c.tap(`k:${ch}`);
    expect(fullText(host.editor())).toBe('Hi');
    expect(fullText(store.get().editor)).toBe('Hi'); // optimistic mirror agrees with host
  });

  it('enter performs the field action (send) instead of a newline', async () => {
    const { c, host, dispose } = await setup();
    cleanup = dispose;
    for (const ch of 'ok') c.tap(`k:${ch}`);
    const before = host.store.get().messages.length;
    c.tap('enter');
    expect(host.store.get().messages.length).toBe(before + 1);
    expect(fullText(host.editor())).toBe('');
  });

  it('streams an agent reply and applies it to the field', async () => {
    const { c, store, host, dispose } = await setup();
    cleanup = dispose;
    c.runAgent('reply', 'conversation');
    expect(store.get().panel).toBe('result');
    for (let i = 0; i < 100 && store.get().run?.status !== 'done'; i++) await tick(50);
    expect(store.get().run?.status).toBe('done');
    c.resultAction('apply');
    expect(fullText(host.editor())).toMatch(/Kumasi/);
  });

  it('blocks AI in protected fields', async () => {
    const { c, store, host, dispose } = await setup();
    cleanup = dispose;
    host.setScenario('form');
    host.focus('password');
    await tick(5);
    expect(store.get().field?.sensitive).toBe(true);
    c.runAgent('fill_form', 'screen');
    expect(store.get().run).toBeNull();
    expect(store.get().toast).toMatch(/protected/i);
  });

  it('requires consent before enabling autonomy', async () => {
    const { c, store, dispose } = await setup();
    cleanup = dispose;
    c.toggleAutonomy();
    expect(store.get().panel).toBe('consent');
    expect(store.get().autonomy.enabled).toBe(false);
    c.grantConsent('draft');
    expect(store.get().autonomy.enabled).toBe(true);
  });

  it('never re-grants consent on enable, and a native withdrawal brings the consent sheet back', async () => {
    const { c, store, bridge, dispose } = await setup();
    cleanup = dispose;
    const consentWrites: unknown[] = [];
    const send = bridge.send.bind(bridge);
    bridge.send = ((type: string, payload: { key?: string; value?: unknown }) => {
      if (type === 'settings.set' && payload.key === 'autonomy.consent') consentWrites.push(payload.value);
      return send(type as never, payload as never);
    }) as typeof bridge.send;
    c.toggleAutonomy();
    c.grantConsent('draft');
    expect(consentWrites).toEqual([true]);
    c.toggleAutonomy(); // off
    c.toggleAutonomy(); // on again: no second consent write
    expect(consentWrites).toEqual([true]);
    // Withdrawn elsewhere (companion Settings → :agent): the keyboard follows the native record.
    bridge.send('settings.set', { key: 'autonomy.consent', value: false });
    await tick(5);
    expect(store.get().autonomy.consent).toBe(false);
    expect(store.get().autonomy.enabled).toBe(false);
    c.toggleAutonomy();
    expect(store.get().panel).toBe('consent');
  });

  it('turns live screen on, attaches the frame to visual snapshots and pauses in protected fields', async () => {
    const { c, store, host, bridge, dispose } = await setup();
    cleanup = dispose;
    await expect(bridge.request('context.snapshot', { scope: 'visual' })).rejects.toThrow(/off/i);
    c.setScreenCapture(true);
    expect(store.get().screen.status).toBe('starting');
    await tick(600);
    expect(store.get().screen.status).toBe('live');
    const ctx = await bridge.request('context.snapshot', { scope: 'visual' });
    expect(ctx.rung).toBe(3);
    expect(ctx.images?.[0]?.preview).toMatch(/^data:image\//);
    host.setScenario('form');
    host.focus('password');
    await tick(5);
    expect(store.get().screen.status).toBe('paused');
    c.setScreenCapture(false);
    await tick(5);
    expect(store.get().screen.status).toBe('off');
  });

  it('reads earlier messages with a user-initiated scan', async () => {
    const { c, bridge, dispose } = await setup();
    cleanup = dispose;
    const before = await bridge.request('context.snapshot', { scope: 'screen' });
    const after = await c.scanScreen(before.screenType);
    expect(after.chat!.messages.length).toBeGreaterThan(before.chat!.messages.length);
    expect(after.chat!.messages[0]!.text).toMatch(/shea/i);
    expect(after.scan).toMatchObject({ complete: true, restored: true });
  });

  it('sends text.delete in code points so backspace never eats the character before an emoji', async () => {
    const { c, host, bridge, dispose } = await setup();
    cleanup = dispose;
    const sent: Array<{ before: number; after: number }> = [];
    const send = bridge.send.bind(bridge);
    bridge.send = ((type: string, payload: never) => {
      if (type === 'text.delete') sent.push(payload);
      return send(type as never, payload);
    }) as typeof bridge.send;
    c.insertText('a👍');
    c.backspace('grapheme');
    expect(sent.at(-1)).toEqual({ before: 1, after: 0 }); // 1 code point, though 2 UTF-16 units
    expect(fullText(host.editor())).toBe('a'); // the 'a' before the emoji survived
    c.insertText('👨‍👩‍👧');
    c.backspace('grapheme');
    expect(sent.at(-1)).toEqual({ before: 5, after: 0 }); // ZWJ family: 5 code points, 8 units
    expect(fullText(host.editor())).toBe('a');
  });
});
