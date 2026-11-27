/**
 * Android IME transport (ADR-0002).
 *
 * The native host registers `WebViewCompat.addWebMessageListener(webView, "akNative", setOf("https://ak.local"), …)`.
 * That injects a global `akNative` object with `postMessage(string)` and an `onmessage` handler slot.
 * Native replies through the `JavaScriptReplyProxy` it receives with the first message.
 */
import { EnvelopeBridge } from './envelope-bridge';

interface InjectedNativePort {
  postMessage(message: string): void;
  onmessage: ((event: { data: string }) => void) | null;
}

declare global {
  interface Window {
    akNative?: InjectedNativePort;
  }
}

export function isImeHost(): boolean {
  return typeof window !== 'undefined' && typeof window.akNative?.postMessage === 'function';
}

export class ImeBridge extends EnvelopeBridge {
  readonly kind = 'ime' as const;
  private readonly port: InjectedNativePort;

  constructor() {
    super();
    const port = window.akNative;
    if (!port) throw new Error('ImeBridge: akNative not injected');
    this.port = port;
    this.port.onmessage = (event) => this.receive(event.data);
  }

  protected post(raw: string): void {
    this.port.postMessage(raw);
  }

  override dispose(): void {
    this.port.onmessage = null;
    super.dispose();
  }
}
