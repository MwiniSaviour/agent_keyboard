'use client';
/** Top bar per the reference: [● Agent ▾] AUTO (toggle) ON · Lens · Tasks/Clipboard · Settings (FR-UI-01). */
import { ChevronDown, ClipboardCheckIcon, GearIcon, LensIcon } from '@/ui/icons';
import { useShell, useShellState } from './context';
import css from './keyboard.module.css';

export function AgentBar() {
  const { controller } = useShell();
  const agentName = useShellState((s) => s.agents.find((a) => a.id === s.activeAgentId)?.name ?? 'General');
  const autoOn = useShellState((s) => s.autonomy.enabled);
  const live = useShellState((s) => s.autonomy.enabled && ['planning', 'review', 'typing', 'sending'].includes(s.autonomy.phase));
  const panel = useShellState((s) => s.panel);
  const secure = useShellState((s) => s.field?.sensitive ?? false);

  return (
    <div className={css.bar}>
      <button
        type="button"
        className={css.agentPill}
        onClick={() => controller.openPanel('agents')}
        aria-haspopup="dialog"
        aria-expanded={panel === 'agents'}
        aria-label={`Active agent: ${agentName}. Switch agent`}
      >
        <i className={`${css.dot} ${live ? css.dotLive : ''}`} />
        <span>{agentName}</span>
        <ChevronDown size={16} />
      </button>

      <button
        type="button"
        className={css.auto}
        onClick={() => controller.toggleAutonomy()}
        disabled={secure}
        role="switch"
        aria-checked={autoOn}
        aria-label="Autonomous mode"
        style={secure ? { opacity: 0.35 } : undefined}
      >
        <span className={css.autoLabel}>AUTO</span>
        <span className={css.toggle} data-on={autoOn} />
        <span>{autoOn ? 'ON' : 'OFF'}</span>
      </button>

      <div className={css.barIcons}>
        <button
          type="button"
          className={css.iconBtn}
          data-active={panel === 'context'}
          onClick={() => controller.openPanel('context')}
          aria-label="Screen context and actions"
        >
          <LensIcon size={26} />
        </button>
        <button
          type="button"
          className={css.iconBtn}
          data-active={panel === 'clipboard'}
          onClick={() => controller.openPanel('clipboard')}
          aria-label="Clipboard and quick replies"
        >
          <ClipboardCheckIcon size={26} />
        </button>
        <button
          type="button"
          className={css.iconBtn}
          data-active={panel === 'settings'}
          onClick={() => controller.openPanel('settings')}
          aria-label="Keyboard settings"
        >
          <GearIcon size={26} />
        </button>
      </div>
    </div>
  );
}
