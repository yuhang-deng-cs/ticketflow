import React from 'react';
import { X, Layers } from 'lucide-react';

interface ArchitectureModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const STEPS = [
  {
    title: '1. Reserve stock',
    body: 'The configured inventory strategy takes the tickets in its own short transaction. The default is one conditional UPDATE: the check and the decrement happen in a single statement, so there is no gap between them for another buyer to slip into.',
    code: 'UPDATE ticket_tiers SET available_capacity = available_capacity - :qty\nWHERE id = :tierId AND available_capacity >= :qty;',
  },
  {
    title: '2. Create a PENDING order',
    body: 'If saving the order fails, the reservation is released again (compensation), so a failed request never leaks inventory. The order has a 10-minute payment deadline.',
  },
  {
    title: '3. Pay, cancel or expire, and only one of them wins',
    body: 'Every state change is a compare-and-set. A payment arriving at the same moment the expiry sweeper runs cannot both confirm the order and give its seat back.',
    code: "UPDATE orders SET status = 'CONFIRMED'\nWHERE id = :id AND status = 'PENDING' AND expires_at > now();",
  },
  {
    title: 'Retries are safe',
    body: 'POST requests carry an Idempotency-Key. Retrying with the same key replays the first response instead of booking twice. Reusing a key for a different request returns 422.',
  },
];

export const ArchitectureModal: React.FC<ArchitectureModalProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-labelledby="how-it-works-title"
    >
      <div
        className="relative w-full max-w-3xl bg-slate-900 border border-slate-800 rounded-2xl p-6 sm:p-8 shadow-2xl max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <button onClick={onClose} className="absolute top-4 right-4 text-slate-400 hover:text-white transition p-1" aria-label="Close">
          <X className="h-6 w-6" />
        </button>

        <div className="flex items-center gap-3 mb-6">
          <div className="h-10 w-10 rounded-xl bg-indigo-600/20 border border-indigo-500/30 flex items-center justify-center text-indigo-400">
            <Layers className="h-5 w-5" />
          </div>
          <div>
            <h2 id="how-it-works-title" className="text-xl font-bold text-white">
              How a booking stays correct under load
            </h2>
            <p className="text-xs text-slate-400">Spring Boot 3 · Java 21 · PostgreSQL 16 · Redis 7 · React 19</p>
          </div>
        </div>

        <ol className="space-y-5">
          {STEPS.map((step) => (
            <li key={step.title} className="space-y-2">
              <h3 className="text-sm font-semibold text-white">{step.title}</h3>
              <p className="text-xs text-slate-300 leading-relaxed">{step.body}</p>
              {step.code && (
                <pre className="bg-slate-950 p-3 rounded-lg border border-slate-800 font-mono text-[11px] text-emerald-300 overflow-x-auto">
                  {step.code}
                </pre>
              )}
            </li>
          ))}
        </ol>

        <div className="mt-6 border-t border-slate-800 pt-4 flex flex-wrap gap-4 text-xs">
          <a href="/swagger-ui.html" target="_blank" rel="noreferrer" className="text-indigo-400 hover:text-indigo-300">
            OpenAPI / Swagger UI
          </a>
          <a
            href="https://github.com/cs50-citizen/ticketflow/blob/main/docs/strategies.md"
            target="_blank"
            rel="noreferrer"
            className="text-indigo-400 hover:text-indigo-300"
          >
            Strategy deep dive
          </a>
          <a
            href="https://github.com/cs50-citizen/ticketflow/blob/main/docs/pitfalls.md"
            target="_blank"
            rel="noreferrer"
            className="text-indigo-400 hover:text-indigo-300"
          >
            Bugs we found (and fixed)
          </a>
        </div>
      </div>
    </div>
  );
};
