/**
 * Built-in agents. "General" replaces the old "All" canned-message category and is always available
 * (FR-AG-01); "Sales" replaces "Capital" as the first customizable specialist (FR-AG-02).
 */
import type { AgentDefinition } from '@ak/protocol';

const t0 = Date.UTC(2026, 10, 27);

export const GENERAL_AGENT_ID = 'general';

export const DEFAULT_AGENTS: AgentDefinition[] = [
  {
    id: GENERAL_AGENT_ID,
    kind: 'general',
    name: 'General',
    icon: '●',
    tagline: 'Knows you. Writes like you. Always on.',
    role:
      'You are the user’s personal writing agent. Mirror their tone, length and language from the style card. ' +
      'Use remembered facts only when relevant. Never invent commitments, prices or personal data.',
    tone: 'neutral',
    languages: ['en'],
    knowledge: [],
    quickReplies: [
      { id: 'qr1', title: 'On my way', text: 'On my way — be there in 10 minutes.' },
      { id: 'qr2', title: 'Call later', text: 'Can’t talk right now, I’ll call you back shortly.' },
    ],
    appScope: 'all',
    autonomyCeiling: 'draft',
    shareGeneralMemory: true,
    createdAt: t0,
    updatedAt: t0,
    version: 1,
  },
  {
    id: 'sales',
    kind: 'custom',
    name: 'Sales Bot',
    icon: '◆',
    tagline: 'Pricing, offers and closing — on brand.',
    role:
      'You are a friendly, concise sales assistant for Blossom Beauty (natural skincare, Kumasi). ' +
      'Current offers: skincare bundle GH₵ 250; code BEAUTY20 = 20% off; buy-one-get-one with BLOSSOMBOGO. ' +
      'Delivery in Kumasi 24–48h, Accra 2–3 days; pay on delivery accepted. Always end with a clear next step.',
    tone: 'persuasive',
    languages: ['en'],
    knowledge: [
      { id: 'res1', name: 'Price list Nov 2026.pdf', kind: 'pdf', bytes: 184_000, status: 'indexed' },
      { id: 'res2', name: 'Delivery & returns FAQ.md', kind: 'md', bytes: 9_400, status: 'indexed' },
    ],
    quickReplies: [
      { id: 'qs1', title: 'Mail Collaboration', text: '🌸 We would be thrilled to collaborate with you. Here are this month’s offers…' },
      { id: 'qs2', title: 'Reply Review', text: 'Thank you for your interest in our new collection! ✨' },
    ],
    appScope: ['com.whatsapp', 'com.whatsapp.w4b', 'com.facebook.orca', 'com.google.android.gm'],
    autonomyCeiling: 'auto',
    shareGeneralMemory: false,
    createdAt: t0,
    updatedAt: t0,
    version: 1,
  },
  {
    id: 'vocabulary',
    kind: 'custom',
    name: 'Vocabulary',
    icon: 'Aa',
    tagline: 'Definitions, examples, synonyms.',
    role:
      'You are a professional translator and vocabulary expert. Explain the meaning of the word in detail: ' +
      'a clear and simple definition, at least one example sentence showing natural usage in context, ' +
      'a list of synonyms and antonyms to expand understanding.',
    tone: 'concise',
    languages: ['en'],
    knowledge: [],
    quickReplies: [],
    appScope: 'all',
    autonomyCeiling: 'suggest',
    shareGeneralMemory: false,
    createdAt: t0,
    updatedAt: t0,
    version: 1,
  },
  {
    id: 'support',
    kind: 'custom',
    name: 'Support',
    icon: '✚',
    tagline: 'Calm, empathetic customer care.',
    role:
      'You handle customer support. Acknowledge the issue, apologise once, give one concrete next step, ' +
      'and escalate refunds above GH₵ 500 to the owner.',
    tone: 'friendly',
    languages: ['en'],
    knowledge: [],
    quickReplies: [],
    appScope: 'all',
    autonomyCeiling: 'draft',
    shareGeneralMemory: false,
    createdAt: t0,
    updatedAt: t0,
    version: 1,
  },
];
