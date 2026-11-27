/**
 * KeyboardController glues the pure engine, the shell store and the NativeBridge.
 * It is the only place where engine effects become bridge messages (SPEC §6.2, §7.3).
 */
import type {
  AgentRunRequest,
  AutonomyLevel,
  NativeBridge,
  RefineStyle,
  ResultActionId,
  RunTarget,
  SkillId,
} from '@ak/protocol';
import {
  commitText,
  createDevPredictor,
  currentWord,
  deleteSurrounding,
  englishDevLexicon,
  moveCursor,
  reduce,
  replaceText,
  toWireDelete,
  type EngineEvent,
  type Predictor,
} from '@ak/keyboard-engine';
import { NATIVE_SETTINGS, settingsStore, systemThemeStore } from '@/lib/settings';
import { uid } from '@/lib/store';
import type { Panel, ShellStore } from './shell-store';

const ACTIVE_AUTONOMY = new Set(['planning', 'review', 'typing', 'sending']);
const CONSENT_KEY = 'ak.autonomy.consent.v1';

export class KeyboardController {
  private readonly predictor: Predictor = createDevPredictor(englishDevLexicon());
  private readonly unsubs: Array<() => void> = [];
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    readonly bridge: NativeBridge,
    readonly store: ShellStore,
  ) {}

  // ───────────── lifecycle ─────────────

  init(): () => void {
    const b = this.bridge;
    const s = this.store;
    try {
      s.set((st) => ({ autonomy: { ...st.autonomy, consent: localStorage.getItem(CONSENT_KEY) === '1' } }));
    } catch {
      /* storage unavailable */
    }

    this.unsubs.push(
      b.on('editor.start', ({ field, state }) => {
        s.set({ field, editor: state });
        this.dispatch({ type: 'editorChanged' });
        this.refreshSuggestions();
      }),
      b.on('editor.state', (state) => {
        const cur = s.get().editor;
        if (cur.before === state.before && cur.after === state.after && cur.selected === state.selected) return;
        s.set({ editor: state });
        this.dispatch({ type: 'editorChanged' });
        this.refreshSuggestions();
      }),
      b.on('editor.finish', () => s.set({ field: null })),
      b.on('suggestions.update', ({ items }) => s.set({ suggestions: items })),
      b.on('insight.update', ({ insight }) => s.set({ insight })),
      b.on('agent.token', ({ runId, delta }) => {
        const run = s.get().run;
        if (run?.req.runId === runId) s.set({ run: { ...run, text: run.text + delta } });
      }),
      b.on('agent.done', (result) => {
        const run = s.get().run;
        if (run?.req.runId === result.runId) s.set({ run: { ...run, status: 'done', text: result.text, result } });
      }),
      b.on('agent.error', ({ runId, message, code }) => {
        const run = s.get().run;
        if (run?.req.runId === runId && code !== 'cancelled') s.set({ run: { ...run, status: 'error', error: message } });
        else if (code === 'policy') this.toast(message);
      }),
      b.on('autonomy.phase', ({ phase, detail }) =>
        s.set((st) => ({ autonomy: { ...st.autonomy, phase, detail: detail ?? null, enabled: phase !== 'off' && st.autonomy.enabled } })),
      ),
      b.on('voice.partial', ({ text }) => s.set((st) => ({ voice: { ...st.voice, partial: text } }))),
      b.on('voice.level', ({ rms }) => s.set((st) => ({ voice: { ...st.voice, level: rms } }))),
      b.on('voice.final', ({ text }) => this.insertSmart(text)),
      b.on('voice.end', () => {
        if (s.get().panel === 'voice') s.set({ panel: 'none' });
        s.set({ voice: { partial: '', level: 0 } });
      }),
      b.on('agents.changed', () => void this.loadAgents()),
      b.on('theme.update', ({ systemDark }) => systemThemeStore.set({ dark: systemDark })),
    );
    this.syncNativeSettings();

    void b
      .request('hello', { shellVersion: '0.1.0' })
      .then((capabilities) => s.set({ capabilities, ready: true }))
      .catch(() => s.set({ ready: true }));
    void this.loadAgents();

    return () => this.dispose();
  }

  /**
   * Mirrors `NATIVE_SETTINGS` to the IME host (it performs haptics/sounds and themes the native
   * fallback keyboard). Sent once at start, so native converges after its data was cleared, then on
   * change. Only the IME needs them; the companion and the web mock do not.
   */
  private syncNativeSettings(): void {
    if (this.bridge.kind !== 'ime') return;
    const sent: Partial<Record<(typeof NATIVE_SETTINGS)[number], unknown>> = {};
    const push = () => {
      const current = settingsStore.get();
      for (const k of NATIVE_SETTINGS) {
        if (Object.is(sent[k], current[k])) continue;
        sent[k] = current[k];
        this.bridge.send('settings.set', { key: `keyboard.${k}`, value: current[k] });
      }
    };
    push();
    this.unsubs.push(settingsStore.subscribe(push));
  }

  dispose(): void {
    this.unsubs.splice(0).forEach((u) => u());
    if (this.toastTimer) clearTimeout(this.toastTimer);
  }

  async loadAgents(): Promise<void> {
    try {
      const agents = await this.bridge.request('agents.list', {});
      const active = this.store.get().activeAgentId;
      this.store.set({ agents, activeAgentId: agents.some((a) => a.id === active) ? active : (agents[0]?.id ?? active) });
    } catch {
      /* keep defaults */
    }
  }

  // ───────────── keystroke path (hot) ─────────────

  private takeoverIfAutonomous(): void {
    const a = this.store.get().autonomy;
    if (a.enabled && ACTIVE_AUTONOMY.has(a.phase)) this.bridge.send('autonomy.takeover', {});
  }

  dispatch(event: EngineEvent): void {
    const s = this.store.get();
    const field = s.field ?? { kind: 'text' as const, capsMode: 'sentences' as const, imeAction: 'none' as const };
    const settings = settingsStore.get();
    const engine =
      s.engine.settings.autoCapitalize === settings.autoCapitalize && s.engine.settings.doubleSpacePeriod === settings.doubleSpacePeriod
        ? s.engine
        : { ...s.engine, settings: { ...s.engine.settings, autoCapitalize: settings.autoCapitalize, doubleSpacePeriod: settings.doubleSpacePeriod } };

    const { state, effects } = reduce(engine, event, { state: s.editor, field });
    let editor = s.editor;
    let panel: Panel | null = null;

    for (const e of effects) {
      switch (e.type) {
        case 'commit':
          editor = commitText(editor, e.text); // optimistic mirror first (SPEC §7.3.5)
          this.store.set({ editor });
          this.bridge.send('text.commit', { text: e.text });
          break;
        case 'delete': {
          // The engine counts UTF-16 units; the wire counts code points (SPEC §8.2).
          const wire = toWireDelete(editor, e.before);
          editor = deleteSurrounding(editor, e.before);
          this.store.set({ editor });
          this.bridge.send('text.delete', wire);
          break;
        }
        case 'action':
          this.bridge.send('editor.action', { action: e.action });
          break;
        case 'feedback':
          if (settings.haptics || settings.keySounds) this.bridge.send('feedback.key', { kind: e.kind });
          break;
        case 'panel':
          panel = e.panel;
          break;
      }
    }
    this.store.set({ engine: state });
    if (event.type !== 'editorChanged') this.refreshSuggestions();
    if (panel === 'voice') this.startVoice();
    else if (panel) this.openPanel(panel);
  }

  tap(keyId: string): void {
    this.takeoverIfAutonomous();
    this.dispatch({ type: 'tap', keyId, at: performance.now() });
  }

  longPress(keyId: string): void {
    this.takeoverIfAutonomous();
    this.dispatch({ type: 'longpress', keyId, at: performance.now() });
  }

  backspace(mode: 'grapheme' | 'word'): void {
    this.takeoverIfAutonomous();
    this.dispatch({ type: 'backspace', mode, at: performance.now() });
  }

  pickSuggestion(text: string): void {
    this.takeoverIfAutonomous();
    this.predictor.learn(text);
    this.dispatch({ type: 'suggestion', text, at: performance.now() });
  }

  insertText(text: string): void {
    this.takeoverIfAutonomous();
    this.dispatch({ type: 'insert', text, at: performance.now() });
  }

  /** Insert with a separating space when needed (voice, result cards). */
  insertSmart(text: string): void {
    const before = this.store.get().editor.before;
    const sep = before.length > 0 && !/\s$/.test(before) ? ' ' : '';
    this.insertText(sep + text);
  }

  moveCursor(delta: number): void {
    this.store.set((st) => ({ editor: moveCursor(st.editor, delta) }));
    this.bridge.send('editor.cursor', { delta });
  }

  private refreshSuggestions(): void {
    const s = this.store.get();
    if (s.capabilities?.nativeSuggestions) return; // native pushes suggestions.update
    if (!settingsStore.get().smartSuggestions || s.field?.sensitive || s.field?.kind === 'password') {
      if (s.suggestions.length) this.store.set({ suggestions: [] });
      return;
    }
    this.store.set({ suggestions: this.predictor.suggest(s.editor.before, 3) });
  }

  // ───────────── panels ─────────────

  openPanel(panel: Panel): void {
    const cur = this.store.get().panel;
    const next = cur === panel ? 'none' : panel;
    this.store.set({ panel: next });
    if (next === 'clipboard') void this.bridge.request('clipboard.list', {}).then((clipboard) => this.store.set({ clipboard }));
    if (next === 'agents') void this.loadAgents();
  }

  closePanel(): void {
    const s = this.store.get();
    if (s.panel === 'voice') this.bridge.send('voice.cancel', {});
    if (s.panel === 'result' && s.run?.status === 'streaming') this.bridge.send('agent.cancel', { runId: s.run.req.runId });
    this.store.set({ panel: 'none' });
  }

  selectAgent(id: string): void {
    this.store.set({ activeAgentId: id, panel: 'none' });
    const a = this.store.get().autonomy;
    if (a.enabled) {
      this.bridge.send('autonomy.set', { enabled: true, level: a.level, agentId: id });
      this.explainCeiling();
    }
  }

  // ───────────── agents ─────────────

  runAgent(skill: SkillId, target: RunTarget, params?: AgentRunRequest['params']): void {
    const s = this.store.get();
    if (s.field?.sensitive) return this.toast('AI is off in protected fields');
    if (s.run?.status === 'streaming') this.bridge.send('agent.cancel', { runId: s.run.req.runId });
    const req: AgentRunRequest = { runId: uid('run'), agentId: s.activeAgentId, skill, target, ...(params ? { params } : {}) };
    this.store.set({ run: { req, status: 'streaming', text: '', result: null }, panel: 'result' });
    this.bridge.send('agent.run', req);
  }

  refine(style: RefineStyle): void {
    const sel = this.store.get().editor.selected;
    this.runAgent('refine', sel ? 'selection' : 'field', { style });
  }

  resultAction(action: ResultActionId): void {
    const run = this.store.get().run;
    if (!run) return;
    const text = run.result?.text ?? run.text;
    switch (action) {
      case 'apply': {
        const mode = run.result?.apply?.mode ?? 'insert';
        if (mode === 'fillForm') this.bridge.send('form.fill', { values: run.result?.data ?? {} });
        else if (mode === 'replaceField' || mode === 'replaceSelection') {
          const scope = mode === 'replaceField' ? 'field' : 'selection';
          this.store.set((st) => ({ editor: replaceText(st.editor, scope, text) }));
          this.bridge.send('text.replace', { scope, text });
        } else this.insertSmart(text);
        this.store.set({ panel: 'none' });
        break;
      }
      case 'insert':
        this.insertSmart(text);
        this.store.set({ panel: 'none' });
        break;
      case 'copy':
        void navigator.clipboard?.writeText(text).catch(() => undefined);
        this.toast('Copied');
        break;
      case 'speak':
        this.bridge.send('tts.speak', { text });
        break;
      case 'retry':
        this.runAgent(run.req.skill, run.req.target, run.req.params);
        break;
    }
  }

  // ───────────── autonomy ─────────────

  /** Tell the user when the active agent's ceiling caps the chosen level (FR-AU-01). */
  private explainCeiling(): void {
    const s = this.store.get();
    const agent = s.agents.find((a) => a.id === s.activeAgentId);
    if (!agent || !s.autonomy.enabled) return;
    const order: AutonomyLevel[] = ['off', 'suggest', 'draft', 'auto'];
    if (order.indexOf(agent.autonomyCeiling) < order.indexOf(s.autonomy.level)) {
      this.toast(`${agent.name} is limited to ${agent.autonomyCeiling === 'draft' ? 'drafts — you send' : 'suggestions'}`);
    }
  }

  toggleAutonomy(): void {
    const s = this.store.get();
    if (s.field?.sensitive) return this.toast('Autopilot is off in protected fields');
    if (!s.autonomy.enabled && !s.autonomy.consent) return this.store.set({ panel: 'consent' });
    const enabled = !s.autonomy.enabled;
    // The native host keeps its own consent record and refuses to arm without it (FR-AU-02). Only this
    // consent flow writes it; re-sending on enable keeps native in sync after its data was cleared.
    if (enabled) this.bridge.send('settings.set', { key: 'autonomy.consent', value: true });
    this.store.set({ autonomy: { ...s.autonomy, enabled, phase: enabled ? 'arming' : 'off', detail: null } });
    this.bridge.send('autonomy.set', { enabled, level: s.autonomy.level, agentId: s.activeAgentId });
    this.explainCeiling();
  }

  grantConsent(level: AutonomyLevel): void {
    try {
      localStorage.setItem(CONSENT_KEY, '1');
    } catch {
      /* ignore */
    }
    this.store.set((st) => ({ autonomy: { ...st.autonomy, consent: true, level }, panel: 'none' }));
    this.toggleAutonomy();
  }

  setAutonomyLevel(level: AutonomyLevel): void {
    const a = this.store.get().autonomy;
    this.store.set({ autonomy: { ...a, level } });
    if (a.enabled) {
      this.bridge.send('autonomy.set', { enabled: true, level, agentId: this.store.get().activeAgentId });
      this.explainCeiling();
    }
  }

  // ───────────── voice ─────────────

  startVoice(): void {
    if (this.store.get().field?.sensitive) return this.toast('Voice typing is off in protected fields');
    this.store.set({ panel: 'voice', voice: { partial: '', level: 0 } });
    this.bridge.send('voice.start', { refine: settingsStore.get().voiceRefine });
  }

  stopVoice(): void {
    this.bridge.send('voice.stop', {});
  }

  // ───────────── misc ─────────────

  toast(message: string): void {
    this.store.set({ toast: message });
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.store.set({ toast: null }), 1800);
  }

  currentWord(): string {
    return currentWord(this.store.get().editor.before);
  }
}
