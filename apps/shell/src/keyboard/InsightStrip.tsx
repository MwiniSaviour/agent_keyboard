'use client';
/**
 * Insight strip (FR-UI-02 / FR-UI-10). Priority:
 *   autopilot status > protected-field notice > suggestions (while typing) > insight pill > next-word.
 */
import type { AutonomyLevel, AutonomyPhase } from '@ak/protocol';
import { ChevronRight, LockIcon, StopIcon } from '@/ui/icons';
import { useShell, useShellState } from './context';
import css from './keyboard.module.css';

const PHASE_TEXT: Record<AutonomyPhase, string> = {
  off: '',
  arming: 'Autopilot arming…',
  observing: 'Autopilot · watching this screen',
  planning: 'Autopilot · thinking',
  review: 'Autopilot · checking reply',
  typing: 'Autopilot · writing a reply',
  awaiting_user: 'Draft ready · tap return to send',
  sending: 'Autopilot · sending',
  cooldown: 'Autopilot · sent',
  paused: 'Autopilot paused',
  escalated: 'Needs you',
};

const ORDER: AutonomyLevel[] = ['off', 'suggest', 'draft', 'auto'];

/** What Autopilot will actually do: the user's level capped by the agent's ceiling (FR-AU-01). */
function effectiveLevel(level: AutonomyLevel, ceiling: AutonomyLevel | undefined): AutonomyLevel {
  if (!ceiling) return level;
  return ORDER[Math.min(ORDER.indexOf(level), ORDER.indexOf(ceiling))]!;
}

export function InsightStrip() {
  const { controller } = useShell();
  const autonomy = useShellState((s) => s.autonomy);
  const secure = useShellState((s) => s.field?.sensitive ?? false);
  const suggestions = useShellState((s) => s.suggestions);
  const insight = useShellState((s) => s.insight);
  const typing = useShellState((s) => s.editor.before.length + s.editor.after.length > 0);
  const sends = useShellState(
    (s) => effectiveLevel(s.autonomy.level, s.agents.find((a) => a.id === s.activeAgentId)?.autonomyCeiling) === 'auto',
  );

  if (autonomy.enabled && autonomy.phase !== 'off') {
    const text =
      autonomy.phase === 'escalated'
        ? `Needs you · ${autonomy.detail ?? 'sensitive request'}`
        : autonomy.phase === 'awaiting_user'
          ? (autonomy.detail ?? PHASE_TEXT.awaiting_user)
          : autonomy.phase === 'typing'
            ? sends
              ? 'Autopilot · replying as you'
              : 'Autopilot · drafting · you send'
            : autonomy.detail
              ? `${PHASE_TEXT[autonomy.phase]} · ${autonomy.detail}`
              : PHASE_TEXT[autonomy.phase];
    return (
      <div className={css.strip} aria-live="polite">
        <div className={css.insight} role="status">
          <span>{text}</span>
          <button type="button" className={css.stopBtn} onClick={() => controller.toggleAutonomy()} aria-label="Stop autopilot">
            <StopIcon size={12} /> Stop
          </button>
        </div>
      </div>
    );
  }

  if (secure) {
    return (
      <div className={css.strip}>
        <div className={css.secure} role="status">
          <LockIcon size={16} />
          <span>Protected field · AI & learning off</span>
        </div>
      </div>
    );
  }

  if (typing && suggestions.length > 0) {
    return (
      <div className={css.strip}>
        <div className={css.suggestions} role="listbox" aria-label="Suggestions">
          {suggestions.map((sg, i) => (
            <button
              type="button"
              key={`${sg.text}-${i}`}
              role="option"
              aria-selected={!!sg.primary}
              className={`${css.suggestion} ${sg.primary ? css.suggestionPrimary : ''}`}
              onPointerDown={(e) => e.preventDefault()}
              onClick={() => controller.pickSuggestion(sg.text)}
            >
              {sg.kind === 'verbatim' ? `“${sg.text}”` : sg.text}
            </button>
          ))}
        </div>
      </div>
    );
  }

  if (insight) {
    return (
      <div className={css.strip}>
        <button
          type="button"
          className={css.insight}
          onClick={() => controller.runAgent(insight.run.skill, insight.run.target, insight.run.params)}
        >
          <span>{insight.text}</span>
          <ChevronRight size={18} />
        </button>
      </div>
    );
  }

  return (
    <div className={css.strip}>
      {suggestions.length > 0 ? (
        <div className={css.suggestions} role="listbox" aria-label="Suggestions">
          {suggestions.map((sg, i) => (
            <button type="button" key={`${sg.text}-${i}`} role="option" aria-selected={false} className={css.suggestion} onClick={() => controller.pickSuggestion(sg.text)}>
              {sg.text}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
