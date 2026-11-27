/**
 * Replays the shared bridge contract (packages/protocol/fixtures/contract.json, SPEC §19.1) through the
 * real EnvelopeBridge. The Kotlin suite replays the same file against the native codec, so the two
 * transports cannot drift apart silently.
 */
import { describe, expect, it, vi } from 'vitest';
import { BridgeError, type NativeEventType, type ShellCommandType, type ShellRequestType } from '@ak/protocol';
import contract from '@ak/protocol/fixtures/contract.json';
import { EnvelopeBridge } from './envelope-bridge';

class LoopbackBridge extends EnvelopeBridge {
  readonly kind = 'ime' as const;
  readonly sent: Array<{ v: number; id: string; type: string; ts: number; payload: unknown }> = [];
  protected post(raw: string): void {
    this.sent.push(JSON.parse(raw));
  }
  deliver(raw: unknown): void {
    this.receive(raw);
  }
}

describe('bridge contract (TS side)', () => {
  it('serialises every command exactly as the fixture expects', () => {
    const b = new LoopbackBridge();
    for (const [type, payload] of Object.entries(contract.commands)) {
      b.send(type as ShellCommandType, payload as never);
      const env = b.sent.at(-1)!;
      expect(env).toMatchObject({ v: contract.protocol, type, payload });
      expect(env.id).toMatch(/^s:\d+$/);
    }
  });

  it('dispatches every native event payload unchanged', () => {
    const b = new LoopbackBridge();
    let n = 0;
    for (const [type, payload] of Object.entries(contract.events)) {
      const handler = vi.fn();
      b.on(type as NativeEventType, handler);
      b.deliver(JSON.stringify({ v: 1, id: `n:${++n}`, type, ts: 1, payload }));
      expect(handler).toHaveBeenCalledWith(payload);
    }
  });

  it('resolves every request with the fixture response', async () => {
    const b = new LoopbackBridge();
    for (const [type, { req, res }] of Object.entries(contract.requests)) {
      const p = b.request(type as ShellRequestType, req as never);
      const env = b.sent.at(-1)!;
      expect(env.payload).toEqual(req);
      b.deliver(JSON.stringify({ v: 1, id: 'n:1', type: `${type}.result`, ts: 1, re: env.id, payload: res }));
      await expect(p).resolves.toEqual(res);
    }
  });

  it('rejects with BridgeError on native error replies', async () => {
    const b = new LoopbackBridge();
    const p = b.request('context.snapshot', { scope: 'screen' });
    const id = b.sent.at(-1)!.id;
    b.deliver(JSON.stringify({ v: 1, id: 'n:9', type: 'error', ts: 1, re: id, payload: { code: 'secure_field', message: 'Not available in secure fields' } }));
    await expect(p).rejects.toBeInstanceOf(BridgeError);
  });

  it('ignores envelopes from another protocol major', () => {
    const b = new LoopbackBridge();
    const handler = vi.fn();
    b.on('editor.finish', handler);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    b.deliver(JSON.stringify({ v: 2, id: 'n:1', type: 'editor.finish', ts: 1, payload: {} }));
    expect(handler).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it('catalog in the fixture lists exactly the fixture keys', () => {
    expect(contract.catalog.commands).toEqual(Object.keys(contract.commands));
    expect(contract.catalog.requests).toEqual(Object.keys(contract.requests));
    expect(contract.catalog.events).toEqual(Object.keys(contract.events));
  });
});
