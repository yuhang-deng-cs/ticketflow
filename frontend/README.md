# TicketFlow frontend

React 19 + TypeScript + Vite + Tailwind. See the [main README](../README.md) for the full picture.

```bash
npm install
npm run dev        # http://localhost:5173, proxies /api to http://localhost:8080
npm run lint       # oxlint, warnings fail the build
npm run build      # type-check + production bundle
```

Point the dev proxy at another backend with `BACKEND_URL=http://host:port npm run dev`,
or bake a different API base into the bundle with `VITE_API_URL`.

| Path | What it is |
|---|---|
| `src/pages/LabPage.tsx` | The Oversell Lab: runs every inventory strategy and visualises the outcome |
| `src/pages/EventListPage.tsx`, `EventDetailPage.tsx` | Browse events and reserve tickets |
| `src/pages/MyOrdersPage.tsx`, `OrderCheckoutModal.tsx` | Pay or cancel held orders before the hold expires |
| `src/api/client.ts` | Axios client: JWT header, `Idempotency-Key` per user action, error messages |
