/**
 * Simulated host apps for the web playground. Each scenario provides what the native Context Engine
 * would produce (SPEC §10) so the shell can be developed and demoed without a device.
 */
import type { ChatMessage, FieldContext, FormField } from '@ak/protocol';

export type ScenarioId = 'chat' | 'form' | 'article';

export interface FieldDef {
  id: string;
  field: FieldContext;
  label: string;
}

export interface Scenario {
  id: ScenarioId;
  title: string;
  app: { package: string; label: string; category: 'chat' | 'browser' | 'docs' };
  fields: FieldDef[];
  /** `history`: older messages above the visible ones, revealed by a scroll-and-read (`context.scan`). */
  chat?: { title: string; participants: string[]; messages: ChatMessage[]; incoming: string[]; history: ChatMessage[] };
  form?: { title: string; fields: FormField[] };
  article?: { title: string; paragraphs: string[]; selectable: string[] };
  voicePhrase: string;
}

const msg = (id: string, from: 'me' | 'them', text: string, minutesAgo: number): ChatMessage => ({
  id,
  from,
  author: from === 'me' ? 'You' : 'Jane Doe',
  text,
  at: Date.now() - minutesAgo * 60_000,
});

const chatField: FieldContext = {
  package: 'com.whatsapp',
  appLabel: 'WhatsApp',
  kind: 'text',
  imeAction: 'send',
  capsMode: 'sentences',
  hint: 'Message',
  sensitive: false,
};

const formField = (over: Partial<FieldContext>): FieldContext => ({
  package: 'com.android.chrome',
  appLabel: 'Chrome',
  kind: 'text',
  imeAction: 'next',
  capsMode: 'sentences',
  sensitive: false,
  ...over,
});

export const SCENARIOS: Record<ScenarioId, Scenario> = {
  chat: {
    id: 'chat',
    title: 'Chat',
    app: { package: 'com.whatsapp', label: 'WhatsApp', category: 'chat' },
    fields: [{ id: 'message', label: 'Message', field: chatField }],
    chat: {
      title: 'Jane Doe',
      participants: ['Jane Doe'],
      messages: [
        msg('m1', 'them', 'Hi! I saw your post about the skincare bundle 😊', 12),
        msg('m2', 'me', 'Hello Jane! Yes, it’s available this week.', 11),
        msg('m3', 'them', 'How much is it, and do you deliver to Kumasi?', 1),
      ],
      history: [
        msg('h1', 'them', 'Good evening, are you the one selling shea butter sets?', 1_460),
        msg('h2', 'me', 'Yes! Shea set is GH₵ 120, the full skincare bundle is GH₵ 250.', 1_455),
        msg('h3', 'them', 'Okay, let me check with my sister and come back.', 1_450),
      ],
      incoming: [
        'Great! Can I pay on delivery?',
        'Perfect, I’ll take two. When can it arrive?',
        'Before that, can you send me your bank PIN so I can transfer directly?',
      ],
    },
    voicePhrase: 'yes it is available and I can deliver it tomorrow morning',
  },
  form: {
    id: 'form',
    title: 'Form',
    app: { package: 'com.android.chrome', label: 'Chrome', category: 'browser' },
    fields: [
      { id: 'name', label: 'Full name', field: formField({ kind: 'name', capsMode: 'words', hint: 'Full name', autofillHints: ['personName'] }) },
      { id: 'email', label: 'Email', field: formField({ kind: 'email', capsMode: 'none', hint: 'Email', autofillHints: ['emailAddress'] }) },
      { id: 'phone', label: 'Phone', field: formField({ kind: 'phone', capsMode: 'none', hint: 'Phone', autofillHints: ['phone'] }) },
      { id: 'city', label: 'City', field: formField({ kind: 'text', capsMode: 'words', hint: 'City', autofillHints: ['addressLocality'] }) },
      { id: 'about', label: 'About your business', field: formField({ kind: 'multiline', imeAction: 'none', hint: 'Tell us about your business' }) },
      { id: 'password', label: 'Password', field: formField({ kind: 'password', capsMode: 'none', imeAction: 'done', hint: 'Password', sensitive: true }) },
    ],
    form: {
      title: 'Create your seller account · Blossom Market',
      fields: [
        { id: 'name', label: 'Full name', kind: 'name', autofillHint: 'personName', required: true },
        { id: 'email', label: 'Email', kind: 'email', autofillHint: 'emailAddress', required: true },
        { id: 'phone', label: 'Phone', kind: 'phone', autofillHint: 'phone', required: true },
        { id: 'city', label: 'City', kind: 'text', autofillHint: 'addressLocality', required: false },
        { id: 'about', label: 'About your business', kind: 'multiline', required: false },
        { id: 'password', label: 'Password', kind: 'password', required: true },
      ],
    },
    voicePhrase: 'I run a small skincare shop in Kumasi selling natural shea butter products',
  },
  article: {
    id: 'article',
    title: 'Essay',
    app: { package: 'com.google.android.keep', label: 'Keep Notes', category: 'docs' },
    fields: [
      {
        id: 'comment',
        label: 'Add a note',
        field: { package: 'com.google.android.keep', appLabel: 'Keep Notes', kind: 'multiline', imeAction: 'none', capsMode: 'sentences', hint: 'Add a note', sensitive: false },
      },
    ],
    article: {
      title: 'Essay',
      paragraphs: [
        'The modern environmental crisis is an exigent issue that demands immediate attention and collective action. From atmospheric pollution to the alarming depletion of biodiversity, humanity’s relentless pursuit of industrialization and material growth has inflicted unparalleled harm on the planet.',
        'This essay seeks to elucidate the multifaceted dimensions of the environmental crisis and the imperative for sustainable solutions. One of the most pernicious consequences is the steady warming of the climate.',
      ],
      selectable: ['exigent', 'elucidate', 'multifaceted', 'imperative', 'pernicious', 'unparalleled', 'relentless'],
    },
    voicePhrase: 'this essay explains why the environmental crisis needs urgent collective action',
  },
};

/** Profile Vault (SPEC §11.7) — encrypted on device in production. */
export const MOCK_PROFILE: Record<string, string> = {
  personName: 'Ama Mensah',
  emailAddress: 'ama.mensah@example.com',
  phone: '+233 24 555 0142',
  addressLocality: 'Kumasi',
};
