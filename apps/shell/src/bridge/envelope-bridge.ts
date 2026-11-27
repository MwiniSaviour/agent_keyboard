/**
 * Shared envelope logic for transports that carry JSON strings (IME WebMessagePort, Capacitor plugin).
 * Handles ids, request/response correlation, timeouts and typed event dispatch (SPEC §8).
 */
import {
  BridgeError,
  PROTOCOL_VERSION,
  type Envelope,
  type NativeBridge,
  type NativeEventType,
  type NativeEvents,
  type ShellCommandType,
  type ShellCommands,
  type ShellRequestType,
  type ShellRequests,
  type Unsubscribe,
} from '@ak/protocol';

interface Pending {
  resolve(v: unknown): void;
  reject(e: unknown): void;
  timer: ReturnType<typeof setTimeout>;
}

export abstract class EnvelopeBridge implements NativeBridge {
  abstract readonly kind: NativeBridge['kind'];
  private seq = 0;
  private readonly pending = new Map<string, Pending>();
  private readonly handlers = new Map<string, Set<(p: never) => void>>();

  /** Transport-specific raw send. */
  protected abstract post(raw: string): void;

  protected nextId(): string {
    this.seq += 1;
    return `s:${this.seq}`;
  }

  send<T extends ShellCommandType>(type: T, payload: ShellCommands[T]): void {
    const env: Envelope<ShellCommands[T]> = { v: PROTOCOL_VERSION, id: this.nextId(), type, ts: Date.now(), payload };
    this.post(JSON.stringify(env));
  }

  request<T extends ShellRequestType>(
    type: T,
    payload: ShellRequests[T]['req'],
    opts: { timeoutMs?: number } = {},
  ): Promise<ShellRequests[T]['res']> {
    const id = this.nextId();
    const env: Envelope = { v: PROTOCOL_VERSION, id, type, ts: Date.now(), payload };
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new BridgeError('timeout', `${type} timed out`));
      }, opts.timeoutMs ?? 5_000);
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject, timer });
      this.post(JSON.stringify(env));
    });
  }

  on<T extends NativeEventType>(type: T, handler: (payload: NativeEvents[T]) => void): Unsubscribe {
    let set = this.handlers.get(type);
    if (!set) this.handlers.set(type, (set = new Set()));
    set.add(handler as (p: never) => void);
    return () => set.delete(handler as (p: never) => void);
  }

  /** Feed a raw message received from native. Malformed messages are dropped (never throw into native). */
  protected receive(raw: unknown): void {
    let env: Envelope;
    try {
      env = (typeof raw === 'string' ? JSON.parse(raw) : raw) as Envelope;
    } catch {
      return;
    }
    if (!env || typeof env.type !== 'string') return;
    if (env.v !== PROTOCOL_VERSION) {
      // Native serves the fallback keyboard on mismatch (SPEC §8.1); nothing sensible to do here.
      console.warn('[bridge] protocol mismatch', env.v);
      return;
    }
    if (env.re) {
      const p = this.pending.get(env.re);
      if (!p) return;
      this.pending.delete(env.re);
      clearTimeout(p.timer);
      if (env.type === 'error') p.reject(new BridgeError('remote', String((env.payload as { message?: string })?.message)));
      else p.resolve(env.payload);
      return;
    }
    this.handlers.get(env.type)?.forEach((h) => {
      try {
        (h as (p: unknown) => void)(env.payload);
      } catch (e) {
        console.error('[bridge] handler failed', env.type, e);
      }
    });
  }

  dispose(): void {
    this.pending.forEach((p) => {
      clearTimeout(p.timer);
      p.reject(new BridgeError('unavailable', 'bridge disposed'));
    });
    this.pending.clear();
    this.handlers.clear();
  }
}
