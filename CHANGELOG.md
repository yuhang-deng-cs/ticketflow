# Changelog

All notable changes to this project. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [2.0.0] - 2026-11-14

### Added
- **Oversell Lab**: `GET /api/v1/lab/strategies`, `POST /api/v1/lab/runs` and a UI that races N buyers against one strategy and reports sold, oversold, phantom stock, unsold, throughput and latency. Scenarios are shareable via URL (`?stock=&buyers=&run=1`).
- Five pluggable inventory strategies behind `InventoryStrategy`: `NAIVE` (broken on purpose), `PESSIMISTIC_LOCK`, `OPTIMISTIC_LOCK`, `ATOMIC_UPDATE`, `REDIS_LUA`. Checkout strategy is set with `INVENTORY_STRATEGY`.
- `POST /api/v1/orders/{orderNumber}/cancel`.
- PostgreSQL 16 + Redis 7 strategy tests via Testcontainers; results published to the CI job summary.
- Metric `ticketflow_booking_reservations_total{strategy,result}` and a provisioned Grafana dashboard.
- Terraform: ElastiCache Redis, secrets in SSM, CloudFront `/api/*` routing to the ALB, S3 bucket policy.
- Docs: strategy deep dive, pitfalls write-up, Chinese README, contributing guide, issue/PR templates, Dependabot.

### Fixed
- Payment racing the expiry sweeper could confirm an order whose seat had already been released. All order transitions are now compare-and-set.
- Idempotency now replays the first response instead of returning 409, is scoped per user, rejects key reuse with a different payload (422) and frees the key after 5xx.
- `Idempotency-Key` missing from CORS allowed headers broke booking in local dev.
- Expired-order handling in `payOrder` was rolled back by its own exception.
- Email uniqueness was checked before normalisation.
- `mvnw` lacked the executable bit, breaking the Docker build on Linux.

### Changed
- Demo data uses fictional events; demo accounts are `demo@ticketflow.test` / `admin@ticketflow.test`. Existing databases are updated by migration `V3`.
- Prod requires `JWT_SECRET`; actuator endpoints other than health/info/prometheus need ADMIN; the H2 console exists only in dev; 500 responses no longer include exception messages.
- The frontend calls the API same-origin (`/api/v1`) through the Vite proxy in dev and nginx in Docker.

## [1.0.0] - 2026-11-04

Initial release.
