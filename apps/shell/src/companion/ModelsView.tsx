'use client';
/**
 * Models (SPEC §12, ADR-0011). Live state from `:agent`'s ModelService: `models.status` on open (which
 * also lets queued downloads start; transfers only ever begin from this screen), then
 * `models.changed` pushes. In the browser the web mock simulates the same lifecycle.
 */
import { useEffect, useState } from 'react';
import type { DeviceTier, ModelPack, ModelsStatus, NativeBridge } from '@ak/protocol';
import { CompanionShell } from './CompanionShell';
import css from './companion.module.css';

const TIER_LABEL: Record<DeviceTier, string> = { S: '4–5 GB RAM', M: '6–8 GB RAM', L: '9 GB+ RAM' };
const BACKEND_LABEL = { npu: 'NPU', gpu: 'GPU', cpu: 'CPU' } as const;

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
      return source === 'download' ? { label: 'Download', kind: 'ghost', run: () => b.send('models.download', { ids: [p.id] }) } : null;
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
              if (confirm(`Delete ${p.name}? Features that need it stop working until you download it again.`)) b.send('models.delete', { id: p.id });
            },
          };
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
  const missingCore = status.packs.filter((p) => p.core && p.state !== 'installed');
  const coreLeft = missingCore.reduce((n, p) => n + Math.max(0, p.sizeBytes - p.receivedBytes), 0);
  const idle = missingCore.filter((p) => p.state === 'available' || p.state === 'paused' || p.state === 'failed');
  const resident = status.resident ? status.packs.find((p) => p.id === status.resident!.id) : undefined;

  return (
    <>
      <section className={css.card}>
        <div className={css.name}>Device tier {status.tier}</div>
        <div className={css.muted}>{TIER_LABEL[status.tier]} · everything runs on this phone, offline</div>
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
            Download core pack · {fmtBytes(coreLeft)}
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

      {status.packs.length > 0 ? (
        <div className={css.list} style={{ marginTop: 14 }}>
          {status.packs.map((p) => (
            <PackRow key={p.id} pack={p} action={packAction(p, status.source, bridge)} />
          ))}
        </div>
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
        Each file is checked against a signed catalog (SHA-256) before it is used, and stays in this app’s private storage. One AI
        model is in memory at a time; it unloads when idle.
        {bridge.kind === 'web-mock' ? ' In this browser preview, downloads and AI responses are simulated.' : ''}
      </p>
    </>
  );
}

function PackRow({ pack: p, action }: { pack: ModelPack; action: Action }) {
  const busy = p.state === 'downloading' || p.state === 'queued' || (p.state === 'paused' && p.receivedBytes > 0);
  const pct = p.sizeBytes > 0 ? Math.min(100, (p.receivedBytes / p.sizeBytes) * 100) : 0;
  return (
    <div className={css.item} style={{ alignItems: 'flex-start' }} data-pack={p.id} data-state={p.state}>
      <span className={css.grow}>
        <div className={css.name}>
          {p.name} <span className={css.pill}>{p.core ? 'Core' : 'Optional'}</span>
        </div>
        <div className={css.muted}>{p.role}</div>
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
      {action ? (
        <button type="button" className={css[action.kind]} style={{ height: 38, flex: 'none' }} onClick={action.run}>
          {action.label}
        </button>
      ) : null}
    </div>
  );
}
