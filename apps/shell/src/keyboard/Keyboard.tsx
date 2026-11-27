'use client';
/**
 * <Keyboard/> — the IME surface. Owns one controller + store per mount; panels replace the key grid
 * area so the keyboard height never jumps (FR-UI-09).
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import type { NativeBridge } from '@ak/protocol';
import { KEY_HEIGHT_PX, useSetting } from '@/lib/settings';
import { useStore } from '@/lib/store';
import { AgentBar } from './AgentBar';
import { ShellContext, useShell as useShellCtx, type ShellContextValue } from './context';
import { KeyboardController } from './controller';
import { InsightStrip } from './InsightStrip';
import { KeyGrid } from './KeyGrid';
import { ContextPanel } from './panels/ContextPanel';
import { AgentsPanel, ClipboardPanel, ConsentPanel, EmojiPanel, SettingsPanel, VoicePanel } from './panels/MiscPanels';
import { ResultPanel } from './panels/ResultPanel';
import { createShellStore } from './shell-store';
import css from './keyboard.module.css';

export function useResolvedTheme(): 'light' | 'dark' {
  const pref = useSetting('theme');
  const [system, setSystem] = useState<'light' | 'dark'>('light');
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const on = () => setSystem(mq.matches ? 'dark' : 'light');
    on();
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);
  return pref === 'system' ? system : pref;
}

export interface KeyboardProps {
  bridge: NativeBridge;
  floating?: boolean;
  /** Force a theme (playground side-by-side); otherwise follows settings. */
  theme?: 'light' | 'dark';
  /** Playground only: route the desktop keyboard into the shell. */
  physicalKeys?: boolean;
}

export function Keyboard({ bridge, floating, theme, physicalKeys }: KeyboardProps) {
  const value = useMemo<ShellContextValue>(() => {
    const store = createShellStore();
    return { store, controller: new KeyboardController(bridge, store) };
  }, [bridge]);

  useEffect(() => value.controller.init(), [value]);

  // The IME window is sized natively; report the rendered height so it always matches (`ui.resize`).
  const boardRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = boardRef.current;
    if (!el || bridge.kind !== 'ime' || typeof ResizeObserver === 'undefined') return;
    let last = 0;
    const ro = new ResizeObserver(() => {
      const height = Math.ceil(el.getBoundingClientRect().height);
      if (height !== last) bridge.send('ui.resize', { height: (last = height) });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [bridge]);

  useEffect(() => {
    if (!physicalKeys) return;
    const c = value.controller;
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (e.metaKey || e.ctrlKey || e.altKey || t?.closest('input, textarea, select, [contenteditable]')) return;
      if (value.store.get().panel !== 'none' && e.key !== 'Escape') return;
      if (e.key === 'Escape') c.closePanel();
      else if (e.key === 'Backspace') c.backspace(e.repeat && e.shiftKey ? 'word' : 'grapheme');
      else if (e.key === 'Enter') c.tap('enter');
      else if (e.key === ' ') c.tap('space');
      else if (e.key === 'ArrowLeft') c.moveCursor(-1);
      else if (e.key === 'ArrowRight') c.moveCursor(1);
      else if (e.key.length === 1 || [...e.key].length === 1) c.insertText(e.key);
      else return;
      e.preventDefault();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [physicalKeys, value]);

  const resolved = useResolvedTheme();
  const height = useSetting('height');
  // Panels take over the strip area too, unless Autopilot is on: its status + Stop must stay visible.
  const stripVisible = useStore(value.store, (s) => s.panel === 'none' || s.autonomy.enabled);

  return (
    <ShellContext.Provider value={value}>
      <div
        ref={boardRef}
        className={`${css.board} ${floating ? css.floating : ''}`}
        data-ak-theme={theme ?? resolved}
        data-strip={stripVisible}
        style={{ ['--key-h' as string]: `${KEY_HEIGHT_PX[height]}px` }}
      >
        <AgentBar />
        {stripVisible ? <InsightStrip /> : null}
        <Body />
        <Toast />
      </div>
    </ShellContext.Provider>
  );
}

function Body() {
  const { store } = useShellCtx();
  const panel = useStore(store, (s) => s.panel);
  switch (panel) {
    case 'agents':
      return <AgentsPanel />;
    case 'context':
      return <ContextPanel />;
    case 'result':
      return <ResultPanel />;
    case 'voice':
      return <VoicePanel />;
    case 'clipboard':
      return <ClipboardPanel />;
    case 'settings':
      return <SettingsPanel />;
    case 'emoji':
      return <EmojiPanel />;
    case 'consent':
      return <ConsentPanel />;
    default:
      return <KeyGrid />;
  }
}

function Toast() {
  const { store } = useShellCtx();
  const toast = useStore(store, (s) => s.toast);
  return toast ? (
    <div className={css.toast} role="status">
      {toast}
    </div>
  ) : null;
}

