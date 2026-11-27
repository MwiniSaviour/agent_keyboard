/**
 * Companion-app transport: a Capacitor plugin named "AgentKit" (Phase 2) that forwards envelopes
 * to the `:agent` service over AIDL (ADR-0003). `@capacitor/core` is imported lazily so the IME
 * route never pays for it (NFR-P-14).
 */
import { EnvelopeBridge } from './envelope-bridge';

interface AgentKitPlugin {
  post(options: { message: string }): Promise<void>;
  addListener(event: 'message', cb: (data: { message: string }) => void): Promise<{ remove(): Promise<void> }>;
}

export function isCapacitorNative(): boolean {
  if (typeof window === 'undefined') return false;
  const cap = (window as unknown as { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor;
  return Boolean(cap?.isNativePlatform?.());
}

export class CapacitorBridge extends EnvelopeBridge {
  readonly kind = 'capacitor' as const;
  private plugin: AgentKitPlugin | null = null;
  private queue: string[] = [];
  private listener: { remove(): Promise<void> } | null = null;

  constructor() {
    super();
    void this.init();
  }

  private async init(): Promise<void> {
    const { registerPlugin } = await import('@capacitor/core');
    this.plugin = registerPlugin<AgentKitPlugin>('AgentKit');
    this.listener = await this.plugin.addListener('message', ({ message }) => this.receive(message));
    const queued = this.queue;
    this.queue = [];
    queued.forEach((m) => void this.plugin?.post({ message: m }));
  }

  protected post(raw: string): void {
    if (!this.plugin) this.queue.push(raw);
    else void this.plugin.post({ message: raw });
  }

  override dispose(): void {
    void this.listener?.remove();
    super.dispose();
  }
}
