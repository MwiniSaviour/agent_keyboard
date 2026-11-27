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
            <div className={css.ctxGrid}>
              <div className={css.ctxCell}>
                App<b>{ctx.app.label}</b>
              </div>
              <div className={css.ctxCell}>
                Screen<b style={{ textTransform: 'capitalize' }}>{ctx.screenType}</b>
              </div>
              <div className={css.ctxCell}>
                Focused field<b>{ctx.field.hint ?? ctx.field.kind}</b>
              </div>
              <div className={css.ctxCell}>
                Source<b>{RUNG_LABEL[ctx.rung]}</b>
              </div>
              {ctx.chat ? (
                <div className={css.ctxCell} style={{ gridColumn: '1 / -1' }}>
                  Last from {ctx.chat.title}
                  <b>{lastThem ? `“${lastThem.text}”` : '—'}</b>
                </div>
              ) : null}
              {ctx.form ? (
                <div className={css.ctxCell} style={{ gridColumn: '1 / -1' }}>
                  {ctx.form.title}
                  <b>
                    {ctx.form.fields.length} fields · {ctx.form.fields.filter((f) => f.kind === 'password').length} protected
                  </b>
                </div>
              ) : null}
              {ctx.text ? (
                <div className={css.ctxCell} style={{ gridColumn: '1 / -1' }}>
                  {ctx.text.selection ? 'Selected on screen' : 'Text on screen'}
                  <b>{ctx.text.selection ? `“${ctx.text.selection}”` : `${ctx.text.body.split(/\s+/).length} words · ${ctx.text.title}`}</b>
                </div>
              ) : null}
            </div>

            <div className={css.sectionLabel}>Understand</div>
            <div className={css.actionGrid}>
              <button type="button" className={css.action} onClick={() => controller.runAgent('explain', 'screen')}>
                Explain screen
              </button>
              <button type="button" className={css.action} onClick={() => controller.runAgent('summarize', 'screen')}>
                Summarize
              </button>
              <button
                type="button"
                className={css.action}
                disabled={!ctx.text?.selection}
                onClick={() => controller.runAgent('define', 'word', { word: ctx.text?.selection ?? '' })}
              >
                Define word
              </button>
            </div>

            <div className={css.sectionLabel}>Act</div>
            <div className={css.actionGrid}>
              <button type="button" className={css.action} disabled={!ctx.chat} onClick={() => controller.runAgent('reply', 'conversation')}>
                Draft reply
              </button>
              <button type="button" className={css.action} disabled={!ctx.form} onClick={() => controller.runAgent('fill_form', 'screen')}>
                Fill form
              </button>
              <button type="button" className={css.action} onClick={() => controller.runAgent('describe_image', 'image')}>
                Describe image
              </button>
            </div>

            <div className={css.sectionLabel}>Write</div>
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
          </>
        ) : (
          <p className={css.itemSub}>Reading screen…</p>
        )}
      </div>
    </section>
  );
}
