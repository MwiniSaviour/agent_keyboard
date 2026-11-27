'use client';
/**
 * Key grid with a single delegated pointer handler (SPEC §7.3):
 *  - pressed visuals are set imperatively (no React render per touch),
 *  - commit on pointer-up (retargeted to the key under the finger),
 *  - long-press → hint, backspace repeat + word acceleration, space-bar cursor drag,
 *  - multi-touch rollover: a new touch commits the previous pending key immediately.
 */
import { memo, useEffect, useMemo, useRef, type PointerEvent as ReactPointerEvent } from 'react';
import { enterBehavior, keyById, rowsFor, type KeySpec, type ShiftState } from '@ak/keyboard-engine';
import { useSetting } from '@/lib/settings';
import { useStore } from '@/lib/store';
import { BackspaceIcon, CapsLockIcon, MicIcon, ShiftIcon, SmileIcon } from '@/ui/icons';
import { useShell } from './context';
import css from './keyboard.module.css';

const ENTER_LABEL: Record<string, string> = {
  newline: 'return',
  send: 'send',
  search: 'search',
  go: 'go',
  next: 'next',
  done: 'done',
};

interface Touch {
  keyId: string;
  el: HTMLElement;
  x0: number;
  y0: number;
  committed: boolean;
  longTimer: ReturnType<typeof setTimeout> | null;
  repeatTimer: ReturnType<typeof setTimeout> | null;
  repeatStart: number;
  cursorMode: boolean;
  cursorAccum: number;
}

const REPEAT_DELAY = 350;
const REPEAT_INTERVAL = 60;
const WORD_DELETE_AFTER = 1500;
const CURSOR_SLOP = 12;
const CURSOR_STEP = 14;

export function KeyGrid() {
  const { controller, store } = useShell();
  const layer = useStore(store, (s) => s.engine.layer);
  const shift = useStore(store, (s) => s.engine.shift);
  const fieldKind = useStore(store, (s) => s.field?.kind ?? 'text');
  const imeAction = useStore(store, (s) => s.field?.imeAction ?? 'none');
  const numberRow = useSetting('numberRow');
  const keyHints = useSetting('keyHints');
  const longPressMs = useSetting('longPressMs');

  const rows = useMemo(() => rowsFor(layer, { numberRow }), [layer, numberRow]);
  const enterLabel = ENTER_LABEL[enterBehavior({ kind: fieldKind, imeAction, capsMode: 'none' })] ?? 'return';

  const touches = useRef(new Map<number, Touch>());

  useEffect(() => {
    const map = touches.current;
    return () => {
      map.forEach(clearTimers);
      map.clear();
    };
  }, []);

  function clearTimers(t: Touch) {
    if (t.longTimer) clearTimeout(t.longTimer);
    if (t.repeatTimer) clearTimeout(t.repeatTimer);
    t.longTimer = t.repeatTimer = null;
  }

  function release(t: Touch) {
    clearTimers(t);
    delete t.el.dataset.pressed;
    delete t.el.dataset.longfired;
  }

  function commitTap(keyId: string) {
    controller.tap(keyId);
  }

  function onPointerDown(e: ReactPointerEvent<HTMLDivElement>) {
    const el = (e.target as HTMLElement).closest<HTMLElement>('[data-key]');
    if (!el) return;
    e.preventDefault();
    const keyId = el.dataset.key!;
    const spec = keyById(keyId);
    if (!spec) return;
    e.currentTarget.setPointerCapture(e.pointerId);

    // Rollover: commit any pending char key from another finger now.
    touches.current.forEach((t) => {
      if (!t.committed && keyById(t.keyId)?.kind === 'char') {
        commitTap(t.keyId);
        t.committed = true;
        release(t);
      }
    });

    el.dataset.pressed = '';
    const t: Touch = {
      keyId,
      el,
      x0: e.clientX,
      y0: e.clientY,
      committed: false,
      longTimer: null,
      repeatTimer: null,
      repeatStart: performance.now(),
      cursorMode: false,
      cursorAccum: 0,
    };
    touches.current.set(e.pointerId, t);

    switch (spec.kind) {
      case 'backspace': {
        // Delete on touch-down for responsiveness, then repeat (FR-KB-05).
        controller.backspace('grapheme');
        t.committed = true;
        const repeat = () => {
          const word = performance.now() - t.repeatStart > WORD_DELETE_AFTER;
          controller.backspace(word ? 'word' : 'grapheme');
          t.repeatTimer = setTimeout(repeat, word ? REPEAT_INTERVAL * 3 : REPEAT_INTERVAL);
        };
        t.repeatTimer = setTimeout(repeat, REPEAT_DELAY);
        break;
      }
      case 'shift':
        // Shift acts on touch-down so a fast double-tap reliably locks caps.
        commitTap(keyId);
        t.committed = true;
        break;
      case 'char':
        if (spec.hint) {
          t.longTimer = setTimeout(() => {
            t.longTimer = null;
            if (t.committed) return;
            t.committed = true;
            el.dataset.longfired = '';
            controller.longPress(keyId);
          }, longPressMs);
        }
        break;
      default:
        break;
    }
  }

  function onPointerMove(e: ReactPointerEvent<HTMLDivElement>) {
    const t = touches.current.get(e.pointerId);
    if (!t || t.committed) return;
    const spec = keyById(t.keyId);
    if (spec?.kind !== 'space') return;
    const dx = e.clientX - t.x0;
    if (!t.cursorMode && Math.abs(dx) > CURSOR_SLOP) {
      t.cursorMode = true;
      t.cursorAccum = 0;
      t.x0 = e.clientX;
      return;
    }
    if (t.cursorMode) {
      const steps = Math.trunc((e.clientX - t.x0) / CURSOR_STEP);
      if (steps !== 0) {
        controller.moveCursor(steps);
        t.x0 += steps * CURSOR_STEP;
      }
    }
  }

  function onPointerUp(e: ReactPointerEvent<HTMLDivElement>) {
    const t = touches.current.get(e.pointerId);
    if (!t) return;
    touches.current.delete(e.pointerId);
    release(t);
    if (t.committed || t.cursorMode) return;
    // Retarget to the key under the finger at release (slide correction).
    const under = (document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null)?.closest<HTMLElement>('[data-key]');
    const keyId = under?.dataset.key ?? t.keyId;
    if (!keyById(keyId)) return;
    commitTap(keyId);
  }

  function onPointerCancel(e: ReactPointerEvent<HTMLDivElement>) {
    const t = touches.current.get(e.pointerId);
    if (!t) return;
    touches.current.delete(e.pointerId);
    release(t);
  }

  return (
    <div
      className={css.grid}
      role="group"
      aria-label="Keyboard"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onContextMenu={(e) => e.preventDefault()}
    >
      {rows.map((row, ri) => (
        <div className={css.row} key={`${layer}-${ri}`}>
          {row.inset ? <div className={css.spacer} style={{ flexGrow: row.inset }} /> : null}
          {row.keys.map((k) => (
            <Key key={k.id} spec={k} shift={shift} showHint={keyHints} enterLabel={enterLabel} />
          ))}
          {row.inset ? <div className={css.spacer} style={{ flexGrow: row.inset }} /> : null}
        </div>
      ))}
    </div>
  );
}

interface KeyProps {
  spec: KeySpec;
  shift: ShiftState;
  showHint: boolean;
  enterLabel: string;
}

const Key = memo(function Key({ spec, shift, showHint, enterLabel }: KeyProps) {
  const width = spec.width ?? 1;
  let content: React.ReactNode;
  let preview: string | undefined;
  let a11y = spec.a11y;

  switch (spec.kind) {
    case 'char': {
      const label = shift !== 'off' ? (spec.value ?? '').toUpperCase() : (spec.value ?? '');
      content = <span className={css.label}>{label}</span>;
      preview = label;
      a11y = label;
      break;
    }
    case 'shift':
      content = shift === 'locked' ? <CapsLockIcon size={26} /> : <ShiftIcon size={26} filled={shift === 'once'} />;
      a11y = shift === 'locked' ? 'Shift, caps lock on' : shift === 'once' ? 'Shift, on' : 'Shift';
      break;
    case 'backspace':
      content = <BackspaceIcon size={26} />;
      break;
    case 'emoji':
      content = <SmileIcon size={28} />;
      break;
    case 'mic':
      content = <MicIcon size={24} />;
      break;
    case 'enter':
      content = <span className={`${css.label} ${css.wide}`}>{enterLabel}</span>;
      a11y = enterLabel;
      break;
    case 'space':
      content = <span className={`${css.label} ${css.wide}`}>space</span>;
      break;
    case 'layer':
      content = <span className={`${css.label} ${css.wide}`}>{spec.label}</span>;
      break;
  }

  return (
    <div
      className={css.key}
      style={{ flexGrow: width }}
      data-key={spec.id}
      data-role={spec.role}
      data-preview={preview}
      data-long={spec.hint}
      role="button"
      aria-label={a11y}
    >
      {content}
      {showHint && spec.hint ? <span className={css.hint}>{spec.hint}</span> : null}
    </div>
  );
});
