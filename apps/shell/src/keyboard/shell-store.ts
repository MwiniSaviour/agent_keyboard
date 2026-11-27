/**
 * Shell UI state (SPEC §7.5). Engine state lives here too so the KeyGrid can subscribe to just
 * `layer`/`shift`, keeping key re-renders to layer/shift changes only (ADR-0008).
 */
import type {
  AgentRunRequest,
  AgentRunResult,
  AutonomyLevel,
  AutonomyPhase,
  Capabilities,
  ClipboardItem,
  EditorState,
  FieldContext,
  Insight,
  ScreenCaptureStatus,
  Suggestion,
  AgentDefinition,
} from '@ak/protocol';
import { createEngineState, EMPTY_EDITOR, type EngineState } from '@ak/keyboard-engine';
import { createStore } from '@/lib/store';
import { DEFAULT_AGENTS, GENERAL_AGENT_ID } from '@/agents/defaults';

export type Panel =
  | 'none'
  | 'agents'
  | 'context'
  | 'result'
  | 'voice'
  | 'clipboard'
  | 'settings'
  | 'emoji'
  | 'consent';

export interface RunState {
  req: AgentRunRequest;
  status: 'streaming' | 'done' | 'error';
  text: string;
  result: AgentRunResult | null;
  error?: string;
}

export interface ShellState {
  ready: boolean;
  capabilities: Capabilities | null;
  field: FieldContext | null;
  editor: EditorState;
  engine: EngineState;
  panel: Panel;
  suggestions: Suggestion[];
  insight: Insight | null;
  agents: AgentDefinition[];
  activeAgentId: string;
  autonomy: { enabled: boolean; level: AutonomyLevel; phase: AutonomyPhase; detail: string | null; consent: boolean };
  run: RunState | null;
  voice: { partial: string; level: number };
  clipboard: ClipboardItem[];
  toast: string | null;
  /** Live screen capture (`screen.state`, ADR-0010). */
  screen: { status: ScreenCaptureStatus; detail: string | null };
}

export const createShellStore = () =>
  createStore<ShellState>({
    ready: false,
    capabilities: null,
    field: null,
    editor: EMPTY_EDITOR,
    engine: createEngineState(),
    panel: 'none',
    suggestions: [],
    insight: null,
    agents: DEFAULT_AGENTS,
    activeAgentId: GENERAL_AGENT_ID,
    autonomy: { enabled: false, level: 'draft', phase: 'off', detail: null, consent: false },
    run: null,
    voice: { partial: '', level: 0 },
    clipboard: [],
    toast: null,
    screen: { status: 'off', detail: null },
  });

export type ShellStore = ReturnType<typeof createShellStore>;
