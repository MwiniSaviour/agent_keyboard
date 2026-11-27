/**
 * Keyboard settings (FR-KB-09). Persisted in the origin's localStorage, which the IME and the
 * companion share (same `https://ak.local` origin, same app profile). The few settings the native IME
 * acts on itself are mirrored to it with `settings.set {key: "keyboard.<name>"}` (see
 * `NATIVE_SETTINGS`): haptics and key sounds are performed natively, and the native fallback keyboard
 * follows the chosen theme.
 */
import type { ThemeMode } from '@ak/protocol';
import { createStore, useStore } from './store';

export type KeyboardHeight = 'compact' | 'normal' | 'tall' | 'xl';

export interface KeyboardSettings {
  theme: ThemeMode;
  height: KeyboardHeight;
  numberRow: boolean;
  keyHints: boolean;
  keySounds: boolean;
  haptics: boolean;
  autoCapitalize: boolean;
  doubleSpacePeriod: boolean;
  smartSuggestions: boolean;
  longPressMs: number;
  voiceRefine: boolean;
}

export const DEFAULT_SETTINGS: KeyboardSettings = {
  theme: 'light',
  height: 'normal',
  numberRow: false,
  keyHints: true,
  keySounds: false,
  haptics: true,
  autoCapitalize: true,
  doubleSpacePeriod: true,
  smartSuggestions: true,
  longPressMs: 300,
  voiceRefine: true,
};

/** Key height in px per setting; everything else in the grid derives from this. */
export const KEY_HEIGHT_PX: Record<KeyboardHeight, number> = { compact: 40, normal: 46, tall: 52, xl: 58 };

const STORAGE_KEY = 'ak.settings.v1';

/** Settings the native IME host needs (sent as `keyboard.<name>`). Keep in sync with ImeController. */
export const NATIVE_SETTINGS = ['theme', 'haptics', 'keySounds'] as const satisfies ReadonlyArray<keyof KeyboardSettings>;

export const settingsStore = createStore<KeyboardSettings>({ ...DEFAULT_SETTINGS });

/**
 * System dark mode as reported by the native IME (`theme.update`). Null until native speaks; the
 * WebView's `prefers-color-scheme` is the fallback, but inside an IME it does not reliably follow the
 * system night mode, so native wins when present.
 */
export const systemThemeStore = createStore<{ dark: boolean | null }>({ dark: null });

function readStored(raw: string | null): KeyboardSettings | null {
  if (!raw) return null;
  try {
    return { ...DEFAULT_SETTINGS, ...(JSON.parse(raw) as Partial<KeyboardSettings>) };
  } catch {
    return null; // corrupted storage → keep current
  }
}

let hydrated = false;
/** Call once on the client after mount (avoids SSR/hydration mismatch in the static export). */
export function hydrateSettings(): void {
  if (hydrated || typeof window === 'undefined') return;
  hydrated = true;
  try {
    const stored = readStored(window.localStorage.getItem(STORAGE_KEY));
    if (stored) settingsStore.set(stored);
  } catch {
    /* storage unavailable → defaults */
  }
  // A change made in the companion app reaches the long-lived IME page without a reload.
  window.addEventListener('storage', (e) => {
    if (e.key !== STORAGE_KEY) return;
    const stored = readStored(e.newValue);
    if (stored) settingsStore.set(stored);
  });
  settingsStore.subscribe(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(settingsStore.get()));
    } catch {
      /* quota / private mode */
    }
  });
}

export function updateSetting<K extends keyof KeyboardSettings>(key: K, value: KeyboardSettings[K]): void {
  settingsStore.set({ [key]: value } as Partial<KeyboardSettings>);
}

export function useSetting<K extends keyof KeyboardSettings>(key: K): KeyboardSettings[K] {
  return useStore(settingsStore, (s) => s[key]);
}
