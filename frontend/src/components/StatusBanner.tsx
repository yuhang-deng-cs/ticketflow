import React, { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { LabCatalog } from '../types';

type Status = { state: 'loading' } | { state: 'online'; catalog: LabCatalog } | { state: 'offline' };

/** Live status read from the backend, not hard-coded claims. */
export const StatusBanner: React.FC = () => {
  const [status, setStatus] = useState<Status>({ state: 'loading' });

  useEffect(() => {
    api.lab
      .catalog()
      .then((catalog) => setStatus({ state: 'online', catalog }))
      .catch(() => setStatus({ state: 'offline' }));
  }, []);

  const redis = status.state === 'online' ? status.catalog.strategies.find((s) => s.type === 'REDIS_LUA') : undefined;

  return (
    <div className="border-b border-slate-800/80 bg-slate-900/60 py-2 px-4 text-xs">
      <div className="max-w-7xl mx-auto flex flex-wrap items-center gap-x-5 gap-y-1 text-slate-400">
        <span className="flex items-center gap-2">
          <span
            className={`h-2 w-2 rounded-full ${
              status.state === 'online' ? 'bg-emerald-400' : status.state === 'offline' ? 'bg-rose-500' : 'bg-slate-500'
            }`}
          />
          {status.state === 'online' ? 'API online' : status.state === 'offline' ? 'API unreachable' : 'Connecting…'}
        </span>
        {status.state === 'online' && (
          <>
            <span>
              Checkout strategy: <code className="text-indigo-300">{status.catalog.activeStrategy}</code>
            </span>
            <span>Redis: {redis?.available ? 'connected' : 'not running (Redis strategy disabled)'}</span>
          </>
        )}
      </div>
    </div>
  );
};
