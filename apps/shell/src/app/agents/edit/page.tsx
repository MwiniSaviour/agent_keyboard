'use client';
/** Static export → no dynamic segments; the agent id travels as ?id=. */
import { useEffect, useState } from 'react';
import { agentRepository } from '@/agents/repository';
import { AgentEditor } from '@/companion/AgentEditor';
import { CompanionShell } from '@/companion/CompanionShell';
import type { AgentDefinition } from '@ak/protocol';

export default function EditAgentPage() {
  const [agent, setAgent] = useState<AgentDefinition | null | undefined>(undefined);
  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get('id') ?? '';
    // CompanionShell hydrates agents in its own effect; defer one tick so the store is populated.
    const t = setTimeout(() => setAgent(agentRepository.get(id) ?? null), 0);
    return () => clearTimeout(t);
  }, []);
  return (
    <CompanionShell title={agent ? `Edit ${agent.name}` : 'Edit agent'} back="/agents/">
      {agent === undefined ? null : agent ? <AgentEditor agent={agent} /> : <p>Agent not found.</p>}
    </CompanionShell>
  );
}
