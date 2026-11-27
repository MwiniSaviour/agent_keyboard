'use client';
import { AgentEditor } from '@/companion/AgentEditor';
import { CompanionShell } from '@/companion/CompanionShell';

export default function NewAgentPage() {
  return (
    <CompanionShell title="Create agent" back="/agents/">
      <AgentEditor />
    </CompanionShell>
  );
}
