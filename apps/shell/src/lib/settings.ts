/**
 * Keyboard settings (FR-KB-09). Persisted locally in the web build; in the IME the native host owns
 * persistence (`settings.set`) so settings survive WebView recreation.
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

export const settingsStore = createStore<KeyboardSettings>({ ...DEFAULT_SETTINGS });

let hydrated = false;
/** Call once on the client after mount (avoids SSR/hydration mismatch in the static export). */
export function hydrateSettings(): void {
  if (hydrated || typeof window === 'undefined') return;
  hydrated = true;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) settingsStore.set({ ...DEFAULT_SETTINGS, ...(JSON.parse(raw) as Partial<KeyboardSettings>) });
  } catch {
    /* corrupted storage → defaults */
  }
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
