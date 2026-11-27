'use client';
import { useEffect, useState } from 'react';
import type { NativeBridge } from '@ak/protocol';
import { hydrateAgents } from '@/agents/repository';
import { loadBridge } from '@/bridge';
import { Keyboard } from '@/keyboard/Keyboard';
import { hydrateSettings } from '@/lib/settings';

export function ImeSurface() {
  const [bridge, setBridge] = useState<NativeBridge | null>(null);
  useEffect(() => {
    hydrateSettings();
    hydrateAgents();
    document.body.style.background = 'transparent';
    let alive = true;
    loadBridge().then((b) => alive && setBridge(b), (e: unknown) => console.error(e));
    return () => {
      alive = false;
    };
  }, []);
  if (!bridge) return null;
  return (
    <div style={{ position: 'fixed', inset: 'auto 0 0 0' }}>
      <Keyboard bridge={bridge} />
    </div>
  );
}
