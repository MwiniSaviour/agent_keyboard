'use client';
/** Agents switcher, Clipboard/Quick replies, Settings, Emoji, Voice and Autonomy consent panels. */
import { useState } from 'react';
import type { AutonomyLevel } from '@ak/protocol';
import { updateSetting, useSetting, type KeyboardHeight, type KeyboardSettings } from '@/lib/settings';
import { BackspaceIcon, CheckIcon, CloseIcon, MicIcon, PinIcon, PlusIcon, ShieldIcon } from '@/ui/icons';
import { agentMonogram } from '@/agents/monogram';
import { useShell, useShellState } from '../context';
import css from '../keyboard.module.css';

function PanelHead({ title, icon }: { title: string; icon?: React.ReactNode }) {
  const { controller } = useShell();
  return (
    <header className={css.panelHead}>
      {icon}
      <span className={css.grow}>{title}</span>
      <button type="button" className={css.btnIcon} onClick={() => controller.closePanel()} aria-label="Close">
        <CloseIcon size={18} />
      </button>
    </header>
  );
}

// ───────────── Agents (FR-UI-03) ─────────────

export function AgentsPanel() {
  const { controller } = useShell();
  const agents = useShellState((s) => s.agents);
  const active = useShellState((s) => s.activeAgentId);
  return (
    <section className={css.panel} role="dialog" aria-label="Choose agent">
      <PanelHead title="Agents" />
      <div className={css.panelBody}>
        <div className={css.list} role="listbox" aria-label="Agents">
          {agents.map((a) => (
            <button
              type="button"
              key={a.id}
              role="option"
              aria-selected={a.id === active}
              aria-current={a.id === active}
              className={css.listItem}
              onClick={() => controller.selectAgent(a.id)}
            >
              <span className={css.avatar} aria-hidden>
                {agentMonogram(a)}
              </span>
              <span className={css.itemText}>
                <span className={css.itemTitle}>{a.name}</span>
                <span className={css.itemSub} style={{ display: 'block' }}>
                  {a.tagline}
                </span>
              </span>
              {a.id === active ? <CheckIcon size={18} /> : null}
            </button>
          ))}
          <button type="button" className={css.listItem} onClick={() => controller.bridge.send('app.open', { route: '/agents/new/' })}>
            <span className={css.avatar}>
              <PlusIcon size={18} />
            </span>
            <span className={css.itemText}>
              <span className={css.itemTitle}>New agent</span>
              <span className={css.itemSub} style={{ display: 'block' }}>
                Sales, support, tutor… built for your context
              </span>
            </span>
          </button>
        </div>
      </div>
    </section>
  );
}

// ───────────── Clipboard + Quick replies (FR-KB-11; canned messages → agent quick replies) ─────────────

export function ClipboardPanel() {
  const { controller } = useShell();
  const [tab, setTab] = useState<'clipboard' | 'quick'>('quick');
  const clipboard = useShellState((s) => s.clipboard);
  const agent = useShellState((s) => s.agents.find((a) => a.id === s.activeAgentId));
  const quick = agent?.quickReplies ?? [];
  return (
    <section className={css.panel} role="dialog" aria-label="Clipboard and quick replies">
      <header className={css.tabHead}>
        <div className={css.tabList} role="tablist" aria-label={`${agent?.name ?? 'Agent'} quick replies and clipboard`}>
          <button type="button" role="tab" className={css.chip} aria-selected={tab === 'quick'} aria-pressed={tab === 'quick'} onClick={() => setTab('quick')}>
            {agent?.name ?? 'Agent'} replies
          </button>
          <button type="button" role="tab" className={css.chip} aria-selected={tab === 'clipboard'} aria-pressed={tab === 'clipboard'} onClick={() => setTab('clipboard')}>
            Clipboard
          </button>
        </div>
        <button type="button" className={css.btnIcon} onClick={() => controller.closePanel()} aria-label="Close">
          <CloseIcon size={18} />
        </button>
      </header>
      <div className={css.panelBody}>
        <div className={css.list}>
          {tab === 'quick' ? (
            quick.length ? (
              quick.map((q) => (
                <button type="button" key={q.id} className={css.listItem} onClick={() => (controller.insertSmart(q.text), controller.closePanel())}>
                  <span className={css.itemText}>
                    <span className={css.itemTitle}>{q.title}</span>
                    <span className={css.itemSub} style={{ display: 'block' }}>
                      {q.text}
                    </span>
                  </span>
                </button>
              ))
            ) : (
              <p className={css.itemSub} style={{ whiteSpace: 'normal' }}>
                {agent?.name ?? 'This agent'} has no quick replies yet. Add one-tap messages in the agent editor.
              </p>
            )
          ) : (
            clipboard.map((c) => (
              <div key={c.id} className={css.listItem}>
                <button
                  type="button"
                  className={css.itemText}
                  style={{ textAlign: 'left' }}
                  onClick={() => {
                    controller.bridge.send('clipboard.paste', { id: c.id });
                    controller.closePanel();
                  }}
                >
                  <span className={css.itemTitle} style={{ fontWeight: 500 }}>
                    {c.text}
                  </span>
                </button>
                {c.pinned ? <PinIcon size={16} aria-label="Pinned" /> : null}
              </div>
            ))
          )}
        </div>
      </div>
    </section>
  );
}

// ───────────── Quick settings (FR-KB-09) ─────────────

function Toggle({ k, label }: { k: keyof KeyboardSettings; label: string }) {
  const v = useSetting(k) as boolean;
  return (
    <div className={css.setRow}>
      <span>{label}</span>
      <button type="button" role="switch" aria-checked={v} aria-label={label} className={css.toggle} data-on={v} onClick={() => updateSetting(k, !v as never)} />
    </div>
  );
}

const HEIGHT_SHORT: Record<KeyboardHeight, string> = { compact: 'S', normal: 'M', tall: 'L', xl: 'XL' };
const HEIGHT_NAME: Record<KeyboardHeight, string> = { compact: 'Compact', normal: 'Normal', tall: 'Tall', xl: 'Extra tall' };

export function SettingsPanel() {
  const { controller } = useShell();
  const theme = useSetting('theme');
  const height = useSetting('height');
  const level = useShellState((s) => s.autonomy.level);
  return (
    <section className={css.panel} role="dialog" aria-label="Keyboard settings">
      <PanelHead title="Keyboard" />
      <div className={css.panelBody}>
        <div className={css.setRow}>
          <span>Theme</span>
          <div className={css.seg}>
            {(['light', 'dark', 'system'] as const).map((t) => (
              <button type="button" key={t} aria-pressed={theme === t} onClick={() => updateSetting('theme', t)}>
                {t[0]!.toUpperCase() + t.slice(1)}
              </button>
            ))}
          </div>
        </div>
        <div className={css.setRow}>
          <span>Height</span>
          <div className={css.seg}>
            {(['compact', 'normal', 'tall', 'xl'] as KeyboardHeight[]).map((h) => (
              <button type="button" key={h} aria-pressed={height === h} aria-label={HEIGHT_NAME[h]} title={HEIGHT_NAME[h]} onClick={() => updateSetting('height', h)}>
                {HEIGHT_SHORT[h]}
              </button>
            ))}
          </div>
        </div>
        <div className={css.setRow}>
          <span>Autopilot level</span>
          <div className={css.seg}>
            {(['suggest', 'draft', 'auto'] as AutonomyLevel[]).map((l) => (
              <button type="button" key={l} aria-pressed={level === l} onClick={() => controller.setAutonomyLevel(l)}>
                {l[0]!.toUpperCase() + l.slice(1)}
              </button>
            ))}
          </div>
        </div>
        <Toggle k="smartSuggestions" label="Smart suggestions" />
        <Toggle k="autoCapitalize" label="Auto-capitalize" />
        <Toggle k="doubleSpacePeriod" label="Double-space period" />
        <Toggle k="numberRow" label="Number row" />
        <Toggle k="keyHints" label="Long-press hints" />
        <Toggle k="haptics" label="Haptic feedback" />
        <Toggle k="keySounds" label="Key sounds" />
        <Toggle k="voiceRefine" label="Clean up dictation" />
        <button type="button" className={css.btnGhost} style={{ marginTop: 12 }} onClick={() => controller.bridge.send('app.open', { route: '/settings/' })}>
          All settings
        </button>
      </div>
    </section>
  );
}

// ───────────── Emoji (FR-KB-10) ─────────────

const EMOJI: Record<string, string[]> = {
  Smileys: ['😀', '😂', '🥹', '😊', '😍', '🥰', '😘', '😎', '🤩', '🤔', '🙃', '😅', '😭', '😤', '😴', '🤗', '🙄', '😬', '🥲', '😇', '🤝', '👍', '👏', '🙏'],
  Hearts: ['❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '🤍', '💕', '💯', '✨', '🔥', '🎉', '🌸', '⭐', '☀️'],
  Things: ['📦', '🚚', '💳', '💰', '🛍️', '📱', '💻', '📅', '⏰', '📍', '✅', '❌', '📝', '📎', '🎁', '🧴'],
};

export function EmojiPanel() {
  const { controller } = useShell();
  const [cat, setCat] = useState('Smileys');
  return (
    <section className={css.panel} role="dialog" aria-label="Emoji">
      <div className={css.chipRow} style={{ paddingTop: 10 }}>
        <button type="button" className={css.chip} onClick={() => controller.closePanel()} aria-label="Back to letters">
          ABC
        </button>
        {Object.keys(EMOJI).map((c) => (
          <button type="button" key={c} className={css.chip} aria-pressed={cat === c} onClick={() => setCat(c)}>
            {c}
          </button>
        ))}
        <button type="button" className={css.chip} onClick={() => controller.backspace('grapheme')} aria-label="Delete">
          <BackspaceIcon size={16} />
        </button>
      </div>
      <div className={css.panelBody}>
        <div className={css.emojiGrid}>
          {EMOJI[cat]!.map((e) => (
            <button type="button" key={e} className={css.emoji} onClick={() => controller.insertText(e)} aria-label={e}>
              {e}
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}

// ───────────── Voice (FR-UI-05, FR-VO-01) ─────────────

export function VoicePanel() {
  const { controller } = useShell();
  const voice = useShellState((s) => s.voice);
  const bars = Array.from({ length: 14 }, (_, i) => {
    const phase = Math.sin(i * 1.7 + voice.level * 9) * 0.5 + 0.5;
    return 8 + Math.round(voice.level * 52 * (0.35 + phase * 0.65));
  });
  return (
    <section className={css.voice} role="dialog" aria-label="Voice typing">
      <div className={css.wave} aria-hidden>
        {bars.map((h, i) => (
          <i key={i} className={css.bar1} style={{ height: h }} />
        ))}
      </div>
      <p className={css.transcript} aria-live="polite">
        {voice.partial || <em>Listening… speak naturally</em>}
      </p>
      <div className={css.voiceControls}>
        <button type="button" className={css.btnIcon} onClick={() => controller.closePanel()} aria-label="Cancel dictation">
          <CloseIcon size={18} />
        </button>
        <button type="button" className={css.micBig} onClick={() => controller.stopVoice()} aria-label="Finish dictation">
          <MicIcon size={28} />
        </button>
        <button type="button" className={css.btnIcon} onClick={() => controller.stopVoice()} aria-label="Insert text">
          <CheckIcon size={18} />
        </button>
      </div>
      <span className={css.itemSub}>Offline · simulated in the browser</span>
    </section>
  );
}

// ───────────── Autonomy consent (FR-AU-02) ─────────────

export function ConsentPanel() {
  const { controller } = useShell();
  const agent = useShellState((s) => s.agents.find((a) => a.id === s.activeAgentId));
  const canSend = agent?.autonomyCeiling === 'auto';
  const [level, setLevel] = useState<AutonomyLevel>('draft');
  const name = agent?.name ?? 'Your agent';
  return (
    <section className={css.panel} role="dialog" aria-label="Turn on Autopilot">
      <PanelHead title="Turn on Autopilot" icon={<ShieldIcon size={18} />} />
      <div className={css.panelBody}>
        <p className={css.consentLead}>
          <b>{name}</b> replies to chats and fills forms for you while the keyboard is open, on this device.
        </p>
        <div className={css.levelPick} role="group" aria-label="What Autopilot may do">
          <button type="button" aria-pressed={level === 'draft'} onClick={() => setLevel('draft')}>
            Draft
            <small>Writes it, you send</small>
          </button>
          <button type="button" aria-pressed={level === 'auto'} disabled={!canSend} onClick={() => setLevel('auto')}>
            Auto-send
            <small>{canSend ? 'Sends for you' : `${name} only drafts`}</small>
          </button>
        </div>
        <p className={css.bullets}>
          Touch any key to take over. It never acts in password, OTP or payment fields, never submits forms, and asks you
          about money or commitments.
        </p>
      </div>
      <footer className={css.panelFoot}>
        <button type="button" className={css.btnGhost} onClick={() => controller.closePanel()}>
          Not now
        </button>
        <button type="button" className={css.btnPrimary} onClick={() => controller.grantConsent(level)}>
          Turn on
        </button>
      </footer>
    </section>
  );
}
