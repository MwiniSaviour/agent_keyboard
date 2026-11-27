'use client';
/**
 * Lens / Context panel (FR-UI-06): shows WHAT the agent sees, then offers actions. Transparency is
 * a product feature (P4). Data comes from `context.snapshot` (SPEC §10).
 */
import { useEffect, useState } from 'react';
import type { RefineStyle, ScreenContext } from '@ak/protocol';
import { CloseIcon, ShieldIcon } from '@/ui/icons';
import { useShell, useShellState } from '../context';
import css from '../keyboard.module.css';

const RUNG_LABEL = ['Field only', 'Notifications', 'Screen structure', 'Screen text (OCR)', 'Vision'] as const;

export function ContextPanel() {
  const { controller } = useShell();
  const bridge = controller.bridge;
  const [ctx, setCtx] = useState<ScreenContext | null>(null);
  const hasText = useShellState((s) => (s.editor.before + s.editor.after).trim().length > 0);

  useEffect(() => {
    let alive = true;
    bridge
      .request('context.snapshot', { scope: 'screen' })
      .then((c) => alive && setCtx(c))
      .catch(() => alive && setCtx(null));
    return () => {
      alive = false;
    };
  }, [bridge]);

  const refine = (style: RefineStyle) => controller.refine(style);
  const lastThem = ctx?.chat?.messages.filter((m) => m.from === 'them').at(-1);
  const selection = ctx?.text?.selection;

  // Context-relevant actions first; the single best one for this screen carries the primary weight.
  const act: Array<{ label: string; run: () => void }> = [];
  if (ctx?.chat) act.push({ label: 'Draft reply', run: () => controller.runAgent('reply', 'conversation') });
  if (ctx?.form) act.push({ label: 'Fill form', run: () => controller.runAgent('fill_form', 'screen') });
  if (selection) act.push({ label: 'Define word', run: () => controller.runAgent('define', 'word', { word: selection }) });
  const understand: Array<{ label: string; run: () => void }> = [
    { label: 'Explain screen', run: () => controller.runAgent('explain', 'screen') },
    { label: 'Summarize', run: () => controller.runAgent('summarize', 'screen') },
    { label: 'Describe image', run: () => controller.runAgent('describe_image', 'image') },
  ];
  const primary = act[0]?.label ?? 'Explain screen';

  const actionButton = (a: { label: string; run: () => void }) => (
    <button type="button" key={a.label} className={css.action} data-primary={a.label === primary} onClick={a.run}>
      {a.label}
    </button>
  );

  return (
    <section className={css.panel} role="dialog" aria-label="Screen context">
      <header className={css.panelHead}>
        <ShieldIcon size={18} />
        <span className={css.grow}>What your agent sees</span>
        <button type="button" className={css.btnIcon} onClick={() => controller.closePanel()} aria-label="Close">
          <CloseIcon size={18} />
        </button>
      </header>
      <div className={css.panelBody}>
        {ctx ? (
          <>
            <p className={css.ctxSummary}>
              <b>{ctx.app.label}</b>
              <span aria-hidden>·</span>
              <span style={{ textTransform: 'capitalize' }}>{ctx.screenType}</span>
              <span aria-hidden>·</span>
              <span>
                <b>{ctx.field.hint ?? ctx.field.kind}</b> field
              </span>
              <span aria-hidden>·</span>
              <span>via {RUNG_LABEL[ctx.rung].toLowerCase()}</span>
            </p>
            {lastThem ? (
              <p className={css.ctxQuote}>
                <small>Last from {ctx.chat?.title}</small>“{lastThem.text}”
              </p>
            ) : ctx.form ? (
              <p className={css.ctxQuote}>
                <small>{ctx.form.title}</small>
                {ctx.form.fields.length} fields · {ctx.form.fields.filter((f) => f.kind === 'password').length} protected (never filled)
              </p>
            ) : ctx.text ? (
              <p className={css.ctxQuote}>
                <small>{selection ? 'Selected on screen' : ctx.text.title}</small>
                {selection ? `“${selection}”` : `${ctx.text.body.split(/\s+/).length} words on screen`}
              </p>
            ) : null}

            <div className={css.actionGrid} style={{ marginTop: 8 }}>
              {[...act, ...understand].slice(0, 6).map(actionButton)}
            </div>

            <div className={css.sectionLabel}>Rewrite what you typed</div>
            <div className={css.actionGrid}>
              {(
                [
                  ['fix', 'Fix grammar'],
                  ['formal', 'Formal'],
                  ['friendly', 'Friendly'],
                ] as const
              ).map(([style, label]) => (
                <button type="button" key={style} className={css.action} disabled={!hasText} onClick={() => refine(style)}>
                  {label}
                </button>
              ))}
            </div>
            {!hasText ? <p className={css.meta}>Type something first to rewrite it.</p> : null}
          </>
        ) : (
          <p className={css.itemSub}>Reading screen…</p>
        )}
      </div>
    </section>
  );
}
