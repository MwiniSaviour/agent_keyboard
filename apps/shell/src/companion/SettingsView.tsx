'use client';
/** Full settings (FR-KB-09 + privacy controls, SPEC §16). */
import { updateSetting, useSetting, type KeyboardHeight, type KeyboardSettings } from '@/lib/settings';
import { CompanionShell } from './CompanionShell';
import css from './companion.module.css';

function Switch({ k, label, sub }: { k: keyof KeyboardSettings; label: string; sub?: string }) {
  const v = useSetting(k) as boolean;
  return (
    <div className={css.setRow}>
      <span>
        {label}
        {sub ? <div className={css.muted}>{sub}</div> : null}
      </span>
      <button type="button" role="switch" aria-checked={v} aria-label={label} className={css.switch} onClick={() => updateSetting(k, !v as never)} />
    </div>
  );
}

export function SettingsView() {
  return (
    <CompanionShell title="Settings" active="/settings/">
      <Body />
    </CompanionShell>
  );
}

function Body() {
  const theme = useSetting('theme');
  const height = useSetting('height');
  const longPress = useSetting('longPressMs');

  const wipe = () => {
    if (!confirm('Delete everything the keyboard has learned on this device? Agents are kept.')) return;
    try {
      Object.keys(localStorage)
        .filter((k) => k.startsWith('ak.') && k !== 'ak.agents.v1')
        .forEach((k) => localStorage.removeItem(k));
    } catch {
      /* ignore */
    }
    location.reload();
  };

  return (
    <>
      <h2 className={css.h2}>Appearance</h2>
      <section className={css.card}>
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
        <div className={css.setRowStack}>
          <span>Keyboard height</span>
          <div className={css.seg}>
            {(['compact', 'normal', 'tall', 'xl'] as KeyboardHeight[]).map((h) => (
              <button type="button" key={h} aria-pressed={height === h} onClick={() => updateSetting('height', h)}>
                {h === 'xl' ? 'XL' : h[0]!.toUpperCase() + h.slice(1)}
              </button>
            ))}
          </div>
        </div>
        <Switch k="numberRow" label="Number row" />
        <Switch k="keyHints" label="Long-press hints" sub="Show numbers and symbols on keys" />
      </section>

      <h2 className={css.h2}>Typing</h2>
      <section className={css.card}>
        <Switch k="smartSuggestions" label="Smart suggestions" />
        <Switch k="autoCapitalize" label="Auto-capitalize" />
        <Switch k="doubleSpacePeriod" label="Double-space for period" />
        <Switch k="haptics" label="Vibrate on keypress" />
        <Switch k="keySounds" label="Sound on keypress" />
        <div className={css.setRow}>
          <span>
            Long-press delay
            <div className={css.muted}>{longPress} ms</div>
          </span>
          <input type="range" className={css.range} min={200} max={600} step={50} value={longPress} onChange={(e) => updateSetting('longPressMs', Number(e.target.value))} aria-label="Long-press delay" />
        </div>
      </section>

      <h2 className={css.h2}>Voice</h2>
      <section className={css.card}>
        <Switch k="voiceRefine" label="Clean up dictation" sub="Remove filler words and fix punctuation on device" />
      </section>

      <h2 className={css.h2}>Privacy</h2>
      <section className={css.card}>
        <p className={css.help} style={{ marginTop: 0 }}>
          All AI runs on this device. Nothing you type is sent anywhere. Password, OTP and payment fields are never read, learned
          or acted on.
        </p>
        <div className={css.setRow}>
          <span>
            Delete learned data
            <div className={css.muted}>Personal dictionary, memory, clipboard history</div>
          </span>
          <button type="button" className={css.danger} style={{ height: 38 }} onClick={wipe}>
            Delete
          </button>
        </div>
      </section>
    </>
  );
}
