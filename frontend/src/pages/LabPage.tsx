import React, { useEffect, useRef, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardCopy,
  FlaskConical,
  Loader2,
  Play,
  ShieldAlert,
  XCircle,
} from 'lucide-react';
import { api, errorMessage } from '../api/client';
import type { LabCatalog, LabRunResult, StrategyInfo, StrategyType } from '../types';

const PRESETS = [
  { label: 'Flash sale', stock: 100, buyers: 1000, hint: '10 buyers per ticket' },
  { label: 'Fair fight', stock: 500, buyers: 500, hint: 'one ticket per buyer' },
  { label: 'Last seat', stock: 1, buyers: 200, hint: '200 buyers, 1 ticket' },
];

/** Scenario from the URL, e.g. ?stock=100&buyers=1000&run=1, so results can be shared as a link. */
function scenarioFromUrl() {
  const params = new URLSearchParams(window.location.search);
  const positive = (key: string, fallback: number) => {
    const n = Number(params.get(key));
    return Number.isInteger(n) && n > 0 ? n : fallback;
  };
  return { stock: positive('stock', 100), buyers: positive('buyers', 1000), autorun: params.get('run') === '1' };
}

type Verdict = { tone: 'bad' | 'warn' | 'good'; text: string };

function verdict(r: LabRunResult): Verdict {
  if (r.oversold > 0 || r.phantomStock > 0) {
    const parts = [];
    if (r.oversold > 0) parts.push(`sold ${r.oversold} tickets that don't exist`);
    if (r.phantomStock > 0) parts.push(`${r.phantomStock} sold seats still show as free`);
    return { tone: 'bad', text: `Oversold: ${parts.join(', ')}` };
  }
  if (r.errors > 0) return { tone: 'warn', text: `${r.errors} requests failed` };
  if (r.unsold > 0) return { tone: 'warn', text: `Correct, but ${r.unsold} tickets left unsold while buyers were turned away` };
  return { tone: 'good', text: 'Exactly right: every ticket sold once' };
}

function toMarkdown(results: LabRunResult[]): string {
  const header =
    '| Strategy | Stock | Buyers | Sold | Oversold | Phantom stock | Unsold | Throughput (req/s) | p99 ms |\n' +
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|\n';
  return (
    header +
    results
      .map(
        (r) =>
          `| ${r.strategy} | ${r.stock} | ${r.buyers} | ${r.succeeded} | ${r.oversold} | ${r.phantomStock} | ${r.unsold} | ${Math.round(r.throughputPerSecond)} | ${r.p99Ms} |`,
      )
      .join('\n')
  );
}

const StockBar: React.FC<{ result: LabRunResult }> = ({ result }) => {
  const scale = Math.max(result.stock, result.succeeded + result.phantomStock, 1);
  const pct = (n: number) => `${(n / scale) * 100}%`;
  const legit = Math.min(result.succeeded, result.stock);

  return (
    <div className="relative" aria-hidden>
      <div className="h-3 w-full rounded-full bg-slate-800 overflow-hidden flex">
        <div className="h-full bg-emerald-500" style={{ width: pct(legit) }} />
        <div className="h-full bg-rose-500" style={{ width: pct(result.oversold) }} />
        <div className="h-full bg-amber-400" style={{ width: pct(result.phantomStock) }} />
        <div className="h-full bg-slate-600" style={{ width: pct(result.unsold) }} />
      </div>
      {result.stock < scale && (
        // Marks where the real tickets end; everything to the right was sold out of thin air.
        <div
          className="absolute -top-1 -bottom-1 border-l-2 border-dashed border-white/80"
          style={{ left: pct(result.stock) }}
          title={`${result.stock} real tickets`}
        />
      )}
    </div>
  );
};

const StrategyCard: React.FC<{
  info: StrategyInfo;
  result?: LabRunResult;
  running: boolean;
  disabled: boolean;
  onRun: () => void;
}> = ({ info, result, running, disabled, onRun }) => {
  const v = result ? verdict(result) : null;
  const toneClasses = {
    bad: 'border-rose-500/40 bg-rose-500/5',
    warn: 'border-amber-500/40 bg-amber-500/5',
    good: 'border-emerald-500/40 bg-emerald-500/5',
  };

  return (
    <div
      className={`rounded-2xl border p-5 flex flex-col gap-4 transition ${
        v ? toneClasses[v.tone] : 'border-slate-800 bg-slate-900'
      }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-bold text-white">{info.displayName}</h3>
            {!info.safe && (
              <span className="text-[10px] uppercase tracking-wide px-2 py-0.5 rounded-full bg-rose-500/15 text-rose-300 border border-rose-500/30">
                Broken on purpose
              </span>
            )}
            {info.active && (
              <span className="text-[10px] uppercase tracking-wide px-2 py-0.5 rounded-full bg-indigo-500/15 text-indigo-300 border border-indigo-500/30">
                Used by checkout
              </span>
            )}
          </div>
          <code className="text-[11px] text-slate-500">{info.type}</code>
        </div>
        <button
          onClick={onRun}
          disabled={disabled || !info.available}
          className="shrink-0 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 disabled:opacity-40 disabled:cursor-not-allowed transition"
        >
          {running ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />}
          Run
        </button>
      </div>

      <p className="text-xs text-slate-400 leading-relaxed">{info.description}</p>

      {!info.available && (
        <p className="text-xs text-amber-300/90 flex items-center gap-1.5">
          <AlertTriangle className="h-3.5 w-3.5" /> Needs Redis. Run the full stack with <code>docker compose up</code>.
        </p>
      )}

      {result && v && (
        <div className="space-y-3">
          <div className="flex items-baseline justify-between">
            <div>
              <span className="text-3xl font-extrabold text-white tabular-nums">{result.succeeded}</span>
              <span className="text-sm text-slate-400"> sold / {result.stock} real tickets</span>
            </div>
            <span className="text-xs text-slate-500 tabular-nums">{result.durationMs} ms</span>
          </div>
          <StockBar result={result} />
          <div
            className={`flex items-start gap-1.5 text-xs font-medium ${
              v.tone === 'bad' ? 'text-rose-300' : v.tone === 'warn' ? 'text-amber-300' : 'text-emerald-300'
            }`}
          >
            {v.tone === 'bad' ? (
              <XCircle className="h-4 w-4 shrink-0" />
            ) : v.tone === 'warn' ? (
              <ShieldAlert className="h-4 w-4 shrink-0" />
            ) : (
              <CheckCircle2 className="h-4 w-4 shrink-0" />
            )}
            <span>{v.text}</span>
          </div>
          <dl className="grid grid-cols-3 gap-2 text-[11px]">
            <Stat label="Throughput" value={`${Math.round(result.throughputPerSecond).toLocaleString()}/s`} />
            <Stat label="p50" value={`${result.p50Ms} ms`} />
            <Stat label="p99" value={`${result.p99Ms} ms`} />
            <Stat label="Sold out" value={result.soldOut} />
            <Stat label="Contention" value={result.conflicts} />
            <Stat label="DB stock left" value={result.finalStock} />
          </dl>
        </div>
      )}
    </div>
  );
};

const Stat: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div className="rounded-lg bg-slate-950/60 border border-slate-800 px-2 py-1.5">
    <dt className="text-slate-500">{label}</dt>
    <dd className="text-slate-200 font-semibold tabular-nums">{value}</dd>
  </div>
);

export const LabPage: React.FC = () => {
  const [catalog, setCatalog] = useState<LabCatalog | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [initial] = useState(scenarioFromUrl);
  const [stock, setStock] = useState(initial.stock);
  const [buyers, setBuyers] = useState(initial.buyers);
  const autoran = useRef(false);
  const [results, setResults] = useState<Partial<Record<StrategyType, LabRunResult>>>({});
  const [running, setRunning] = useState<StrategyType | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    api.lab
      .catalog()
      .then(setCatalog)
      .catch((err) => setLoadError(errorMessage(err, 'Cannot reach the backend. Is it running on :8080?')));
  }, []);

  const runOne = async (type: StrategyType) => {
    setRunning(type);
    setRunError(null);
    try {
      const result = await api.lab.run(type, stock, buyers);
      setResults((prev) => ({ ...prev, [type]: result }));
    } catch (err) {
      setRunError(errorMessage(err, `Run failed for ${type}`));
    } finally {
      setRunning(null);
    }
  };

  const runAll = async () => {
    if (!catalog) return;
    setResults({});
    for (const s of catalog.strategies.filter((x) => x.available)) {
      await runOne(s.type);
    }
  };

  useEffect(() => {
    if (catalog && initial.autorun && !autoran.current) {
      autoran.current = true;
      void runAll();
    }
    // runAll is recreated every render; this should fire once, when the catalog arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [catalog]);

  useEffect(() => {
    const url = new URL(window.location.href);
    url.searchParams.set('stock', String(stock));
    url.searchParams.set('buyers', String(buyers));
    url.searchParams.delete('run');
    window.history.replaceState(null, '', url);
  }, [stock, buyers]);

  const finished = catalog
    ? catalog.strategies.map((s) => results[s.type]).filter((r): r is LabRunResult => Boolean(r))
    : [];

  const copyMarkdown = async () => {
    await navigator.clipboard.writeText(toMarkdown(finished));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const limits = catalog?.limits ?? { maxStock: 1000, maxBuyers: 1000 };
  const clamp = (n: number, max: number) => Math.max(1, Math.min(max, Number.isFinite(n) ? Math.floor(n) : 1));

  return (
    <div className="space-y-8">
      <section className="space-y-4">
        <div className="inline-flex items-center gap-2 text-xs font-medium text-indigo-300 bg-indigo-500/10 border border-indigo-500/20 rounded-full px-3 py-1">
          <FlaskConical className="h-3.5 w-3.5" /> Oversell Lab
        </div>
        <h1 className="text-3xl sm:text-5xl font-extrabold tracking-tight text-white">
          {buyers.toLocaleString()} buyers. {stock.toLocaleString()} tickets.
          <br />
          <span className="text-indigo-300">Which strategy oversells?</span>
        </h1>
        <p className="max-w-2xl text-sm sm:text-base text-slate-400">
          Each run creates a fresh ticket tier on this server, releases every buyer at the same instant, then compares
          what buyers were told with what is left in the database. No mocks: real threads, real transactions, real row
          locks.
        </p>
      </section>

      <section className="rounded-2xl border border-slate-800 bg-slate-900 p-5 flex flex-col lg:flex-row lg:items-end gap-5">
        <div className="flex flex-wrap gap-2">
          {PRESETS.map((p) => (
            <button
              key={p.label}
              onClick={() => {
                setStock(p.stock);
                setBuyers(p.buyers);
              }}
              className={`text-left px-3 py-2 rounded-xl border text-xs transition ${
                stock === p.stock && buyers === p.buyers
                  ? 'border-indigo-500/60 bg-indigo-500/10 text-white'
                  : 'border-slate-800 bg-slate-950 text-slate-300 hover:border-slate-700'
              }`}
            >
              <div className="font-semibold">{p.label}</div>
              <div className="text-slate-500">{p.hint}</div>
            </button>
          ))}
        </div>
        <div className="flex gap-3">
          <label className="text-xs text-slate-400 space-y-1">
            <span>Tickets</span>
            <input
              type="number"
              min={1}
              max={limits.maxStock}
              value={stock}
              onChange={(e) => setStock(clamp(e.target.valueAsNumber, limits.maxStock))}
              className="block w-28 rounded-lg bg-slate-950 border border-slate-800 px-3 py-2 text-sm text-white tabular-nums"
            />
          </label>
          <label className="text-xs text-slate-400 space-y-1">
            <span>Buyers</span>
            <input
              type="number"
              min={1}
              max={limits.maxBuyers}
              value={buyers}
              onChange={(e) => setBuyers(clamp(e.target.valueAsNumber, limits.maxBuyers))}
              className="block w-28 rounded-lg bg-slate-950 border border-slate-800 px-3 py-2 text-sm text-white tabular-nums"
            />
          </label>
        </div>
        <button
          onClick={runAll}
          disabled={!catalog || running !== null}
          className="lg:ml-auto inline-flex items-center justify-center gap-2 px-6 py-3 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white font-semibold shadow-lg shadow-indigo-600/30 transition"
        >
          {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
          {running ? `Running ${running}…` : 'Run all strategies'}
        </button>
      </section>

      {(loadError || runError) && (
        <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200">
          {loadError ?? runError}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-4 text-[11px] text-slate-400">
        <Legend color="bg-emerald-500" label="Sold (real tickets)" />
        <Legend color="bg-rose-500" label="Oversold" />
        <Legend color="bg-amber-400" label="Phantom stock (will be sold twice)" />
        <Legend color="bg-slate-600" label="Unsold despite demand" />
      </div>

      <section className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {catalog?.strategies.map((info) => (
          <StrategyCard
            key={info.type}
            info={info}
            result={results[info.type]}
            running={running === info.type}
            disabled={running !== null}
            onRun={() => runOne(info.type)}
          />
        ))}
      </section>

      {finished.length > 0 && (
        <section className="rounded-2xl border border-slate-800 bg-slate-900 p-5 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="font-bold text-white">Results</h2>
            <button
              onClick={copyMarkdown}
              className="inline-flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 transition"
            >
              <ClipboardCopy className="h-3.5 w-3.5" />
              {copied ? 'Copied!' : 'Copy as Markdown'}
            </button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs tabular-nums">
              <thead className="text-slate-500">
                <tr className="text-left">
                  <th className="py-2 pr-4">Strategy</th>
                  <th className="py-2 pr-4 text-right">Sold</th>
                  <th className="py-2 pr-4 text-right">Oversold</th>
                  <th className="py-2 pr-4 text-right">Phantom</th>
                  <th className="py-2 pr-4 text-right">Unsold</th>
                  <th className="py-2 pr-4 text-right">req/s</th>
                  <th className="py-2 pr-4 text-right">p50 ms</th>
                  <th className="py-2 text-right">p99 ms</th>
                </tr>
              </thead>
              <tbody className="text-slate-200">
                {finished.map((r) => (
                  <tr key={r.strategy} className="border-t border-slate-800">
                    <td className="py-2 pr-4 font-mono">{r.strategy}</td>
                    <td className="py-2 pr-4 text-right">{r.succeeded}</td>
                    <td className={`py-2 pr-4 text-right ${r.oversold ? 'text-rose-400 font-bold' : ''}`}>{r.oversold}</td>
                    <td className={`py-2 pr-4 text-right ${r.phantomStock ? 'text-amber-300 font-bold' : ''}`}>
                      {r.phantomStock}
                    </td>
                    <td className={`py-2 pr-4 text-right ${r.unsold ? 'text-amber-300' : ''}`}>{r.unsold}</td>
                    <td className="py-2 pr-4 text-right">{Math.round(r.throughputPerSecond).toLocaleString()}</td>
                    <td className="py-2 pr-4 text-right">{r.p50Ms}</td>
                    <td className="py-2 text-right">{r.p99Ms}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="text-[11px] text-slate-500">
            Timings depend on this server's hardware, database and connection pool. Compare strategies within one run,
            not across machines.
          </p>
        </section>
      )}
    </div>
  );
};

const Legend: React.FC<{ color: string; label: string }> = ({ color, label }) => (
  <span className="inline-flex items-center gap-1.5">
    <span className={`h-2.5 w-2.5 rounded-sm ${color}`} />
    {label}
  </span>
);
