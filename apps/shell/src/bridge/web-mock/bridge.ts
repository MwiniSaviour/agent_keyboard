/**
 * WebMockBridge: simulates the native host for the browser playground. It's the executable
 * specification of native behaviour: the same messages, the same ordering and the same autonomy
 * state machine (SPEC §8, §11.6), with templated outputs instead of a model.
 */
import type {
  AgentRunRequest,
  AutonomyLevel,
  AutonomyPhase,
  Capabilities,
  ClipboardItem,
  Insight,
  NativeBridge,
  NativeEventType,
  NativeEvents,
  ScreenContext,
  ShellCommandType,
  ShellCommands,
  ShellRequestType,
  ShellRequests,
  Unsubscribe,
} from '@ak/protocol';
import { fullText } from '@ak/keyboard-engine';
import { agentRepository, agentsStore } from '@/agents/repository';
import { GENERAL_AGENT_ID } from '@/agents/defaults';
import { MockHost } from './host';
import { checkInbound, checkOutbound, RateLimiter } from './policy';
import { fixGrammar, runSkill } from './runtime';

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export class WebMockBridge implements NativeBridge {
  readonly kind = 'web-mock' as const;
  readonly host = new MockHost();
  private handlers = new Map<string, Set<(p: never) => void>>();
  private runs = new Map<string, { cancelled: boolean }>();
  private clipboard: ClipboardItem[] = [
    { id: 'c1', text: 'BEAUTY20', pinned: true, at: Date.now() - 3_600_000 },
    { id: 'c2', text: 'https://blossom.example/bundle', pinned: false, at: Date.now() - 600_000 },
    { id: 'c3', text: 'Thank you for your interest in our new collection! ✨', pinned: false, at: Date.now() - 120_000 },
  ];
  private autonomy: { enabled: boolean; level: AutonomyLevel; agentId: string; phase: AutonomyPhase; token: number } = {
    enabled: false,
    level: 'draft',
    agentId: GENERAL_AGENT_ID,
    phase: 'off',
    token: 0,
  };
  private limiter = new RateLimiter(6, 60_000);
  private voice: { timer: ReturnType<typeof setInterval> | null; words: string[]; i: number; refine: boolean } = {
    timer: null,
    words: [],
    i: 0,
    refine: true,
  };
  private unsubHost: () => void;
  private unsubAgents: () => void;

  constructor() {
    let agentsRef = agentsStore.get().agents;
    this.unsubAgents = agentsStore.subscribe(() => {
      const next = agentsStore.get().agents;
      if (next === agentsRef) return;
      agentsRef = next;
      this.emit('agents.changed', {});
    });
    this.unsubHost = this.host.onEvent((e) => {
      switch (e.type) {
        case 'scenario':
        case 'focus':
          this.emitEditorStart();
          this.emitInsight();
          if (this.autonomy.enabled) void this.autonomyTick();
          break;
        case 'text':
          this.emit('editor.state', this.host.editor());
          this.emitInsight();
          break;
        case 'selection':
          this.emitInsight();
          break;
        case 'incoming':
          this.emitInsight();
          if (this.autonomy.enabled) void this.autonomyTick();
          break;
        case 'sent':
          this.emitInsight();
          if (this.autonomy.enabled && this.autonomy.phase === 'awaiting_user') this.setPhase('observing');
          break;
      }
    });
  }

  // ───────────── NativeBridge ─────────────

  on<T extends NativeEventType>(type: T, handler: (payload: NativeEvents[T]) => void): Unsubscribe {
    let set = this.handlers.get(type);
    if (!set) this.handlers.set(type, (set = new Set()));
    set.add(handler as (p: never) => void);
    return () => set.delete(handler as (p: never) => void);
  }

  private emit<T extends NativeEventType>(type: T, payload: NativeEvents[T]): void {
    this.handlers.get(type)?.forEach((h) => (h as (p: NativeEvents[T]) => void)(payload));
  }

  send<T extends ShellCommandType>(type: T, payload: ShellCommands[T]): void {
    const p = payload as ShellCommands[keyof ShellCommands];
    const field = this.host.field();
    // Native enforcement of secure input (FR-KB-14): agent/context traffic is dropped for sensitive fields.
    if (field?.sensitive && (type === 'agent.run' || type === 'autonomy.set')) {
      if (type === 'agent.run') {
        this.emit('agent.error', { runId: (p as AgentRunRequest).runId, code: 'policy', message: 'AI is disabled in protected fields.' });
      }
      return;
    }
    switch (type) {
      case 'text.commit':
        return this.host.commit((p as ShellCommands['text.commit']).text);
      case 'text.delete': {
        const d = p as ShellCommands['text.delete'];
        return this.host.delete(d.before, d.after);
      }
      case 'text.replace': {
        const r = p as ShellCommands['text.replace'];
        return this.host.replace(r.scope, r.text);
      }
      case 'editor.cursor':
        return this.host.moveCursor((p as ShellCommands['editor.cursor']).delta);
      case 'editor.action':
        return this.host.action((p as ShellCommands['editor.action']).action);
      case 'feedback.key':
        return this.feedback();
      case 'agent.run':
        void this.run(p as AgentRunRequest);
        return;
      case 'agent.cancel': {
        const run = this.runs.get((p as ShellCommands['agent.cancel']).runId);
        if (run) run.cancelled = true;
        return;
      }
      case 'autonomy.set':
        return this.setAutonomy(p as ShellCommands['autonomy.set']);
      case 'autonomy.takeover':
        if (this.autonomy.enabled && this.autonomy.phase !== 'paused' && this.autonomy.phase !== 'observing') {
          this.autonomy.token++; // aborts in-flight typing
          this.setPhase('paused', 'You took over — draft left editable');
        }
        return;
      case 'voice.start':
        return this.voiceStart((p as ShellCommands['voice.start']).refine);
      case 'voice.stop':
        return this.voiceStop(true);
      case 'voice.cancel':
        return this.voiceStop(false);
      case 'tts.speak':
        return this.speak((p as ShellCommands['tts.speak']).text);
      case 'tts.stop':
        if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel();
        return;
      case 'clipboard.paste': {
        const item = this.clipboard.find((c) => c.id === (p as ShellCommands['clipboard.paste']).id);
        if (item) this.host.commit(item.text);
        return;
      }
      case 'clipboard.pin': {
        const { id, pinned } = p as ShellCommands['clipboard.pin'];
        this.clipboard = this.clipboard.map((c) => (c.id === id ? { ...c, pinned } : c));
        return;
      }
      case 'clipboard.delete':
        this.clipboard = this.clipboard.filter((c) => c.id !== (p as ShellCommands['clipboard.delete']).id);
        return;
      case 'form.fill':
        void this.fillForm((p as ShellCommands['form.fill']).values, 6);
        return;
      case 'app.open':
        if (typeof window !== 'undefined') window.location.href = (p as ShellCommands['app.open']).route;
        return;
      case 'settings.set':
      case 'ui.resize':
      case 'system.open':
        return;
      case 'agents.save': {
        const { agent } = p as ShellCommands['agents.save'];
        if (agentRepository.get(agent.id)) agentRepository.update(agent.id, agent);
        else agentRepository.create(agent);
        return;
      }
      case 'agents.delete':
        return agentRepository.remove((p as ShellCommands['agents.delete']).id);
    }
  }

  async request<T extends ShellRequestType>(type: T, payload: ShellRequests[T]['req']): Promise<ShellRequests[T]['res']> {
    void payload;
    switch (type) {
      case 'hello':
        // Protocol: after `hello`, native replays the current binding (editor.start + insight) so a
        // freshly (re)loaded shell never misses the field it was opened on.
        setTimeout(() => {
          this.emitEditorStart();
          this.emitInsight();
        }, 0);
        return this.capabilities() as ShellRequests[T]['res'];
      case 'context.snapshot':
        return this.snapshot() as ShellRequests[T]['res'];
      case 'clipboard.list':
        return [...this.clipboard].sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.at - a.at) as ShellRequests[T]['res'];
      case 'agents.list':
        return agentRepository.list() as ShellRequests[T]['res'];
      case 'system.status':
        return {
          imeEnabled: true,
          imeSelected: true,
          notificationAccess: true,
          screenReading: true,
          flavor: 'web',
          tier: 'M',
          totalRamMb: 0,
          modelsInstalled: false,
        } as ShellRequests[T]['res'];
    }
    throw new Error(`unknown request ${String(type)}`);
  }

  dispose(): void {
    this.unsubHost();
    this.unsubAgents();
    this.handlers.clear();
    this.voiceStop(false);
  }

  // ───────────── Simulated native internals ─────────────

  private capabilities(): Capabilities {
    return {
      protocol: 1,
      platform: 'web',
      tier: 'M',
      nativeSuggestions: false,
      voice: true,
      tts: typeof speechSynthesis !== 'undefined',
      screenContext: true,
      visualContext: true,
      autonomy: true,
    };
  }

  private emitEditorStart(): void {
    const field = this.host.field();
    if (!field) return this.emit('editor.finish', {});
    this.emit('editor.start', { field, state: this.host.editor() });
  }

  private feedback(): void {
    // Native performs View.performHapticFeedback(KEYBOARD_TAP); the web mock uses the Vibration API.
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      try {
        navigator.vibrate(6);
      } catch {
        /* unsupported */
      }
    }
  }

  /** Context Engine (SPEC §10): build the snapshot from the simulated screen. */
  snapshot(): ScreenContext {
    const sc = this.host.scenario;
    const s = this.host.store.get();
    const field = this.host.field() ?? sc.fields[0]!.field;
    const ctx: ScreenContext = {
      app: sc.app,
      screenType: sc.id === 'chat' ? 'chat' : sc.id === 'form' ? 'form' : 'article',
      field,
      sensitivity: { secureWindow: false, sensitiveField: field.sensitive, blockedApp: false },
      rung: 2,
      capturedAt: Date.now(),
      ttlMs: 120_000,
    };
    if (sc.chat) ctx.chat = { title: sc.chat.title, participants: sc.chat.participants, messages: s.messages.slice(-20) };
    if (sc.form) {
      ctx.form = {
        title: sc.form.title,
        fields: sc.form.fields.map((f) => {
          const value = f.kind === 'password' ? undefined : fullText(s.editors[f.id] ?? { before: '', after: '', selected: '' });
          return value ? { ...f, value } : { ...f };
        }),
      };
    }
    if (sc.article) {
      ctx.text = { title: sc.article.title, body: sc.article.paragraphs.join('\n\n') };
      if (s.screenSelection) ctx.text.selection = s.screenSelection;
    }
    return ctx;
  }

  private emitInsight(): void {
    const sc = this.host.scenario;
    const s = this.host.store.get();
    const field = this.host.field();
    let insight: Insight | null = null;
    if (!field || field.sensitive) insight = null;
    else if (sc.id === 'chat') {
      const last = s.messages[s.messages.length - 1];
      const empty = fullText(this.host.editor()).length === 0;
      if (last?.from === 'them' && empty) {
        const topic = /how much|price/i.test(last.text) ? 'asked about price' : 'sent a message';
        insight = { id: `reply:${last.id}`, text: `${last.author.split(' ')[0]} ${topic} · Draft reply`, run: { skill: 'reply', target: 'conversation' }, score: 0.9 };
      }
    } else if (sc.id === 'form') {
      const empty = sc.fields.filter((f) => !f.field.sensitive && fullText(s.editors[f.id] ?? { before: '', after: '', selected: '' }) === '').length;
      if (empty > 0) insight = { id: 'form', text: `${empty} fields detected · Fill form`, run: { skill: 'fill_form', target: 'screen' }, score: 0.8 };
    } else if (sc.id === 'article') {
      insight = s.screenSelection
        ? { id: `define:${s.screenSelection}`, text: `“${s.screenSelection}” selected · Define`, run: { skill: 'define', target: 'word', params: { word: s.screenSelection } }, score: 0.95 }
        : { id: 'summarize', text: 'Long essay on screen · Summarize', run: { skill: 'summarize', target: 'screen' }, score: 0.6 };
    }
    this.emit('insight.update', { insight });
  }

  private async run(req: AgentRunRequest): Promise<void> {
    const state = { cancelled: false };
    this.runs.set(req.runId, state);
    const t0 = performance.now();
    const agent = agentRepository.get(req.agentId) ?? agentRepository.get(GENERAL_AGENT_ID)!;
    const editor = this.host.editor();
    const out = runSkill({ req, agent, ctx: this.snapshot(), fieldText: fullText(editor), selected: editor.selected });

    await sleep(220); // simulated TTFT (Tier M, warm, prefix-cached): SPEC NFR-P-07
    const ttft = performance.now() - t0;
    const tokens = out.text.match(/\S+\s*|\n/g) ?? [out.text];
    for (const tok of tokens) {
      if (state.cancelled) {
        this.runs.delete(req.runId);
        this.emit('agent.error', { runId: req.runId, code: 'cancelled', message: 'Cancelled' });
        return;
      }
      this.emit('agent.token', { runId: req.runId, delta: tok });
      await sleep(22 + Math.random() * 18);
    }
    this.runs.delete(req.runId);
    this.emit('agent.done', {
      ...out,
      runId: req.runId,
      model: 'mock · gemma-4-e2b (simulated)',
      latencyMs: { ttft: Math.round(ttft), total: Math.round(performance.now() - t0) },
    });
  }

  // ───────────── Autonomy state machine (SPEC §11.6.1) ─────────────

  private setPhase(phase: AutonomyPhase, detail?: string): void {
    this.autonomy.phase = phase;
    this.emit('autonomy.phase', detail ? { phase, detail } : { phase });
  }

  private setAutonomy(p: ShellCommands['autonomy.set']): void {
    this.autonomy.token++;
    this.autonomy = { ...this.autonomy, enabled: p.enabled, level: p.level, agentId: p.agentId };
    if (!p.enabled) return this.setPhase('off');
    const field = this.host.field();
    if (!field || field.sensitive) return this.setPhase('paused', 'protected field');
    this.setPhase('arming');
    setTimeout(() => {
      if (!this.autonomy.enabled) return;
      this.setPhase('observing', this.host.scenario.id === 'article' ? 'Nothing to act on here' : undefined);
      void this.autonomyTick();
    }, 350);
  }

  private async autonomyTick(): Promise<void> {
    const a = this.autonomy;
    if (!a.enabled || (a.phase !== 'observing' && a.phase !== 'paused' && a.phase !== 'cooldown')) return;
    const token = ++a.token;
    const alive = () => this.autonomy.enabled && this.autonomy.token === token;
    const sc = this.host.scenario;
    const s = this.host.store.get();

    if (sc.id === 'chat') {
      const last = s.messages[s.messages.length - 1];
      if (!last || last.from !== 'them') return this.setPhase('observing');
      this.setPhase('planning', `Reading “${last.text.slice(0, 40)}${last.text.length > 40 ? '…' : ''}”`);
      await sleep(500);
      if (!alive()) return;

      const inbound = checkInbound(last.text);
      if (!inbound.ok) return this.setPhase('escalated', inbound.reason);
      if (!this.limiter.tryAcquire()) return this.setPhase('paused', 'Rate limit reached (6/min)');

      const agent = agentRepository.get(a.agentId) ?? agentRepository.get(GENERAL_AGENT_ID)!;
      const out = runSkill({
        req: { runId: 'auto', agentId: agent.id, skill: 'reply', target: 'conversation' },
        agent,
        ctx: this.snapshot(),
        fieldText: '',
        selected: '',
      });
      this.setPhase('review');
      await sleep(250);
      if (!alive()) return;
      const outbound = checkOutbound(out.text);
      if (!outbound.ok) return this.setPhase('escalated', outbound.reason);

      // Level ceiling: the agent's ceiling caps the requested level (FR-AU-01).
      const order: AutonomyLevel[] = ['off', 'suggest', 'draft', 'auto'];
      const level = order[Math.min(order.indexOf(a.level), order.indexOf(agent.autonomyCeiling))]!;

      this.setPhase('typing', `${agent.name} is replying`);
      this.host.replace('field', '');
      for (const ch of out.text) {
        if (!alive()) return;
        this.host.commit(ch);
        await sleep(14);
      }
      if (level !== 'auto') return this.setPhase('awaiting_user', level === 'draft' ? 'Draft ready — tap return to send' : 'Suggestion ready');

      this.setPhase('sending');
      await sleep(300);
      if (!alive()) return;
      this.host.action('send');
      this.setPhase('cooldown');
      const more = this.host.scheduleIncoming(2_600);
      await sleep(900);
      if (!alive()) return;
      this.setPhase('observing', more ? 'Waiting for Jane…' : undefined);
      return;
    }

    if (sc.id === 'form') {
      this.setPhase('planning', 'Mapping fields to your profile');
      await sleep(450);
      if (!alive()) return;
      const out = runSkill({
        req: { runId: 'auto', agentId: a.agentId, skill: 'fill_form', target: 'screen' },
        agent: agentRepository.get(a.agentId) ?? agentRepository.get(GENERAL_AGENT_ID)!,
        ctx: this.snapshot(),
        fieldText: '',
        selected: '',
      });
      this.setPhase('typing', 'Filling form');
      await this.fillForm(out.data ?? {}, 12, alive);
      if (!alive()) return;
      // Forms are never auto-submitted (FR-AU-08).
      this.setPhase('awaiting_user', 'Form filled — review & submit yourself');
      return;
    }

    this.setPhase('observing', 'Nothing to act on here');
  }

  /**
   * Writes each value into its own field (never "whatever is focused") and aborts if the scenario
   * changes or the caller's run is no longer alive — mirrors native per-node ACTION_SET_TEXT.
   */
  private async fillForm(values: Record<string, string>, charDelay: number, alive: () => boolean = () => true): Promise<void> {
    const scenario = this.host.store.get().scenarioId;
    const ok = () => alive() && this.host.store.get().scenarioId === scenario;
    for (const [fieldId, value] of Object.entries(values)) {
      if (!ok()) return;
      const def = this.host.scenario.fields.find((f) => f.id === fieldId);
      if (!def || def.field.sensitive) continue;
      this.host.focus(fieldId);
      const chars = [...value];
      for (let i = 0; i <= chars.length; i++) {
        if (!ok()) return;
        this.host.setFieldValue(fieldId, chars.slice(0, i).join(''));
        if (charDelay && i < chars.length) await sleep(charDelay);
      }
    }
  }

  // ───────────── Voice (simulated streaming STT, SPEC §13.1) ─────────────

  private voiceStart(refine: boolean): void {
    this.voiceStop(false);
    this.voice = { timer: null, words: this.host.scenario.voicePhrase.split(' '), i: 0, refine };
    let tick = 0;
    this.voice.timer = setInterval(() => {
      tick++;
      this.emit('voice.level', { rms: 0.25 + Math.abs(Math.sin(tick / 2.3)) * 0.55 + Math.random() * 0.2 });
      if (tick % 3 === 0 && this.voice.i < this.voice.words.length) {
        this.voice.i++;
        this.emit('voice.partial', { text: this.voice.words.slice(0, this.voice.i).join(' ') });
      }
      if (this.voice.i >= this.voice.words.length && tick % 3 === 0) this.voiceStop(true);
    }, 70);
  }

  private voiceStop(commit: boolean): void {
    if (this.voice.timer) clearInterval(this.voice.timer);
    const wasActive = this.voice.timer !== null;
    this.voice.timer = null;
    if (!wasActive) return;
    if (commit && this.voice.i > 0) {
      const raw = this.voice.words.slice(0, this.voice.i).join(' ');
      this.emit('voice.final', { text: this.voice.refine ? fixGrammar(raw) : raw });
    }
    this.emit('voice.end', {});
  }

  private speak(text: string): void {
    if (typeof speechSynthesis === 'undefined') return;
    speechSynthesis.cancel();
    speechSynthesis.speak(new SpeechSynthesisUtterance(text));
  }
}
