/**
 * Bridge message catalog v1 (SPEC §8.2). Additive changes only within a major version.
 */
import type {
  AgentDefinition,
  AgentRunRequest,
  AgentRunResult,
  AutonomyLevel,
  AutonomyPhase,
  Capabilities,
  ClipboardItem,
  EditorState,
  FieldContext,
  ImeAction,
  Insight,
  ModelsStatus,
  ScreenCaptureStatus,
  ScreenContext,
  Suggestion,
  SystemSettingsTarget,
  SystemStatus,
  ThemeMode,
} from './domain';

export const PROTOCOL_VERSION = 1 as const;

export interface Envelope<T = unknown> {
  v: typeof PROTOCOL_VERSION;
  /** Monotonic per sender: "s:<n>" from the shell, "n:<n>" from native. */
  id: string;
  type: string;
  /** ms since epoch — used for end-to-end latency tracing (SPEC §18). */
  ts: number;
  /** Correlation id for responses (`<type>.result` / `error`). */
  re?: string;
  payload: T;
}

export type DeleteUnit = 'char' | 'grapheme' | 'word';
export type FeedbackKind = 'tap' | 'delete' | 'space' | 'return' | 'mod';

type Empty = Record<string, never>;

/** Fire-and-forget commands: shell → native. */
export interface ShellCommands {
  'text.commit': { text: string };
  /** Unicode code points (not UTF-16 units) before/after the cursor, ≤ 10,000 each (SPEC §8.2). */
  'text.delete': { before: number; after: number };
  /** Replace the current selection, or the whole field when `scope` is "field". Atomic (one undo step). */
  'text.replace': { scope: 'selection' | 'field' | 'word'; text: string };
  'editor.action': { action: Exclude<ImeAction, 'none'> };
  /** Cursor steps; negative = left. Android applies one D-pad step per unit (≈ one grapheme). */
  'editor.cursor': { delta: number };
  'feedback.key': { kind: FeedbackKind };
  'agent.run': AgentRunRequest;
  'agent.cancel': { runId: string };
  'autonomy.set': { enabled: boolean; level: AutonomyLevel; agentId: string };
  /** User touched the keyboard while autonomy was active → pause immediately (FR-AU-03). */
  'autonomy.takeover': Empty;
  'voice.start': { refine: boolean };
  'voice.stop': Empty;
  'voice.cancel': Empty;
  'tts.speak': { text: string };
  'tts.stop': Empty;
  'clipboard.paste': { id: string };
  'clipboard.pin': { id: string; pinned: boolean };
  'clipboard.delete': { id: string };
  'settings.set': { key: string; value: unknown };
  'app.open': { route: string };
  'form.fill': { values: Record<string, string> };
  /** Upsert an agent. The `:agent` service validates and owns persistence (SPEC §16). */
  'agents.save': { agent: AgentDefinition };
  /** Delete a custom agent. General can never be deleted (FR-AG-01). */
  'agents.delete': { id: string };
  /** Open a system settings screen from the companion's setup checklist. */
  'system.open': { target: SystemSettingsTarget };
  /**
   * The keyboard surface's rendered height in CSS px (= dp in the IME WebView). The IME sizes its
   * window to it, so height settings and panels never clip or leave a gap (SPEC §9). Integer, 120–600.
   */
  'ui.resize': { height: number };
  /**
   * Start/stop live screen capture (ADR-0010). Starting shows the system MediaProjection consent
   * dialog every session; frames stay in memory, are downscaled, and pause in secure fields and blocked
   * apps. Progress arrives as `screen.state`.
   */
  'screen.capture': { enabled: boolean };
  /**
   * Queue packs for download (online builds) in order; ids come from `models.status`. Progress arrives
   * as `models.changed`. Downloads resume from where they stopped and are verified before use.
   */
  'models.download': { ids: string[] };
  /** Pause a queued or running download; the partial file is kept so it can resume. */
  'models.cancel': { id: string };
  /** Remove an installed pack (or a partial download) from the phone. */
  'models.delete': { id: string };
  /** Companion only: open the system file picker to install packs or a signed catalog from files. */
  'models.import': Empty;
}

/** Request/response pairs: shell → native → shell. */
export interface ShellRequests {
  hello: { req: { shellVersion: string }; res: Capabilities };
  'context.snapshot': { req: { scope: 'field' | 'screen' | 'visual' }; res: ScreenContext };
  /**
   * Scroll the app under the keyboard and read up to `pages` screens (1–8), then scroll back
   * (ADR-0010). Only ever sent from an explicit user tap; the host refuses it in secure fields.
   * `up` reads older content (chats), `down` reads further (articles).
   */
  'context.scan': { req: { direction: 'up' | 'down'; pages: number }; res: ScreenContext };
  'clipboard.list': { req: Empty; res: ClipboardItem[] };
  'agents.list': { req: Empty; res: AgentDefinition[] };
  'system.status': { req: Empty; res: SystemStatus };
  'models.status': { req: Empty; res: ModelsStatus };
}

/** Events: native → shell. */
export interface NativeEvents {
  'editor.start': { field: FieldContext; state: EditorState };
  'editor.state': EditorState;
  'editor.finish': Empty;
  'suggestions.update': { items: Suggestion[] };
  'insight.update': { insight: Insight | null };
  'agent.token': { runId: string; delta: string };
  'agent.done': AgentRunResult;
  'agent.error': { runId: string; code: 'cancelled' | 'unavailable' | 'policy' | 'internal'; message: string };
  /** `consent` reports the native consent record (the source of truth, FR-AU-02) when known. */
  'autonomy.phase': { phase: AutonomyPhase; detail?: string; consent?: boolean };
  'voice.partial': { text: string };
  'voice.final': { text: string };
  'voice.level': { rms: number };
  /** Dictation stopped. `detail` is a user-presentable reason when it stopped on an error. */
  'voice.end': { detail?: string };
  'theme.update': { mode: ThemeMode; systemDark: boolean };
  'agents.changed': Empty;
  'screen.state': { status: ScreenCaptureStatus; detail?: string };
  /** Pack catalog, download progress or residency changed (throttled to ≤ 2/s while downloading). */
  'models.changed': ModelsStatus;
}

/** Error payload for `{type: "error", re}` responses. `code` is stable; `message` is user-presentable. */
export interface ErrorPayload {
  code: 'unknown_type' | 'bad_payload' | 'secure_field' | 'unavailable' | 'internal' | 'version';
  message: string;
}

export type ShellCommandType = keyof ShellCommands;
export type ShellRequestType = keyof ShellRequests;
export type NativeEventType = keyof NativeEvents;

export type Unsubscribe = () => void;

/**
 * The single seam between UI and platform. Implemented by:
 *  - ImeBridge (Android IME WebView, androidx.webkit WebMessageListener) — ADR-0002
 *  - CapacitorBridge (companion app, Capacitor plugin)
 *  - WebMockBridge (browser playground / tests)
 */
export interface NativeBridge {
  readonly kind: 'ime' | 'capacitor' | 'web-mock';
  send<T extends ShellCommandType>(type: T, payload: ShellCommands[T]): void;
  request<T extends ShellRequestType>(
    type: T,
    payload: ShellRequests[T]['req'],
    opts?: { timeoutMs?: number },
  ): Promise<ShellRequests[T]['res']>;
  on<T extends NativeEventType>(type: T, handler: (payload: NativeEvents[T]) => void): Unsubscribe;
  dispose(): void;
}

export class BridgeError extends Error {
  constructor(
    readonly code: 'timeout' | 'version' | 'unavailable' | 'remote',
    message: string,
  ) {
    super(message);
    this.name = 'BridgeError';
  }
}
