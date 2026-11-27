/**
 * Mock agent runtime: deterministic, context-aware outputs per skill so the shell UX can be built
 * and demoed. Replaced by the on-device Orchestrator + LiteRT-LM in Phase 3 (SPEC §11–§12).
 * Outputs are templated on purpose: they demonstrate *which context* each skill consumes.
 */
import type { AgentDefinition, AgentRunRequest, AgentRunResult, RefineStyle, ScreenContext } from '@ak/protocol';
import { MOCK_PROFILE } from './scenarios';

export interface SkillInput {
  req: AgentRunRequest;
  agent: AgentDefinition;
  ctx: ScreenContext;
  /** Text in the focused field (whole field). */
  fieldText: string;
  /** Selected text in the field. */
  selected: string;
}

export type SkillOutput = Omit<AgentRunResult, 'runId' | 'latencyMs' | 'model'>;

const DICTIONARY: Record<string, { def: string; ex: string; syn: string[]; ant: string[] }> = {
  exigent: {
    def: 'Exigent means requiring immediate attention or action due to its urgent and pressing nature.',
    ex: 'The company faced exigent circumstances after the data breach, forcing them to overhaul their security measures.',
    syn: ['Pressing', 'Critical', 'Urgent', 'Demanding', 'Imperative'],
    ant: ['Optional', 'Nonurgent', 'Minor', 'Negligible'],
  },
  elucidate: {
    def: 'To elucidate is to make something clear by explaining it in detail.',
    ex: 'The professor used a simple diagram to elucidate the theory.',
    syn: ['Clarify', 'Explain', 'Illuminate', 'Explicate'],
    ant: ['Obscure', 'Confuse', 'Complicate'],
  },
  multifaceted: {
    def: 'Multifaceted describes something that has many different aspects or features.',
    ex: 'Climate change is a multifaceted problem involving science, economics and politics.',
    syn: ['Complex', 'Many-sided', 'Varied', 'Diverse'],
    ant: ['Simple', 'One-dimensional', 'Uniform'],
  },
  imperative: {
    def: 'Imperative means of vital importance; absolutely necessary.',
    ex: 'It is imperative that we reduce emissions this decade.',
    syn: ['Essential', 'Crucial', 'Vital', 'Critical'],
    ant: ['Optional', 'Unimportant', 'Trivial'],
  },
  pernicious: {
    def: 'Pernicious means having a harmful effect, especially in a gradual or subtle way.',
    ex: 'Misinformation has a pernicious influence on public debate.',
    syn: ['Harmful', 'Damaging', 'Destructive', 'Insidious'],
    ant: ['Beneficial', 'Harmless', 'Wholesome'],
  },
  unparalleled: {
    def: 'Unparalleled means having no equal; exceptional.',
    ex: 'The region enjoyed a period of unparalleled growth.',
    syn: ['Unmatched', 'Unequalled', 'Peerless'],
    ant: ['Ordinary', 'Common', 'Average'],
  },
  relentless: {
    def: 'Relentless means continuing without stopping or becoming less intense.',
    ex: 'The team kept up a relentless pace until launch.',
    syn: ['Persistent', 'Unceasing', 'Unremitting'],
    ant: ['Intermittent', 'Lenient', 'Yielding'],
  },
};

const CONTRACTIONS: Array<[RegExp, string]> = [
  [/\bcan't\b/gi, 'cannot'], [/\bwon't\b/gi, 'will not'], [/\bdon't\b/gi, 'do not'], [/\bit's\b/gi, 'it is'],
  [/\bi'm\b/gi, 'I am'], [/\bwe're\b/gi, 'we are'], [/\byou're\b/gi, 'you are'], [/\bthat's\b/gi, 'that is'],
  [/\bgonna\b/gi, 'going to'], [/\bwanna\b/gi, 'want to'], [/\bpls\b/gi, 'please'], [/\bthx\b/gi, 'thank you'],
];

export function fixGrammar(text: string): string {
  let t = text.replace(/\s+/g, ' ').trim();
  t = t.replace(/\bi\b/g, 'I').replace(/\s+([,.!?])/g, '$1').replace(/([,.!?])(?=[^\s\d])/g, '$1 ');
  t = t.replace(/(^|[.!?]\s+)([a-z])/g, (_, p: string, c: string) => p + c.toUpperCase());
  if (t && !/[.!?…]$/.test(t)) t += '.';
  return t;
}

export function refine(text: string, style: RefineStyle): string {
  const base = fixGrammar(text);
  if (!base) return base;
  switch (style) {
    case 'fix':
      return base;
    case 'formal': {
      let t = base;
      for (const [re, rep] of CONTRACTIONS) t = t.replace(re, rep);
      t = t.replace(/^(hi|hey|hello)\b[,!]?\s*/i, 'Good day, ').replace(/\byeah\b/gi, 'yes').replace(/\bok(ay)?\b/gi, 'certainly');
      return /regards/i.test(t) ? t : `${t} Kind regards.`;
    }
    case 'friendly':
      return /^(hi|hey|hello)/i.test(base) ? `${base.replace(/\.$/, '!')} 😊` : `Hey! ${base.replace(/\.$/, '!')} 😊`;
    case 'shorter': {
      const first = base.split(/(?<=[.!?])\s+/)[0] ?? base;
      const words = first.split(' ');
      return words.length > 14 ? `${words.slice(0, 14).join(' ')}…` : first;
    }
    case 'longer':
      return `${base} Let me know if you have any questions — happy to share more details.`;
    case 'persuasive':
      return `${base.replace(/\.$/, '')} — and it's a limited-time offer, so now is the perfect moment.`;
    case 'simplify':
      return base.replace(/\b(utilize)\b/gi, 'use').replace(/\b(approximately)\b/gi, 'about').replace(/\b(purchase)\b/gi, 'buy');
    case 'emojify':
      return base.replace(/\b(thanks|thank you)\b/gi, '$1 🙏').replace(/\b(great|perfect)\b/gi, '$1 ✨').replace(/\b(deliver(y)?)\b/gi, '$1 🚚');
  }
}

function lastInbound(ctx: ScreenContext): string {
  const msgs = ctx.chat?.messages ?? [];
  for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i]!.from === 'them') return msgs[i]!.text;
  return '';
}

/** Compose a chat reply from the last inbound message + the agent's knowledge (role prompt). */
export function composeReply(agent: AgentDefinition, ctx: ScreenContext): string {
  const inbound = lastInbound(ctx).toLowerCase();
  const name = ctx.chat?.participants[0]?.split(' ')[0] ?? '';
  const sales = agent.id === 'sales' || /price|offer|sales/i.test(agent.role);
  const parts: string[] = [];
  if (/\b(how much|price|cost)\b/.test(inbound)) {
    parts.push(sales ? 'The skincare bundle is GH₵ 250 this week, and code BEAUTY20 takes 20% off.' : 'It’s GH₵ 250.');
  }
  if (/\bdeliver(?!y)/.test(inbound) && !/pay on delivery/.test(inbound)) parts.push(sales ? 'Yes, we deliver to Kumasi within 24–48 hours.' : 'And yes, I can deliver to Kumasi in 1–2 days.');
  if (/\bpay on delivery|pay\b/.test(inbound)) parts.push('Yes, pay on delivery is fine — cash or MoMo when it arrives.');
  if (/\bwhen\b.*\barrive|\barrive\b/.test(inbound)) parts.push(/two|2/.test(inbound) ? 'Two bundles it is! They can arrive tomorrow afternoon.' : 'It can arrive tomorrow afternoon.');
  if (/\b(thanks|thank you)\b/.test(inbound)) parts.push('You’re welcome! Enjoy, and let me know how you like it.');
  if (parts.length === 0) parts.push('Thanks for reaching out! How can I help?');
  let close = '';
  if (sales && !/\b(thanks|thank you)\b/.test(inbound)) {
    close = /\b(take|two|order)\b/.test(inbound) ? ' I’ll confirm your order now.' : ' Shall I reserve one for you?';
  }
  const greet = /\b(how much|price)\b/.test(inbound) && name ? `Hi ${name}! ` : '';
  return `${greet}${parts.join(' ')}${close}`.trim();
}

export function runSkill(input: SkillInput): SkillOutput {
  const { req, agent, ctx, fieldText, selected } = input;
  switch (req.skill) {
    case 'reply':
      return { text: composeReply(agent, ctx), actions: ['apply', 'copy', 'speak', 'retry'], apply: { mode: 'replaceField' } };

    case 'refine': {
      const source = selected || fieldText;
      if (!source.trim()) return { text: 'Type something first, then refine it.', actions: [] };
      return {
        text: refine(source, req.params?.style ?? 'fix'),
        actions: ['apply', 'copy', 'retry'],
        apply: { mode: selected ? 'replaceSelection' : 'replaceField' },
      };
    }

    case 'define': {
      const word = (req.params?.word ?? ctx.text?.selection ?? '').toLowerCase();
      const d = DICTIONARY[word];
      if (!d) return { text: `“${word}”: the on-device model will define any word (Phase 3).`, actions: ['copy'] };
      return {
        text: `Explanation: ${d.def}\nExample: ${d.ex}\nSynonyms: ${d.syn.join(', ')}\nAntonyms: ${d.ant.join(', ')}`,
        actions: ['insert', 'copy', 'speak'],
        apply: { mode: 'insert' },
      };
    }

    case 'summarize': {
      if (ctx.screenType === 'chat') {
        return {
          text: `${ctx.chat?.title} is interested in the skincare bundle and asked about price and delivery to Kumasi. No reply sent yet.`,
          actions: ['copy', 'speak'],
        };
      }
      if (ctx.text) {
        return {
          text: 'The essay argues that the environmental crisis is urgent, driven by industrial growth that has polluted the air and reduced biodiversity, and calls for collective, sustainable action.',
          actions: ['insert', 'copy', 'speak'],
          apply: { mode: 'insert' },
        };
      }
      return { text: refine(fieldText, 'shorter'), actions: ['copy'] };
    }

    case 'explain': {
      const lines = [`You are in ${ctx.app.label} on a ${ctx.screenType} screen.`];
      if (ctx.chat) lines.push(`Conversation with ${ctx.chat.title}; last message: “${lastInbound(ctx)}”.`);
      if (ctx.form) lines.push(`A form with ${ctx.form.fields.length} fields (${ctx.form.fields.filter((f) => f.required).length} required). The password field is protected and will never be filled by AI.`);
      if (ctx.text) lines.push(`An essay titled “${ctx.text.title}” about the environmental crisis (${ctx.text.body.split(/\s+/).length} words).`);
      lines.push(`Focused field: ${ctx.field.hint ?? ctx.field.kind}.`);
      return { text: lines.join(' '), actions: ['copy', 'speak'] };
    }

    case 'fill_form': {
      const fields = ctx.form?.fields ?? [];
      const data: Record<string, string> = {};
      const lines: string[] = [];
      for (const f of fields) {
        if (f.kind === 'password' || f.kind === 'otp') {
          lines.push(`${f.label}: skipped (protected field)`);
          continue;
        }
        const v = f.autofillHint ? MOCK_PROFILE[f.autofillHint] : undefined;
        if (v) data[f.id] = v;
        else if (f.kind === 'multiline') data[f.id] = 'We make natural shea-butter skincare by hand in Kumasi and ship across Ghana.';
        if (data[f.id]) lines.push(`${f.label}: ${data[f.id]}`);
      }
      return { text: lines.join('\n'), data, actions: ['apply', 'retry'], apply: { mode: 'fillForm' } };
    }

    case 'translate':
      return {
        text: req.params?.language === 'fr' && /available|deliver/i.test(fieldText)
          ? 'Oui, il est disponible et je peux le livrer demain matin.'
          : `[${req.params?.language ?? 'fr'}] ${fieldText || 'Nothing to translate.'}`,
        actions: ['apply', 'copy'],
        apply: { mode: 'replaceField' },
      };

    case 'describe_image':
      return { text: 'A product photo: three amber skincare bottles on a white marble surface with green leaves.', actions: ['copy', 'speak'] };

    case 'transcribe_refine':
    case 'complete':
      return { text: refine(fieldText, 'fix'), actions: ['apply'], apply: { mode: 'replaceField' } };
  }
}
