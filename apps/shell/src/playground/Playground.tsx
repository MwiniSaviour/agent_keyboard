'use client';
/**
 * Web playground: a simulated phone (host app + keyboard) driven entirely by the WebMockBridge, so
 * the whole shell — typing, context lens, agents, voice, autonomy — can be exercised without a device.
 */
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { hydrateAgents } from '@/agents/repository';
import { loadMockBridge } from '@/bridge';
import type { WebMockBridge } from '@/bridge/web-mock/bridge';
import type { ScenarioId } from '@/bridge/web-mock/scenarios';
import { Keyboard, useResolvedTheme } from '@/keyboard/Keyboard';
import { hydrateSettings, updateSetting } from '@/lib/settings';
import { useStore } from '@/lib/store';
import { HostApp } from './HostApp';
import css from './playground.module.css';

const TABS: Array<{ id: ScenarioId; label: string; hint: string }> = [
  { id: 'chat', label: 'Chat', hint: 'Turn AUTO on, then let Jane send messages — the Sales Bot replies, and escalates the PIN request.' },
  { id: 'form', label: 'Form', hint: 'Open the lens → Fill form. Your agent fills from your on-device profile; the password stays yours.' },
  { id: 'article', label: 'Essay', hint: 'Tap “exigent”, then the lens → Define word, or ask for a summary of the screen.' },
];

export function Playground() {
  const [bridge, setBridge] = useState<WebMockBridge | null>(null);
  useEffect(() => {
    hydrateSettings();
    hydrateAgents();
    let alive = true;
    void loadMockBridge().then((b) => alive && setBridge(b));
    return () => {
      alive = false;
    };
  }, []);
  if (!bridge) return <div className={css.loading}>Loading…</div>;
  return <PlaygroundInner bridge={bridge} />;
}

function PlaygroundInner({ bridge }: { bridge: WebMockBridge }) {
  const host = bridge.host;
  const scenario = useStore(host.store, (s) => s.scenarioId);
  const queue = useStore(host.store, (s) => s.incomingQueue.length);
  const typing = useStore(host.store, (s) => s.typingIndicator);
  const theme = useResolvedTheme();
  const tab = TABS.find((t) => t.id === scenario)!;

  return (
    <main className={css.page} data-theme={theme}>
      <aside className={css.side}>
        <div className={css.brand}>
          <span className={css.logo}>●</span> Agent Keyboard
          <span className={css.badge}>Shell · Phase 1</span>
        </div>
        <p className={css.lede}>
          An offline AI keyboard that understands what’s on your screen and can reply or fill forms for you.
          This page runs the real keyboard shell against a simulated phone.
        </p>

        <div className={css.tabs} role="tablist" aria-label="Scenario">
          {TABS.map((t) => (
            <button type="button" key={t.id} role="tab" aria-selected={scenario === t.id} className={css.tab} onClick={() => host.setScenario(t.id)}>
              {t.label}
            </button>
          ))}
        </div>
        <p className={css.hint}>{tab.hint}</p>

        {scenario === 'chat' ? (
          <button type="button" className={css.primary} disabled={queue === 0 || typing} onClick={() => host.scheduleIncoming(900)}>
            {queue === 0 ? 'No more messages from Jane' : typing ? 'Jane is typing…' : `Jane sends a message (${queue} left)`}
          </button>
        ) : null}

        <div className={css.row}>
          <span>Theme</span>
          <div className={css.seg}>
            {(['light', 'dark'] as const).map((t) => (
              <button type="button" key={t} aria-pressed={theme === t} onClick={() => updateSetting('theme', t)}>
                {t === 'light' ? 'Light' : 'Dark'}
              </button>
            ))}
          </div>
        </div>

        <nav className={css.links} aria-label="Companion app">
          <Link href="/agents/">Agents</Link>
          <Link href="/agents/new/">Create agent</Link>
          <Link href="/models/">Models</Link>
          <Link href="/settings/">Settings</Link>
          <Link href="/ime/">/ime surface</Link>
        </nav>

        <ul className={css.facts}>
          <li>Everything here runs locally — no network calls.</li>
          <li>The phone keyboard works with touch; your physical keyboard types too.</li>
          <li>Touching any key while Autopilot is acting takes over immediately.</li>
        </ul>
      </aside>

      <section className={css.stage}>
        <div className={css.phone}>
          <div className={css.screen}>
            <HostApp host={host} />
          </div>
          <Keyboard bridge={bridge} physicalKeys />
        </div>
      </section>
    </main>
  );
}
