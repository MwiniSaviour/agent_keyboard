'use client';
/**
 * Lens / Context panel (FR-UI-06): shows WHAT the agent sees, then offers actions. Transparency is
 * a product feature (P4). Data comes from `context.snapshot` (SPEC §10).
 *
 * Live screen (ADR-0010): while capture is on, the panel shows the exact frame the agent would use;
 * "Read whole screen" scrolls the app under the keyboard and back, then shows how much it read.
 * Cluely-style tools hide from the user's screen share; this panel does the opposite: it shows the
 * user everything the agent can see.
 */
import { useEffect, useState } from 'react';
import type { RefineStyle, ScreenContext } from '@ak/protocol';
import { CloseIcon, ShieldIcon } from '@/ui/icons';
import { useShell, useShellState } from '../context';
import css from '../keyboard.module.css';

const RUNG_LABEL = ['Field only', 'Notifications', 'Screen structure', 'Screen pixels', 'Vision'] as const;

export function ContextPanel() {
  const { controller } = useShell();
  const bridge = controller.bridge;
  const [ctx, setCtx] = useState<ScreenContext | null>(null);
  const [scanning, setScanning] = useState(false);
  const hasText = useShellState((s) => (s.editor.before + s.editor.after).trim().length > 0);
  const screen = useShellState((s) => s.screen);
  const canCapture = useShellState((s) => s.capabilities?.screenCapture ?? false);
  const canScan = useShellState((s) => s.capabilities?.screenScan ?? false);
  const live = screen.status === 'live';

  useEffect(() => {
    let alive = true;
    // With live capture on, ask for pixels too; fall back to structure if no frame is ready yet.
    const want = live ? bridge.request('context.snapshot', { scope: 'visual' }) : Promise.reject(new Error('off'));
    want
      .catch(() => bridge.request('context.snapshot', { scope: 'screen' }))
      .then((c) => alive && setCtx(c))
      .catch(() => alive && setCtx(null));
    return () => {
      alive = false;
    };
  }, [bridge, live]);

  const scan = () => {
    if (!ctx || scanning) return;
    setScanning(true);
    controller
      .scanScreen(ctx.screenType)
      .then(setCtx, () => controller.toast('Couldn’t read more of this screen'))
      .finally(() => setScanning(false));
  };

  const refine = (style: RefineStyle) => controller.refine(style);
  const lastThem = ctx?.chat?.messages.filter((m) => m.from === 'them').at(-1);
  const frame = ctx?.images?.find((i) => i.preview);
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
              {ctx.scan ? (
                <>
                  <span aria-hidden>·</span>
                  <span>
                    {ctx.scan.pages} {ctx.scan.pages === 1 ? 'screen' : 'screens'} read{ctx.scan.complete ? ' (all)' : ''}
                  </span>
                </>
              ) : null}
            </p>
            {frame ? (
              <figure className={css.ctxFrame}>
                <img src={frame.preview} alt="The screen image your agent can use right now" width={frame.width} height={frame.height} />
                <figcaption>Exactly what your agent sees · kept on this phone for 30 s</figcaption>
              </figure>
            ) : null}
            {lastThem ? (
              <p className={css.ctxQuote}>
                <small>
                  {ctx.chat?.match === 'uncertain' ? `Latest notification · ${ctx.chat.title} · may be another chat` : `Last from ${ctx.chat?.title}`}
                  {ctx.chat && ctx.chat.messages.length > 1 ? ` · ${ctx.chat.messages.length} messages` : ''}
                </small>
                “{lastThem.text}”
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

            {canCapture || canScan ? (
              <>
                <div className={css.sectionLabel}>See more</div>
                <div className={css.actionGrid}>
                  {canScan ? (
                    <button type="button" className={css.action} onClick={scan} disabled={scanning || ctx.sensitivity.sensitiveField} aria-busy={scanning}>
                      {scanning ? 'Reading…' : ctx.screenType === 'chat' ? 'Read earlier messages' : 'Read whole page'}
                    </button>
                  ) : null}
                  {canCapture ? (
                    <button
                      type="button"
                      className={css.action}
                      role="switch"
                      aria-checked={screen.status !== 'off'}
                      onClick={() => controller.setScreenCapture(screen.status === 'off')}
                      disabled={ctx.sensitivity.sensitiveField && screen.status === 'off'}
                    >
                      {screen.status === 'off' ? 'Turn on live screen' : screen.status === 'starting' ? 'Starting…' : 'Stop live screen'}
                    </button>
                  ) : null}
                </div>
                <p className={css.meta}>
                  {screen.status === 'paused' && screen.detail
                    ? screen.detail
                    : screen.status === 'live'
                      ? 'Live: your agent sees the app above the keyboard. Frames stay in memory, never saved or sent.'
                      : 'Reading scrolls the app and puts it back. Live screen asks Android for permission each time.'}
                </p>
              </>
            ) : null}

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
