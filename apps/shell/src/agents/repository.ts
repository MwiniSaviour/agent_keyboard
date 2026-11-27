/**
 * Agent repository. Web build: localStorage. Native: the `:agent` service owns agents in SQLite
 * (SPEC §16) and the shell reads them via `agents.list`; companion screens write via the AgentKit plugin.
 */
import { AGENT_ROLE_MAX_CHARS, type AgentDefinition } from '@ak/protocol';
import { createStore, useStore, uid } from '@/lib/store';
import { DEFAULT_AGENTS, GENERAL_AGENT_ID } from './defaults';

const STORAGE_KEY = 'ak.agents.v1';

interface AgentsState {
  agents: AgentDefinition[];
}

export const agentsStore = createStore<AgentsState>({ agents: DEFAULT_AGENTS });

let hydrated = false;
export function hydrateAgents(): void {
  if (hydrated || typeof window === 'undefined') return;
  hydrated = true;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const saved = JSON.parse(raw) as AgentDefinition[];
      // General is guaranteed to exist (FR-AG-01) even if storage was edited.
      const hasGeneral = saved.some((a) => a.id === GENERAL_AGENT_ID);
      agentsStore.set({ agents: hasGeneral ? saved : [DEFAULT_AGENTS[0]!, ...saved] });
    }
  } catch {
    /* defaults */
  }
  agentsStore.subscribe(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(agentsStore.get().agents));
    } catch {
      /* ignore */
    }
  });
}

export type AgentDraft = Pick<
  AgentDefinition,
  'name' | 'icon' | 'tagline' | 'role' | 'tone' | 'autonomyCeiling' | 'appScope' | 'knowledge' | 'quickReplies' | 'shareGeneralMemory'
>;

export function validateDraft(d: AgentDraft): string[] {
  const errors: string[] = [];
  if (!d.name.trim()) errors.push('Name is required.');
  if (d.name.length > 40) errors.push('Name must be 40 characters or fewer.');
  if (!d.role.trim()) errors.push('Prompt is required.');
  if (d.role.length > AGENT_ROLE_MAX_CHARS) errors.push(`Prompt must be ≤ ${AGENT_ROLE_MAX_CHARS} characters.`);
  return errors;
}

export const agentRepository = {
  list: (): AgentDefinition[] => agentsStore.get().agents,
  get: (id: string): AgentDefinition | undefined => agentsStore.get().agents.find((a) => a.id === id),
  create(draft: AgentDraft): AgentDefinition {
    const now = Date.now();
    const agent: AgentDefinition = {
      ...draft,
      id: uid('agent'),
      kind: 'custom',
      languages: ['en'],
      createdAt: now,
      updatedAt: now,
      version: 1,
    };
    agentsStore.set((s) => ({ agents: [...s.agents, agent] }));
    return agent;
  },
  update(id: string, draft: Partial<AgentDraft>): void {
    agentsStore.set((s) => ({
      agents: s.agents.map((a) =>
        a.id === id ? { ...a, ...draft, updatedAt: Date.now(), version: a.version + 1 } : a,
      ),
    }));
  },
  remove(id: string): void {
    if (id === GENERAL_AGENT_ID) return; // FR-AG-01
    agentsStore.set((s) => ({ agents: s.agents.filter((a) => a.id !== id) }));
  },
};

export function useAgents(): AgentDefinition[] {
  return useStore(agentsStore, (s) => s.agents);
}
