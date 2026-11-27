'use client';
/** Result card: the "lens" pattern from the reference (FR-UI-04, FR-RX-03). */
import type { RefineStyle, ResultActionId } from '@ak/protocol';
import { CloseIcon, CopyIcon, RetryIcon, SpeakerIcon } from '@/ui/icons';
import { useShell, useShellState } from '../context';
import css from '../keyboard.module.css';

const SKILL_TITLE: Record<string, string> = {
  reply: 'Reply',
  refine: 'Refine',
  define: 'Define',
  explain: 'Explain screen',
  summarize: 'Summary',
  fill_form: 'Fill form',
  translate: 'Translate',
  describe_image: 'Describe image',
  complete: 'Complete',
  transcribe_refine: 'Dictation',
};

const REFINE_CHIPS: Array<[RefineStyle, string]> = [
  ['fix', 'Fix grammar'],
  ['shorter', 'Shorter'],
  ['formal', 'Formal'],
  ['friendly', 'Friendly'],
  ['persuasive', 'Persuasive'],
  ['longer', 'Longer'],
  ['emojify', 'Emojify'],
];

const SECONDARY: Partial<Record<ResultActionId, { icon: React.ReactNode; label: string }>> = {
  copy: { icon: <CopyIcon size={18} />, label: 'Copy' },
  speak: { icon: <SpeakerIcon size={18} />, label: 'Read aloud' },
  retry: { icon: <RetryIcon size={18} />, label: 'Retry' },
};

export function ResultPanel() {
  const { controller } = useShell();
  const run = useShellState((s) => s.run);
  const agent = useShellState((s) => s.agents.find((a) => a.id === s.run?.req.agentId));
  if (!run) return null;

  const actions = run.result?.actions ?? [];
  const primary: ResultActionId | null = actions.includes('apply') ? 'apply' : actions.includes('insert') ? 'insert' : null;
  const applyLabel = run.result?.apply?.mode === 'fillForm' ? 'Fill fields' : primary === 'insert' ? 'Insert' : 'Apply';

  return (
    <section className={css.panel} role="dialog" aria-label={`${SKILL_TITLE[run.req.skill]} result`}>
      <header className={css.panelHead}>
        <span className={css.agentTag}>
          <span aria-hidden>{agent?.icon ?? '●'}</span> {agent?.name ?? 'General'}
        </span>
        <span className={css.grow}>{SKILL_TITLE[run.req.skill]}</span>
        <button type="button" className={css.btnIcon} onClick={() => controller.closePanel()} aria-label="Close">
          <CloseIcon size={18} />
        </button>
      </header>

      {run.req.skill === 'refine' ? (
        <div className={css.chipRow} role="toolbar" aria-label="Refine style">
          {REFINE_CHIPS.map(([style, label]) => (
            <button
              type="button"
              key={style}
              className={css.chip}
              aria-pressed={run.req.params?.style === style}
              onClick={() => controller.refine(style)}
            >
              {label}
            </button>
          ))}
        </div>
      ) : null}

      <div className={css.panelBody} aria-live="polite">
        <div className={css.resultLabel}>Result</div>
        {run.status === 'error' ? (
          <p className={css.error}>{run.error}</p>
        ) : (
          <p className={css.resultText}>
            {run.text}
            {run.status === 'streaming' ? <i className={css.caret} aria-hidden /> : null}
          </p>
        )}
        {run.result ? (
          <div className={css.meta}>
            {run.result.model} · first token {run.result.latencyMs.ttft} ms · {run.result.latencyMs.total} ms total · on-device
          </div>
        ) : null}
      </div>

      <footer className={css.panelFoot}>
        {actions
          .filter((a) => SECONDARY[a])
          .map((a) => (
            <button type="button" key={a} className={css.btnIcon} onClick={() => controller.resultAction(a)} aria-label={SECONDARY[a]!.label}>
              {SECONDARY[a]!.icon}
            </button>
          ))}
        {primary ? (
          <button type="button" className={css.btnPrimary} onClick={() => controller.resultAction(primary)} disabled={run.status !== 'done'}>
            {applyLabel}
          </button>
        ) : null}
      </footer>
    </section>
  );
}
