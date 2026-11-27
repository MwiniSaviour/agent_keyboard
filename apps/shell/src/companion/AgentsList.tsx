'use client';
import Link from 'next/link';
import { GENERAL_AGENT_ID } from '@/agents/defaults';
import { useAgents } from '@/agents/repository';
import { ChevronRight, PlusIcon } from '@/ui/icons';
import { CompanionShell } from './CompanionShell';
import css from './companion.module.css';

const CEILING_LABEL = { off: 'Off', suggest: 'Suggest', draft: 'Draft', auto: 'Auto-send' } as const;

export function AgentsList() {
  return (
    <CompanionShell title="Agents" active="/agents/">
      <List />
    </CompanionShell>
  );
}

function List() {
  const agents = useAgents();
  return (
    <>
      <p className={css.help} style={{ margin: '0 0 14px' }}>
        <b>General</b> is always on and learns how you write. Create specialised agents — sales, support, tutoring — for
        specific contexts and switch with the pill on your keyboard.
      </p>
      <div className={css.list}>
        {agents.map((a) => (
          <Link key={a.id} href={`/agents/edit/?id=${encodeURIComponent(a.id)}`} className={css.item}>
            <span className={css.avatar}>{a.icon}</span>
            <span className={css.grow}>
              <div className={css.name}>{a.name}</div>
              <div className={css.muted}>{a.tagline}</div>
            </span>
            {a.id === GENERAL_AGENT_ID ? <span className={css.pill}>Default</span> : <span className={css.pill}>{CEILING_LABEL[a.autonomyCeiling]}</span>}
            <ChevronRight size={18} />
          </Link>
        ))}
        <Link href="/agents/new/" className={css.item}>
          <span className={css.avatar}>
            <PlusIcon size={20} />
          </span>
          <span className={css.grow}>
            <div className={css.name}>Create agent</div>
            <div className={css.muted}>Give it a prompt and learning resources</div>
          </span>
        </Link>
      </div>
    </>
  );
}
