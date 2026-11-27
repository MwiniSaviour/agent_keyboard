/**
 * MockHost: a simulated "host app + InputConnection". The playground renders it; the WebMockBridge
 * mutates it exactly as the native InputBridge would mutate a real field.
 */
import type { ChatMessage, EditorState, FieldContext } from '@ak/protocol';
import { commitText, deleteSurroundingCodePoints, EMPTY_EDITOR, moveCursor, replaceText } from '@ak/keyboard-engine';
import { createStore, uid } from '@/lib/store';
import { SCENARIOS, type ScenarioId } from './scenarios';

export interface HostState {
  scenarioId: ScenarioId;
  focusedFieldId: string | null;
  editors: Record<string, EditorState>;
  messages: ChatMessage[];
  /** Remaining scripted incoming messages (chat scenario). */
  incomingQueue: string[];
  /** Word selected on screen (article scenario) — screen selection, not field selection. */
  screenSelection: string | null;
  typingIndicator: boolean;
  /** Bumped on every text mutation so subscribers can cheaply detect changes. */
  rev: number;
  lastSubmitted: string | null;
}

function initialState(id: ScenarioId): HostState {
  const sc = SCENARIOS[id];
  const editors: Record<string, EditorState> = {};
  for (const f of sc.fields) editors[f.id] = EMPTY_EDITOR;
  return {
    scenarioId: id,
    focusedFieldId: sc.fields[0]?.id ?? null,
    editors,
    messages: sc.chat ? [...sc.chat.messages] : [],
    incomingQueue: sc.chat ? [...sc.chat.incoming] : [],
    screenSelection: null,
    typingIndicator: false,
    rev: 0,
    lastSubmitted: null,
  };
}

type HostListener = (event: HostEvent) => void;
export type HostEvent =
  | { type: 'focus'; fieldId: string | null }
  | { type: 'text' }
  | { type: 'incoming'; message: ChatMessage }
  | { type: 'sent'; message: ChatMessage }
  | { type: 'selection' }
  | { type: 'scenario' };

export class MockHost {
  readonly store = createStore<HostState>(initialState('chat'));
  private listeners = new Set<HostListener>();
  private timers = new Set<ReturnType<typeof setTimeout>>();

  onEvent(l: HostListener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }
  private emit(e: HostEvent) {
    this.listeners.forEach((l) => l(e));
  }

  get scenario() {
    return SCENARIOS[this.store.get().scenarioId];
  }

  setScenario(id: ScenarioId): void {
    this.timers.forEach(clearTimeout);
    this.timers.clear();
    this.store.set(initialState(id));
    this.emit({ type: 'scenario' });
    this.emit({ type: 'focus', fieldId: this.store.get().focusedFieldId });
  }

  focus(fieldId: string | null): void {
    if (this.store.get().focusedFieldId === fieldId) return;
    this.store.set({ focusedFieldId: fieldId });
    this.emit({ type: 'focus', fieldId });
  }

  field(): FieldContext | null {
    const id = this.store.get().focusedFieldId;
    return this.scenario.fields.find((f) => f.id === id)?.field ?? null;
  }

  editor(): EditorState {
    const s = this.store.get();
    return (s.focusedFieldId && s.editors[s.focusedFieldId]) || EMPTY_EDITOR;
  }

  private mutate(fn: (e: EditorState) => EditorState, fieldId = this.store.get().focusedFieldId): void {
    if (!fieldId) return;
    this.store.set((s) => ({
      editors: { ...s.editors, [fieldId]: fn(s.editors[fieldId] ?? EMPTY_EDITOR) },
      rev: s.rev + 1,
    }));
    this.emit({ type: 'text' });
  }

  commit(text: string): void {
    this.mutate((e) => commitText(e, text));
  }
  /** Counts are code points, like `deleteSurroundingTextInCodePoints` on Android (SPEC §8.2). */
  delete(before: number, after: number): void {
    this.mutate((e) => deleteSurroundingCodePoints(e, before, after));
  }
  replace(scope: 'selection' | 'field' | 'word', text: string): void {
    this.mutate((e) => replaceText(e, scope, text));
  }
  moveCursor(delta: number): void {
    this.mutate((e) => moveCursor(e, delta));
  }
  setFieldValue(fieldId: string, value: string): void {
    this.mutate(() => ({ before: value, after: '', selected: '' }), fieldId);
  }

  selectOnScreen(word: string | null): void {
    this.store.set({ screenSelection: word });
    this.emit({ type: 'selection' });
  }

  /** Editor action from the keyboard (send / next / done …). */
  action(action: string): void {
    const s = this.store.get();
    const sc = this.scenario;
    if (sc.id === 'chat' && action === 'send') {
      const text = (this.editor().before + this.editor().after).trim();
      if (!text) return;
      const message: ChatMessage = { id: uid('m'), from: 'me', author: 'You', text, at: Date.now() };
      this.store.set((st) => ({ messages: [...st.messages, message] }));
      this.mutate(() => EMPTY_EDITOR);
      this.emit({ type: 'sent', message });
      return;
    }
    if (sc.id === 'form' && (action === 'next' || action === 'done')) {
      const idx = sc.fields.findIndex((f) => f.id === s.focusedFieldId);
      const next = action === 'next' ? sc.fields[idx + 1]?.id ?? null : null;
      if (action === 'done') this.store.set({ lastSubmitted: new Date().toLocaleTimeString() });
      this.focus(next);
    }
  }

  /** Deliver the next scripted inbound message after `delayMs` (chat scenario). */
  scheduleIncoming(delayMs: number): boolean {
    const queue = this.store.get().incomingQueue;
    if (this.scenario.id !== 'chat' || queue.length === 0) return false;
    const [text, ...rest] = queue;
    this.store.set({ incomingQueue: rest, typingIndicator: true });
    const timer = setTimeout(() => {
      this.timers.delete(timer);
      const message: ChatMessage = { id: uid('m'), from: 'them', author: 'Jane Doe', text: text!, at: Date.now() };
      this.store.set((st) => ({ messages: [...st.messages, message], typingIndicator: false }));
      this.emit({ type: 'incoming', message });
    }, delayMs);
    this.timers.add(timer);
    return true;
  }
}
