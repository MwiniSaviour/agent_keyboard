'use client';
/**
 * Create / edit agent (reference image 1: name, prompt ≤ 15,000 chars, optional learning resources),
 * extended with tone, quick replies (replacing canned messages), app scope and autonomy ceiling (SPEC §12).
 */
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { AGENT_ROLE_MAX_CHARS, type AgentDefinition, type AgentTone, type AutonomyLevel, type ResourceRef } from '@ak/protocol';
import { GENERAL_AGENT_ID } from '@/agents/defaults';
import { agentRepository, validateDraft, type AgentDraft } from '@/agents/repository';
import { uid } from '@/lib/store';
import { CloseIcon, PlusIcon } from '@/ui/icons';
import css from './companion.module.css';

const TONES: AgentTone[] = ['neutral', 'friendly', 'formal', 'persuasive', 'concise'];
const CEILINGS: Array<[AutonomyLevel, string]> = [
  ['suggest', 'Suggest only'],
  ['draft', 'Draft (I send)'],
  ['auto', 'Auto-send'],
];
const APPS: Array<[string, string]> = [
  ['com.whatsapp', 'WhatsApp'],
  ['com.whatsapp.w4b', 'WhatsApp Business'],
  ['com.google.android.apps.messaging', 'SMS'],
  ['com.facebook.orca', 'Messenger'],
  ['com.instagram.android', 'Instagram'],
  ['org.telegram.messenger', 'Telegram'],
];
const ACCEPT = '.pdf,.txt,.md,.docx';
const MAX_FILE_BYTES = 20 * 1024 * 1024;

const emptyDraft = (): AgentDraft => ({
  name: '',
  icon: '✦',
  tagline: '',
  role: '',
  tone: 'friendly',
  autonomyCeiling: 'draft',
  appScope: 'all',
  knowledge: [],
  quickReplies: [],
  shareGeneralMemory: true,
});

const toDraft = (a: AgentDefinition): AgentDraft => ({
  name: a.name,
  icon: a.icon,
  tagline: a.tagline,
  role: a.role,
  tone: a.tone,
  autonomyCeiling: a.autonomyCeiling,
  appScope: a.appScope,
  knowledge: a.knowledge,
  quickReplies: a.quickReplies,
  shareGeneralMemory: a.shareGeneralMemory,
});

function kindOf(name: string): ResourceRef['kind'] {
  const ext = name.split('.').pop()?.toLowerCase();
  return ext === 'pdf' || ext === 'md' || ext === 'docx' ? ext : 'txt';
}

export function AgentEditor({ agent }: { agent?: AgentDefinition }) {
  const router = useRouter();
  const [d, setD] = useState<AgentDraft>(() => (agent ? toDraft(agent) : emptyDraft()));
  const [errors, setErrors] = useState<string[]>([]);
  const isGeneral = agent?.id === GENERAL_AGENT_ID;
  const set = <K extends keyof AgentDraft>(k: K, v: AgentDraft[K]) => setD((p) => ({ ...p, [k]: v }));

  const save = () => {
    const errs = validateDraft(d);
    setErrors(errs);
    if (errs.length) return;
    const clean = { ...d, quickReplies: d.quickReplies.filter((q) => q.text.trim()) };
    if (agent) agentRepository.update(agent.id, clean);
    else agentRepository.create(clean);
    router.push('/agents/');
  };

  const onFiles = (files: FileList | null) => {
    if (!files) return;
    const refs: ResourceRef[] = [...files]
      .filter((f) => f.size <= MAX_FILE_BYTES)
      .map((f) => ({ id: uid('res'), name: f.name, kind: kindOf(f.name), bytes: f.size, status: 'pending' }));
    set('knowledge', [...d.knowledge, ...refs]);
  };

  const toggleApp = (pkg: string) => {
    const cur = d.appScope === 'all' ? [] : d.appScope;
    const next = cur.includes(pkg) ? cur.filter((p) => p !== pkg) : [...cur, pkg];
    set('appScope', next.length ? next : 'all');
  };

  const over = d.role.length > AGENT_ROLE_MAX_CHARS;

  return (
    <>
      <section className={css.card}>
        <label className={css.label} htmlFor="name" style={{ marginTop: 0 }}>
          Name
        </label>
        <div className={css.row} style={{ flexWrap: 'nowrap' }}>
          <input
            className={css.field}
            style={{ width: 58, textAlign: 'center', flex: 'none' }}
            value={d.icon}
            maxLength={2}
            aria-label="Icon"
            onChange={(e) => set('icon', e.target.value || '✦')}
          />
          <input id="name" className={css.field} placeholder="e.g. Sales Bot" value={d.name} maxLength={40} disabled={isGeneral} onChange={(e) => set('name', e.target.value)} />
        </div>

        <label className={css.label} htmlFor="tagline">
          Short description
        </label>
        <input id="tagline" className={css.field} placeholder="Shown in the agent switcher" value={d.tagline} maxLength={80} onChange={(e) => set('tagline', e.target.value)} />

        <label className={css.label} htmlFor="role">
          Prompt
        </label>
        <textarea
          id="role"
          className={css.field}
          placeholder="Describe who this agent is, what it knows, how it should talk and what it must never do. e.g. “You are the sales assistant for Blossom Beauty in Kumasi. Products: …”"
          value={d.role}
          onChange={(e) => set('role', e.target.value)}
        />
        <div className={css.counter} style={over ? { color: '#b00020' } : undefined}>
          {d.role.length.toLocaleString()}/{AGENT_ROLE_MAX_CHARS.toLocaleString()}
        </div>
      </section>

      <section className={css.card}>
        <div className={css.name}>Learning resources (optional)</div>
        <p className={css.help}>PDF, TXT, MD or DOCX. Indexed on this device and used to ground answers — never uploaded.</p>
        <label className={css.drop} style={{ marginTop: 10 }}>
          <PlusIcon size={22} />
          Add files
          <span className={css.muted}>Up to 20 MB each</span>
          <input type="file" multiple accept={ACCEPT} onChange={(e) => onFiles(e.target.files)} />
        </label>
        {d.knowledge.length ? (
          <div className={css.list} style={{ marginTop: 10 }}>
            {d.knowledge.map((k) => (
              <div key={k.id} className={css.item}>
                <span className={css.pill}>{k.kind}</span>
                <span className={css.grow}>
                  <div className={css.name} style={{ fontSize: 14 }}>
                    {k.name}
                  </div>
                  <div className={css.muted}>
                    {(k.bytes / 1024).toFixed(0)} KB · {k.status === 'pending' ? 'will index on device' : k.status}
                  </div>
                </span>
                <button type="button" className={css.iconBtn} aria-label={`Remove ${k.name}`} onClick={() => set('knowledge', d.knowledge.filter((x) => x.id !== k.id))}>
                  <CloseIcon size={16} />
                </button>
              </div>
            ))}
          </div>
        ) : null}
      </section>

      <section className={css.card}>
        <div className={css.name}>Tone</div>
        <div className={css.seg} style={{ marginTop: 8 }}>
          {TONES.map((t) => (
            <button type="button" key={t} aria-pressed={d.tone === t} onClick={() => set('tone', t)}>
              {t[0]!.toUpperCase() + t.slice(1)}
            </button>
          ))}
        </div>

        <div className={css.name} style={{ marginTop: 18 }}>
          Quick replies
        </div>
        <p className={css.help}>One-tap messages for this agent (replaces canned messages).</p>
        <div style={{ marginTop: 8 }}>
          {d.quickReplies.map((q, i) => (
            <div key={q.id} className={css.qr}>
              <input
                className={css.field}
                placeholder="Title"
                value={q.title}
                onChange={(e) => set('quickReplies', d.quickReplies.map((x, j) => (j === i ? { ...x, title: e.target.value } : x)))}
              />
              <input
                className={css.field}
                placeholder="Message"
                value={q.text}
                onChange={(e) => set('quickReplies', d.quickReplies.map((x, j) => (j === i ? { ...x, text: e.target.value } : x)))}
              />
              <button type="button" className={css.iconBtn} aria-label="Remove quick reply" onClick={() => set('quickReplies', d.quickReplies.filter((_, j) => j !== i))}>
                <CloseIcon size={16} />
              </button>
            </div>
          ))}
          <button type="button" className={css.ghost} style={{ height: 38 }} onClick={() => set('quickReplies', [...d.quickReplies, { id: uid('qr'), title: '', text: '' }])}>
            + Add quick reply
          </button>
        </div>
      </section>

      <section className={css.card}>
        <div className={css.name}>Autopilot</div>
        <p className={css.help}>The most this agent may do when AUTO is on. Payments, passwords and OTPs always need you.</p>
        <div className={css.seg} style={{ marginTop: 8 }}>
          {CEILINGS.map(([l, label]) => (
            <button type="button" key={l} aria-pressed={d.autonomyCeiling === l} onClick={() => set('autonomyCeiling', l)}>
              {label}
            </button>
          ))}
        </div>
        <div className={css.name} style={{ marginTop: 18 }}>
          Works in
        </div>
        <div className={css.seg} style={{ marginTop: 8 }}>
          <button type="button" aria-pressed={d.appScope === 'all'} onClick={() => set('appScope', 'all')}>
            All apps
          </button>
          {APPS.map(([pkg, label]) => (
            <button type="button" key={pkg} aria-pressed={d.appScope !== 'all' && d.appScope.includes(pkg)} onClick={() => toggleApp(pkg)}>
              {label}
            </button>
          ))}
        </div>
        {!isGeneral ? (
          <div className={css.setRow} style={{ marginTop: 10 }}>
            <span>
              Use what General knows about me
              <div className={css.muted}>Your writing style and profile, kept on device</div>
            </span>
            <button type="button" role="switch" aria-checked={d.shareGeneralMemory} className={css.switch} onClick={() => set('shareGeneralMemory', !d.shareGeneralMemory)} aria-label="Share General memory" />
          </div>
        ) : null}
      </section>

      {errors.length ? (
        <ul className={css.errors} role="alert">
          {errors.map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      ) : null}

      <div className={css.footer}>
        {agent && !isGeneral ? (
          <button
            type="button"
            className={css.danger}
            onClick={() => {
              if (confirm(`Delete ${agent.name}? Its memory on this device is removed too.`)) {
                agentRepository.remove(agent.id);
                router.push('/agents/');
              }
            }}
          >
            Delete
          </button>
        ) : null}
        <button type="button" className={css.primary} onClick={save} disabled={over}>
          {agent ? 'Save' : 'Create'}
        </button>
      </div>
    </>
  );
}
