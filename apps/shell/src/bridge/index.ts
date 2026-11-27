/**
 * Bridge selection. Exactly one bridge per page lifetime:
 *  - IME WebView (akNative injected)        → ImeBridge
 *  - Capacitor native companion app         → CapacitorBridge
 *  - anything else (browser, tests)         → WebMockBridge (lazy chunk)
 *
 * The mock is only reachable through a dynamic import, so it lives in its own chunk that native
 * hosts never fetch. Production builds additionally set NEXT_PUBLIC_AK_MOCK=0, which removes the
 * import entirely (NFR-P-14: /ime ≤ 180 KB gzip).
 */
import type { NativeBridge } from '@ak/protocol';
import { CapacitorBridge, isCapacitorNative } from './capacitor-bridge';
import { ImeBridge, isImeHost } from './ime-bridge';
import type { WebMockBridge } from './web-mock/bridge';

const MOCK_ENABLED = process.env.NEXT_PUBLIC_AK_MOCK !== '0';

let bridge: NativeBridge | null = null;
let pending: Promise<NativeBridge> | null = null;

async function create(): Promise<NativeBridge> {
  if (isImeHost()) return new ImeBridge();
  if (isCapacitorNative()) return new CapacitorBridge();
  if (!MOCK_ENABLED) throw new Error('No native host available and the web mock is disabled');
  const { WebMockBridge } = await import('./web-mock/bridge');
  return new WebMockBridge();
}

export function loadBridge(): Promise<NativeBridge> {
  if (bridge) return Promise.resolve(bridge);
  pending ??= create().then((b) => (bridge = b));
  return pending;
}

/** The mock host (simulated app) — only available in the web playground. */
export async function loadMockBridge(): Promise<WebMockBridge | null> {
  const b = await loadBridge();
  return 'host' in b ? (b as WebMockBridge) : null;
}
