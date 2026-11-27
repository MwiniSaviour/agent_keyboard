'use client';
import { createContext, useContext } from 'react';
import { useStore } from '@/lib/store';
import type { KeyboardController } from './controller';
import type { ShellState, ShellStore } from './shell-store';

export interface ShellContextValue {
  controller: KeyboardController;
  store: ShellStore;
}

export const ShellContext = createContext<ShellContextValue | null>(null);

export function useShell(): ShellContextValue {
  const ctx = useContext(ShellContext);
  if (!ctx) throw new Error('useShell must be used inside <Keyboard>');
  return ctx;
}

export function useShellState<S>(selector: (s: ShellState) => S): S {
  return useStore(useShell().store, selector);
}
