'use client';
/**
 * Models (SPEC §12, ADR-0011, ADR-0012). Live state from `:agent`'s ModelService: `models.status` on
 * open (which also lets queued downloads start; transfers only ever begin from this screen), then
 * `models.changed` pushes. Every model is judged against this phone's memory and speed (`fit`); the
 * screen leads with the recommended ones, lets the user pick which installed model does each job, and
 * lists model files already on the phone. In the browser the web mock simulates the same lifecycle.
 */
import { useEffect, useState } from 'react';
import type { FoundModel, ModelFit, ModelKind, ModelPack, ModelsStatus, NativeBridge } from '@ak/protocol';
import { CompanionShell } from './CompanionShell';
import css from './companion.module.css';

const BACKEND_LABEL = { npu: 'NPU', gpu: 'GPU', cpu: 'CPU' } as const;
const FIT_LABEL: Record<ModelFit, string> = { recommended: 'Recommended', good: 'Runs well', slow: 'Slow here', too_big: 'Too big' };
const KIND_LABEL: Record<ModelKind, string> = { llm: 'Text', vlm: 'Text and vision', stt: 'Voice typing', embedding: 'Search', tts: 'Speech' };

export function fmtBytes(n: number): string {
  if (n >= 1e9) return `${(n / 1e9).toFixed(2)} GB`;
  if (n >= 1e6) return `${Math.round(n / 1e6)} MB`;
  return `${Math.max(0, Math.round(n / 1e3))} KB`;
}

/** Why there is nothing to download, in the user's terms. Null when the catalog is usable. */
export function catalogNotice(s: ModelsStatus): string | null {
  const detail = s.catalogDetail ? ` ${s.catalogDetail}` : '';
  switch (s.catalog) {
    case 'ready':
      return s.catalogDetail ?? null;
    case 'loading':
      return 'Checking the model catalog…';
    case 'unconfigured':
      return 'This build has no model catalog configured, so no models can be installed.';
    case 'unavailable':
      return s.source === 'import'
        ? 'Import a model file you copied to this phone. It is checked against the signed catalog.' + detail
        : 'Can’t reach the model catalog right now. Connect to the internet and reopen this screen.' + detail;
    case 'invalid':
      return 'The model catalog failed its signature check and was ignored.' + detail;
  }
}

type Action = { label: string; run: () => void; kind: 'primary' | 'ghost' | 'danger' } | null;

/** The one action a pack row offers in its current state. */
export function packAction(p: ModelPack, source: ModelsStatus['source'], b: NativeBridge): Action {
  switch (p.state) {
    case 'available':
      return source === 'download' && p.fit !== 'too_big'
        ? {
            label: 'Download',
            kind: 'ghost',
            run: () => {
              if (p.fit !== 'slow' || confirm(`${p.name} will be slow on this phone or may close other apps while it works. Download anyway?`)) {
                b.send('models.download', { ids: [p.id] });
              }
            },
          }
        : null;
    case 'queued':
    case 'downloading':
      return { label: 'Pause', kind: 'ghost', run: () => b.send('models.cancel', { id: p.id }) };
    case 'paused':
    case 'failed':
      return source === 'download'
        ? { label: p.state === 'failed' ? 'Retry' : 'Resume', kind: 'ghost', run: () => b.send('models.download', { ids: [p.id] }) }
        : { label: 'Remove', kind: 'danger', run: () => b.send('models.delete', { id: p.id }) };
    case 'installed':
      return p.updateAvailable && source === 'download'
        ? { label: 'Update', kind: 'ghost', run: () => b.send('models.download', { ids: [p.id] }) }
        : {
            label: 'Delete',
            kind: 'danger',
            run: () => {
              const again = p.custom ? 'add it again from the phone' : 'download it again';
              if (confirm(`Delete ${p.name}? Features that need it stop working until you ${again}.`)) b.send('models.delete', { id: p.id });
            },
          };
  }
}

/** Installed but not the model doing its job: offer to switch to it. */
export function switchAction(p: ModelPack, b: NativeBridge): Action {
  return p.state === 'installed' && !p.active ? { label: 'Use', kind: 'ghost', run: () => b.send('models.use', { id: p.id }) } : null;
}

/** The install action for a model found on the phone; unverified files need an explicit trust. */
export function foundAction(f: FoundModel, b: NativeBridge): NonNullable<Action> {
  if (f.verified) return { label: 'Install', kind: 'ghost', run: () => b.send('models.adopt', { id: f.id }) };
  return {
    label: 'Use…',
    kind: 'ghost',
    run: () => {
      const ok = confirm(
        `${f.name} isn’t in Agent Keyboard’s signed catalog, so it can’t be checked. A model file from an untrusted source ` +
          `can give wrong or harmful answers. It is copied into the app (${fmtBytes(f.sizeBytes)}) and runs offline.\n\nUse it only if you trust where it came from. Continue?`,
      );
      if (ok) b.send('models.adopt', { id: f.id, trust: true });
    },
  };
}

/** Where the scan may look, in the user's terms. */
export function scanScope(s: NonNullable<ModelsStatus['scan']>): string {
  const folders = s.folders === 1 ? '1 folder you chose' : `${s.folders} folders you chose`;
  switch (s.access) {
    case 'all':
      return 'Looks everywhere in this phone’s storage.';
    case 'folders':
      return `Looks in Downloads and ${folders}.`;
    case 'app':
      return 'Looks in Downloads. Choose a folder to look somewhere else.';
  }
}

function stateLine(p: ModelPack): string {
  const pct = p.sizeBytes > 0 ? Math.floor((p.receivedBytes / p.sizeBytes) * 100) : 0;
  switch (p.state) {
    case 'available':
      return fmtBytes(p.sizeBytes);
    case 'queued':
      return `Waiting · ${fmtBytes(p.sizeBytes)}`;
    case 'downloading':
      return `${fmtBytes(p.receivedBytes)} of ${fmtBytes(p.sizeBytes)} · ${pct}%`;
    case 'paused':
      return `${p.detail ?? 'Paused'} · ${pct}%`;
    case 'failed':
      return p.detail ?? 'Couldn’t install';
    case 'installed':
      return p.updateAvailable ? `Installed · update available` : `Installed · ${fmtBytes(p.sizeBytes)}`;
  }
}

export function ModelsView() {
  return (
    <CompanionShell title="Models" active="/models/">
      <Body />
    </CompanionShell>
  );
}

function Body() {
  const [bridge, setBridge] = useState<NativeBridge | null>(null);
  const [status, setStatus] = useState<ModelsStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    let off: (() => void) | undefined;
    void (async () => {
      try {
        const { loadBridge } = await import('@/bridge');
        const b = await loadBridge();
        if (!alive) return;
        setBridge(b);
        off = b.on('models.changed', (s) => alive && setStatus(s));
        const s = await b.request('models.status', {});
        if (alive) setStatus(s);
      } catch {
        if (alive) setError('Model status is only available in the Agent Keyboard app.');
      }
    })();
    return () => {
      alive = false;
      off?.();
    };
  }, []);

  if (error) return <p className={css.help}>{error}</p>;
  if (!status || !bridge) return <p className={css.help}>Loading…</p>;

  const notice = catalogNotice(status);
  // "Download recommended": the picks for this phone that aren't installed yet (text and voice).
  const missing = status.packs.filter((p) => p.core && p.state !== 'installed');
  const missingLeft = missing.reduce((n, p) => n + Math.max(0, p.sizeBytes - p.receivedBytes), 0);
  const idle = missing.filter((p) => p.state === 'available' || p.state === 'paused' || p.state === 'failed');
  const resident = status.resident ? status.packs.find((p) => p.id === status.resident!.id) : undefined;
  const d = status.device;
  const hasVoice = status.packs.some((p) => p.kind === 'stt' && p.state === 'installed');
  const found = status.found ?? [];
  const scan = status.scan;

  return (
    <>
      <section className={css.card}>
        <div className={css.name}>{d ? `This phone · ${d.ramLabel} RAM${d.gpu ? ' · GPU' : ''}` : `Device tier ${status.tier}`}</div>
        <div className={css.muted}>Recommendations fit this phone’s memory and speed. Everything runs on the phone, offline.</div>
        <div className={css.muted} style={{ marginTop: 8 }}>
          {fmtBytes(status.freeBytes)} free
          {status.resident ? ` · in memory: ${resident?.name ?? status.resident.id} on ${BACKEND_LABEL[status.resident.backend]}` : ''}
        </div>
        {status.source === 'download' && idle.length > 0 && status.catalog === 'ready' ? (
          <button
            type="button"
            className={css.primary}
            style={{ marginTop: 14, width: '100%' }}
            onClick={() => bridge.send('models.download', { ids: idle.map((p) => p.id) })}
          >
            Download recommended · {fmtBytes(missingLeft)}
          </button>
        ) : null}
        {status.source === 'import' ? (
          <button type="button" className={css.primary} style={{ marginTop: 14, width: '100%' }} onClick={() => bridge.send('models.import', {})}>
            Import model file
          </button>
        ) : null}
      </section>

      {notice ? (
        <p className={css.help} role="status" style={{ margin: '12px 0 0' }}>
          {notice}
        </p>
      ) : null}

      {hasVoice && status.micGranted === false ? (
        <section className={css.card} style={{ marginTop: 14 }} data-testid="mic-prompt">
          <div className={css.setRow}>
            <span>
              Allow the microphone
              <div className={css.muted}>Voice typing listens only while the mic key is on. Audio never leaves the phone and isn’t saved.</div>
            </span>
            <button type="button" className={css.ghost} style={{ height: 38, flex: 'none' }} onClick={() => bridge.send('system.open', { target: 'microphone' })}>
              Allow
            </button>
          </div>
        </section>
      ) : null}

      {status.packs.length > 0 ? (
        <div className={css.list} style={{ marginTop: 14 }}>
          {status.packs.map((p) => (
            <PackRow key={p.id} pack={p} action={packAction(p, status.source, bridge)} use={switchAction(p, bridge)} />
          ))}
        </div>
      ) : null}

      {scan ? (
        <>
          <h2 className={css.h2}>Models already on this phone</h2>
          <section className={css.card} data-testid="found">
            <div className={css.muted} aria-live="polite">
              {scan.state === 'scanning' ? 'Looking for model files…' : found.length === 0 ? 'No model files found.' : `Found ${found.length}.`} {scanScope(scan)}
            </div>
            {found.length > 0 ? (
              <div className={css.list} style={{ marginTop: 12 }}>
                {found.map((f) => (
                  <FoundRow key={f.id} found={f} entry={status.packs.find((p) => p.id === f.entryId)} action={foundAction(f, bridge)} />
                ))}
              </div>
            ) : null}
            <div className={css.row} style={{ marginTop: 12 }}>
              <button type="button" className={css.ghost} style={{ height: 38 }} disabled={scan.state === 'scanning'} onClick={() => bridge.send('models.scan', {})}>
                Scan again
              </button>
              <button type="button" className={css.ghost} style={{ height: 38 }} disabled={scan.state === 'scanning'} onClick={() => bridge.send('models.scan', { pick: 'folder' })}>
                Choose folder
              </button>
              {scan.allFilesOption && scan.access !== 'all' ? (
                <button type="button" className={css.ghost} style={{ height: 38 }} onClick={() => bridge.send('system.open', { target: 'all_files' })}>
                  Allow all files
                </button>
              ) : null}
            </div>
          </section>
        </>
      ) : null}

      {status.source === 'download' ? (
        <section className={css.card} style={{ marginTop: 14 }}>
          <div className={css.setRow}>
            <span>
              Download on Wi-Fi only
              <div className={css.muted}>Waits for an unmetered network</div>
            </span>
            <button
              type="button"
              role="switch"
              aria-checked={status.wifiOnly}
              aria-label="Download on Wi-Fi only"
              className={css.switch}
              onClick={() => bridge.send('settings.set', { key: 'models.wifiOnly', value: !status.wifiOnly })}
            />
          </div>
        </section>
      ) : null}

      <p className={css.help} style={{ marginTop: 14 }}>
        Models download from Hugging Face after install, so the app itself stays small. Each file is checked against a signed catalog
        (SHA-256) before it is used and stays in this app’s private storage; files found on the phone are copied, never changed. One AI model
        is in memory at a time; it unloads when idle.
        {bridge.kind === 'web-mock' ? ' In this browser preview, the phone, downloads and AI responses are simulated.' : ''}
      </p>
    </>
  );
}

function FoundRow({ found: f, entry, action }: { found: FoundModel; entry: ModelPack | undefined; action: NonNullable<Action> }) {
  return (
    <div className={css.item} style={{ alignItems: 'flex-start', padding: 0, border: 0 }} data-found={f.id}>
      <span className={css.grow}>
        <div className={css.name} style={{ overflowWrap: 'anywhere' }}>
          {f.name}
        </div>
        <div className={css.muted}>
          {f.where} · {fmtBytes(f.sizeBytes)} · {KIND_LABEL[f.kind]}
        </div>
        <div className={css.muted}>{f.verified ? `${entry?.name ?? 'A catalog model'}: every byte is checked before use` : 'Unknown model: can’t be checked'}</div>
      </span>
      <button type="button" className={css[action.kind]} style={{ height: 38, flex: 'none' }} onClick={action.run}>
        {action.label}
      </button>
    </div>
  );
}

function PackRow({ pack: p, action, use }: { pack: ModelPack; action: Action; use: Action }) {
  const busy = p.state === 'downloading' || p.state === 'queued' || (p.state === 'paused' && p.receivedBytes > 0);
  const pct = p.sizeBytes > 0 ? Math.min(100, (p.receivedBytes / p.sizeBytes) * 100) : 0;
  return (
    <div className={css.item} style={{ alignItems: 'flex-start' }} data-pack={p.id} data-state={p.state}>
      <span className={css.grow}>
        <div className={css.name}>
          {p.name}{' '}
          {p.fit ? <span className={p.fit === 'recommended' ? css.pillOn : css.pill}>{FIT_LABEL[p.fit]}</span> : null}{' '}
          {p.active ? <span className={css.pill}>In use</span> : null}
        </div>
        <div className={css.muted}>
          {KIND_LABEL[p.kind]} · {p.role}
        </div>
        {p.fitDetail ? <div className={css.muted}>{p.fitDetail}</div> : null}
        <div className={css.muted} aria-live="polite">
          {stateLine(p)}
        </div>
        {busy ? (
          <div className={css.meter} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.floor(pct)} aria-label={`${p.name} download`}>
            <i style={{ width: `${pct}%` }} />
          </div>
        ) : null}
        <div className={css.muted} style={{ marginTop: 4 }}>
          Licence:{' '}
          {p.licenseUrl ? (
            <a href={p.licenseUrl} target="_blank" rel="noreferrer" style={{ color: 'inherit', textDecoration: 'underline' }}>
              {p.license}
            </a>
          ) : (
            p.license
          )}
        </div>
      </span>
      <span style={{ display: 'flex', flexDirection: 'column', gap: 8, flex: 'none' }}>
        {[use, action].map((a) =>
          a ? (
            <button key={a.label} type="button" className={css[a.kind]} style={{ height: 38 }} onClick={a.run}>
              {a.label}
            </button>
          ) : null,
        )}
      </span>
    </div>
  );
}
