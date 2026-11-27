/**
 * Shared bridge contract fixtures (SPEC §19.1).
 *
 * This file is the typed source: each map is *exhaustive* over its message union, so adding a message
 * to messages.ts without a fixture fails `tsc`. `contract.test.ts` serialises it to `contract.json`,
 * which the Kotlin suite (`ContractFixturesTest`) replays against the native codec. A message that
 * changes shape on one side therefore fails CI on the other.
 *
 * Regenerate after an intentional change: `UPDATE_FIXTURES=1 npm test -w @ak/protocol`.
 */
import type {
  AgentDefinition,
  ErrorPayload,
  NativeEventType,
  NativeEvents,
  ShellCommandType,
  ShellCommands,
  ShellRequestType,
  ShellRequests,
} from '../src';

const salesAgent: AgentDefinition = {
  id: 'agent_sales',
  kind: 'custom',
  name: 'Sales Bot',
  icon: 'SB',
  tagline: 'Closes deals politely',
  role: 'You are a friendly sales assistant for a phone accessories shop in Kumasi.',
  tone: 'friendly',
  languages: ['en'],
  knowledge: [{ id: 'res_1', name: 'price-list.pdf', kind: 'pdf', bytes: 48213, status: 'indexed' }],
  quickReplies: [{ id: 'qr_1', title: 'Delivery', text: 'We deliver within Kumasi in 24 hours.' }],
  appScope: ['com.whatsapp', 'com.whatsapp.w4b'],
  autonomyCeiling: 'auto',
  shareGeneralMemory: false,
  createdAt: 1764201600000,
  updatedAt: 1764205200000,
  version: 3,
};

const generalAgent: AgentDefinition = {
  id: 'general',
  kind: 'general',
  name: 'General',
  icon: '',
  tagline: 'Learns how you write',
  role: 'You are the user’s personal writing assistant. Match their tone.',
  tone: 'neutral',
  languages: ['en'],
  knowledge: [],
  quickReplies: [],
  appScope: 'all',
  autonomyCeiling: 'draft',
  shareGeneralMemory: true,
  createdAt: 1764201600000,
  updatedAt: 1764201600000,
  version: 1,
};

/** One valid payload per shell → native command. */
export const commands: { [K in ShellCommandType]: ShellCommands[K] } = {
  'text.commit': { text: 'Hello 👋🏽 "quoted" \\ back\nslash' },
  'text.delete': { before: 1, after: 0 },
  'text.replace': { scope: 'word', text: 'exigent' },
  'editor.action': { action: 'send' },
  'editor.cursor': { delta: -3 },
  'feedback.key': { kind: 'mod' },
  'agent.run': {
    runId: 'run_42',
    agentId: 'agent_sales',
    skill: 'refine',
    target: 'field',
    params: { style: 'formal', language: 'en' },
  },
  'agent.cancel': { runId: 'run_42' },
  'autonomy.set': { enabled: true, level: 'draft', agentId: 'agent_sales' },
  'autonomy.takeover': {},
  'voice.start': { refine: true },
  'voice.stop': {},
  'voice.cancel': {},
  'tts.speak': { text: 'Exigent means pressing or demanding.' },
  'tts.stop': {},
  'clipboard.paste': { id: 'clip_7' },
  'clipboard.pin': { id: 'clip_7', pinned: true },
  'clipboard.delete': { id: 'clip_7' },
  'settings.set': { key: 'keyboard.height', value: 'L' },
  'app.open': { route: '/agents/new' },
  'form.fill': { values: { emailAddress: 'ama@example.com', personName: 'Ama Mensah' } },
  'agents.save': { agent: salesAgent },
  'agents.delete': { id: 'agent_sales' },
  'system.open': { target: 'ime_picker' },
  'ui.resize': { height: 268 },
};

/** One valid request and a representative native response per request type. */
export const requests: { [K in ShellRequestType]: { req: ShellRequests[K]['req']; res: ShellRequests[K]['res'] } } = {
  hello: {
    req: { shellVersion: '0.1.0' },
    res: {
      protocol: 1,
      platform: 'android-ime',
      tier: 'S',
      nativeSuggestions: false,
      voice: false,
      tts: true,
      screenContext: true,
      visualContext: false,
      autonomy: true,
    },
  },
  'context.snapshot': {
    req: { scope: 'screen' },
    res: {
      app: { package: 'com.whatsapp', label: 'WhatsApp', category: 'chat' },
      screenType: 'chat',
      field: {
        package: 'com.whatsapp',
        appLabel: 'WhatsApp',
        kind: 'multiline',
        imeAction: 'newline',
        capsMode: 'sentences',
        hint: 'Message',
        sensitive: false,
      },
      chat: {
        title: 'Kofi',
        participants: ['Kofi'],
        messages: [{ id: 'm1', from: 'them', author: 'Kofi', text: 'Do you deliver to Kumasi?', at: 1764205000000 }],
      },
      sensitivity: { secureWindow: false, sensitiveField: false, blockedApp: false },
      rung: 1,
      capturedAt: 1764205001000,
      ttlMs: 30000,
    },
  },
  'clipboard.list': {
    req: {},
    res: [
      { id: 'clip_7', text: 'Kejetia Market, Kumasi', pinned: true, at: 1764200000000 },
      { id: 'clip_8', text: 'See you at 5', pinned: false, at: 1764204000000 },
    ],
  },
  'agents.list': { req: {}, res: [generalAgent, salesAgent] },
  'system.status': {
    req: {},
    res: {
      imeEnabled: true,
      imeSelected: false,
      notificationAccess: false,
      screenReading: false,
      flavor: 'play',
      tier: 'S',
      totalRamMb: 3712,
      modelsInstalled: false,
    },
  },
};

/** One payload per native → shell event. */
export const events: { [K in NativeEventType]: NativeEvents[K] } = {
  'editor.start': {
    field: {
      package: 'com.android.chrome',
      appLabel: 'Chrome',
      kind: 'email',
      imeAction: 'next',
      capsMode: 'none',
      hint: 'Email',
      autofillHints: ['emailAddress'],
      sensitive: false,
    },
    state: { before: 'ama@', after: '', selected: '' },
  },
  'editor.state': { before: 'Hello wor', after: '', selected: '' },
  'editor.finish': {},
  'suggestions.update': { items: [{ text: 'world', kind: 'completion', primary: true }, { text: 'word', kind: 'correction' }] },
  'insight.update': { insight: null },
  'agent.token': { runId: 'run_42', delta: 'Dear ' },
  'agent.done': {
    runId: 'run_42',
    text: 'Dear Kofi, yes, we deliver to Kumasi.',
    actions: ['apply', 'copy'],
    apply: { mode: 'replaceField' },
    model: 'none',
    latencyMs: { ttft: 0, total: 0 },
  },
  'agent.error': { runId: 'run_42', code: 'unavailable', message: 'AI models are not installed yet.' },
  'autonomy.phase': { phase: 'paused', detail: 'Off in secure fields' },
  'voice.partial': { text: 'hello' },
  'voice.final': { text: 'Hello there.' },
  'voice.level': { rms: 0.25 },
  'voice.end': {},
  'theme.update': { mode: 'system', systemDark: true },
  'agents.changed': {},
};

/** Messages the native side must reject, with the stable error code it reports (for requests) or logs. */
export const rejected: Array<{ name: string; raw: string; code: ErrorPayload['code'] | 'dropped' }> = [
  { name: 'not json', raw: '{"v":1,', code: 'dropped' },
  { name: 'not an object', raw: '[1,2]', code: 'dropped' },
  { name: 'newer protocol', raw: '{"v":2,"id":"s:1","type":"hello","ts":1,"payload":{"shellVersion":"9"}}', code: 'version' },
  { name: 'missing payload', raw: '{"v":1,"id":"s:2","type":"text.commit","ts":1}', code: 'dropped' },
  { name: 'unknown type', raw: '{"v":1,"id":"s:3","type":"text.explode","ts":1,"payload":{}}', code: 'unknown_type' },
  { name: 'negative delete', raw: '{"v":1,"id":"s:4","type":"text.delete","ts":1,"payload":{"before":-1,"after":0}}', code: 'bad_payload' },
  { name: 'fractional delete', raw: '{"v":1,"id":"s:5","type":"text.delete","ts":1,"payload":{"before":1.5,"after":0}}', code: 'bad_payload' },
  { name: 'unknown action', raw: '{"v":1,"id":"s:6","type":"editor.action","ts":1,"payload":{"action":"launch"}}', code: 'bad_payload' },
  { name: 'action none', raw: '{"v":1,"id":"s:7","type":"editor.action","ts":1,"payload":{"action":"none"}}', code: 'bad_payload' },
  { name: 'unknown skill', raw: '{"v":1,"id":"s:8","type":"agent.run","ts":1,"payload":{"runId":"r","agentId":"a","skill":"hack","target":"field"}}', code: 'bad_payload' },
  { name: 'bad snapshot scope', raw: '{"v":1,"id":"s:9","type":"context.snapshot","ts":1,"payload":{"scope":"everything"}}', code: 'bad_payload' },
  { name: 'protocol-relative route', raw: '{"v":1,"id":"s:10","type":"app.open","ts":1,"payload":{"route":"//evil.example"}}', code: 'bad_payload' },
  { name: 'absurd keyboard height', raw: '{"v":1,"id":"s:12","type":"ui.resize","ts":1,"payload":{"height":5000}}', code: 'bad_payload' },
  { name: 'agent with bad ceiling', raw: '{"v":1,"id":"s:11","type":"agents.save","ts":1,"payload":{"agent":{"id":"x","kind":"custom","name":"X","role":"r","tone":"neutral","autonomyCeiling":"yolo","appScope":"all"}}}', code: 'bad_payload' },
];
