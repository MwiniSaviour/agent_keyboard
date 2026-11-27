#!/usr/bin/env node
/**
 * Signed model manifest tooling (SPEC §12.6, ADR-0011). Zero dependencies (Node ≥ 22).
 *
 *   keygen  --key-id ak-2026-1 --out ~/.ak-keys
 *           Creates an ECDSA P-256 key pair. Prints the public key for the Gradle property
 *           `ak.modelManifestKeys=<keyId>:<base64 SPKI>`. Keep the private key offline.
 *
 *   build   --catalog models/catalog.json --key <private.pem> --key-id <id> --out models.json
 *           [--files <dir>]   use local copies (by file name) instead of streaming each url
 *           Measures every file's exact size and SHA-256 (streaming; nothing is stored), writes
 *           the manifest (schema 2) and signs it. Set HF_TOKEN for gated Hugging Face repos.
 *           Entries name files either as one `url` (already pinned to a commit) or as
 *           `hf: {repo, revision}` + `files: [{name}]`; a branch revision ("main") is resolved to its
 *           commit through the Hugging Face API, so the signed manifest never points at a moving ref.
 *
 *   sign    --manifest <manifest.json> --key <private.pem> --key-id <id> --out models.json
 *   verify  --signed models.json --public <base64 SPKI> --key-id <id>
 *   fixture Regenerates packages/protocol/fixtures/models.signed.json (+ models.public.json) with a
 *           throwaway key: the cross-language test vector the Kotlin verifier and vitest both check.
 *
 * Envelope: {"keyId", "payload": base64(manifest JSON bytes), "signature": base64(DER ECDSA-SHA256)}.
 * Signing raw bytes means no JSON canonicalisation exists to get wrong on either side.
 */
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { createReadStream, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

function args(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) out[a.slice(2)] = argv[i + 1]?.startsWith('--') || argv[i + 1] === undefined ? true : argv[++i];
    else out._.push(a);
  }
  return out;
}

function need(o, k) {
  if (!o[k] || o[k] === true) throw new Error(`missing --${k}`);
  return o[k];
}

export function envelope(manifest, privateKey, keyId) {
  const payload = Buffer.from(JSON.stringify(manifest, null, 2) + '\n', 'utf8');
  const signature = sign('sha256', payload, privateKey); // DER for EC keys
  return { keyId, payload: payload.toString('base64'), signature: signature.toString('base64') };
}

export function open(env, publicKeyB64, keyId) {
  if (env.keyId !== keyId) throw new Error(`signed with ${env.keyId}, expected ${keyId}`);
  const key = createPublicKey({ key: Buffer.from(publicKeyB64, 'base64'), format: 'der', type: 'spki' });
  const payload = Buffer.from(env.payload, 'base64');
  if (!verify('sha256', payload, key, Buffer.from(env.signature, 'base64'))) throw new Error('signature does not verify');
  return JSON.parse(payload.toString('utf8'));
}

async function measureUrl(url) {
  const headers = process.env.HF_TOKEN ? { authorization: `Bearer ${process.env.HF_TOKEN}` } : {};
  const res = await fetch(url, { headers, redirect: 'follow' });
  if (!res.ok || !res.body) throw new Error(`${url} → HTTP ${res.status}`);
  const hash = createHash('sha256');
  let size = 0;
  for await (const chunk of res.body) {
    hash.update(chunk);
    size += chunk.length;
  }
  return { sha256: hash.digest('hex'), sizeBytes: size };
}

async function measureFile(path) {
  const hash = createHash('sha256');
  let size = 0;
  for await (const chunk of createReadStream(path)) {
    hash.update(chunk);
    size += chunk.length;
  }
  return { sha256: hash.digest('hex'), sizeBytes: size };
}

const ENTRY_KEYS = [
  'id', 'kind', 'name', 'role', 'license', 'licenseUrl', 'tiers', 'coreFor', 'capabilities', 'backends', 'runtime',
  'contextTokens', 'ramMb', 'minAppVersion', 'quality', 'config',
];
const PINNED = /\/resolve\/[0-9a-f]{40}\//;

/** Commit sha for a Hugging Face branch or tag (a 40-hex revision is already a commit). */
export async function resolveRevision(repo, revision) {
  if (/^[0-9a-f]{40}$/.test(revision)) return revision;
  const headers = process.env.HF_TOKEN ? { authorization: `Bearer ${process.env.HF_TOKEN}` } : {};
  const res = await fetch(`https://huggingface.co/api/models/${repo}/revision/${encodeURIComponent(revision)}`, { headers });
  if (!res.ok) throw new Error(`${repo}@${revision} → HTTP ${res.status}`);
  const sha = (await res.json()).sha;
  if (!/^[0-9a-f]{40}$/.test(sha ?? '')) throw new Error(`${repo}@${revision}: no commit sha in the API response`);
  return sha;
}

/** The pinned URL of every file of a catalog entry. */
export async function fileUrls(m) {
  if (m.url) {
    if (!PINNED.test(m.url) && m.url.startsWith('https://huggingface.co/')) throw new Error(`${m.id}: url must be pinned to a commit`);
    return [{ name: m.file, url: m.url, approxBytes: m.approxBytes }];
  }
  if (!m.hf?.repo || !m.files?.length) throw new Error(`${m.id}: needs url, or hf + files`);
  const commit = m.files.every((f) => f.url) ? null : await resolveRevision(m.hf.repo, m.hf.revision ?? 'main');
  return m.files.map((f) => ({
    name: f.name,
    // A file may come from elsewhere (e.g. the shared VAD model): its SHA-256 is signed all the same.
    url: f.url ?? `https://huggingface.co/${m.hf.repo}/resolve/${commit}/${f.path ?? f.name}`,
    approxBytes: f.approxBytes,
  }));
}

async function build(o) {
  const catalog = JSON.parse(readFileSync(need(o, 'catalog'), 'utf8'));
  const key = createPrivateKey(readFileSync(need(o, 'key')));
  const models = [];
  for (const m of catalog.models) {
    const files = [];
    for (const f of await fileUrls(m)) {
      const local = o.files ? join(o.files, f.name) : null;
      process.stderr.write(`▸ ${m.id}/${f.name}: ${local && existsSync(local) ? local : f.url}\n`);
      const measured = local && existsSync(local) ? await measureFile(local) : await measureUrl(f.url);
      if (f.approxBytes && Math.abs(measured.sizeBytes - f.approxBytes) > f.approxBytes * 0.1) {
        process.stderr.write(`  ! size ${measured.sizeBytes} differs from approxBytes ${f.approxBytes} by >10%\n`);
      }
      files.push({ name: f.name, url: f.url, ...measured });
      process.stderr.write(`  ${measured.sizeBytes} bytes · sha256 ${measured.sha256}\n`);
    }
    const entry = Object.fromEntries(ENTRY_KEYS.filter((k) => m[k] !== undefined).map((k) => [k, m[k]]));
    models.push({ ...entry, files });
  }
  const manifest = { schema: 2, issuedAt: Number(o['issued-at'] ?? Date.now()), models };
  writeFileSync(need(o, 'out'), JSON.stringify(envelope(manifest, key, need(o, 'key-id')), null, 2) + '\n');
  process.stderr.write(`✓ wrote ${o.out} (${models.length} models)\n`);
}

function fixture() {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const keyId = 'ak-test';
  const sha = (s) => createHash('sha256').update(s).digest('hex');
  const stt = ['encoder.int8.onnx', 'decoder.onnx', 'joiner.int8.onnx', 'tokens.txt'];
  const manifest = {
    schema: 2,
    issuedAt: 1764201600000,
    models: [
      {
        id: 'gemma-3-1b-it-int4', kind: 'llm', name: 'Gemma 3 1B', role: 'Replies, rewriting, explanations and definitions',
        file: 'gemma3-1b-it-int4.litertlm', url: 'https://example.invalid/gemma3-1b-it-int4.litertlm',
        license: 'Gemma Terms of Use', licenseUrl: 'https://ai.google.dev/gemma/terms', tiers: ['S'], coreFor: ['S'],
        capabilities: ['text'], backends: ['cpu', 'gpu'], runtime: 'litertlm', contextTokens: 1024, ramMb: 900, minAppVersion: 1, quality: 40,
        // Test vector: the bytes are the ASCII string "gemma-test-bytes".
        sha256: sha('gemma-test-bytes'), sizeBytes: 16,
      },
      {
        id: 'gemma-4-e2b-it', kind: 'llm', name: 'Gemma 4 E2B', role: 'Text, vision and audio in one model',
        file: 'gemma-4-E2B-it.litertlm', url: 'https://example.invalid/gemma-4-E2B-it.litertlm',
        license: 'Apache-2.0', tiers: ['M', 'L'], coreFor: ['M', 'L'], capabilities: ['text', 'vision', 'audio'],
        backends: ['cpu', 'gpu'], runtime: 'litertlm', contextTokens: 4000, ramMb: 1500, minAppVersion: 1, quality: 70,
        sha256: sha('e2b-test-bytes'), sizeBytes: 14,
      },
      {
        // Multi-file (schema 2): a sherpa-onnx streaming transducer. File i's bytes are "stt-<name>".
        id: 'zipformer-en-20m', kind: 'stt', name: 'Zipformer streaming (English)', role: 'Voice typing, on this phone',
        license: 'Apache-2.0', tiers: ['S', 'M', 'L'], capabilities: ['transcribe'], runtime: 'sherpa-onnx', ramMb: 120,
        minAppVersion: 1, quality: 55,
        config: { encoder: stt[0], decoder: stt[1], joiner: stt[2], tokens: stt[3], modelType: 'zipformer' },
        files: stt.map((name) => ({ name, url: `https://example.invalid/stt/${name}`, sha256: sha(`stt-${name}`), sizeBytes: `stt-${name}`.length })),
      },
    ],
  };
  const dir = join(ROOT, 'packages/protocol/fixtures');
  writeFileSync(join(dir, 'models.signed.json'), JSON.stringify(envelope(manifest, privateKey, keyId), null, 2) + '\n');
  const spki = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  writeFileSync(join(dir, 'models.public.json'), JSON.stringify({ keyId, publicKey: spki, note: 'Throwaway test key; the private half was discarded.' }, null, 2) + '\n');
  process.stderr.write(`✓ wrote fixtures/models.signed.json and models.public.json\n`);
}

async function main() {
  const o = args(process.argv.slice(2));
  switch (o._[0]) {
    case 'keygen': {
      const keyId = need(o, 'key-id');
      const dir = need(o, 'out');
      mkdirSync(dir, { recursive: true });
      const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
      const priv = join(dir, `${keyId}.private.pem`);
      writeFileSync(priv, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
      const spki = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
      process.stdout.write(`private key: ${priv}\nak.modelManifestKeys=${keyId}:${spki}\n`);
      break;
    }
    case 'build':
      await build(o);
      break;
    case 'sign': {
      const manifest = JSON.parse(readFileSync(need(o, 'manifest'), 'utf8'));
      const key = createPrivateKey(readFileSync(need(o, 'key')));
      writeFileSync(need(o, 'out'), JSON.stringify(envelope(manifest, key, need(o, 'key-id')), null, 2) + '\n');
      break;
    }
    case 'verify': {
      const m = open(JSON.parse(readFileSync(need(o, 'signed'), 'utf8')), need(o, 'public'), need(o, 'key-id'));
      process.stdout.write(`✓ signature ok · schema ${m.schema} · ${m.models.length} models · issued ${new Date(m.issuedAt).toISOString()}\n`);
      for (const e of m.models) {
        const files = e.files ?? [{ name: e.file, sizeBytes: e.sizeBytes, sha256: e.sha256 }];
        process.stdout.write(`  ${e.id}  ${files.reduce((n, f) => n + f.sizeBytes, 0)} B  ${files.length} file(s)  quality ${e.quality ?? '-'}\n`);
      }
      break;
    }
    case 'fixture':
      fixture();
      break;
    default:
      process.stderr.write('usage: manifest.mjs keygen|build|sign|verify|fixture (see header)\n');
      process.exitCode = 2;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    process.stderr.write(`✗ ${e.message}\n`);
    process.exitCode = 1;
  });
}
