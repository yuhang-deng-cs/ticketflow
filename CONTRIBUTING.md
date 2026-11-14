# Contributing

Thanks for helping. Issues, strategy ideas, benchmark numbers from your hardware and PRs are all welcome.

## Setup

| You need | For |
|---|---|
| Java 21 | Backend (`./mvnw` downloads Maven for you) |
| Node 22 | Frontend |
| Docker | Full stack and the PostgreSQL/Redis tests (optional; those tests skip without it) |

```bash
cd backend && ./mvnw spring-boot:run       # dev profile: H2 in memory, no Redis needed
cd frontend && npm install && npm run dev   # http://localhost:5173
```

## Before you open a PR

```bash
cd backend && ./mvnw verify                 # all tests; PostgreSQL/Redis ones need Docker
cd frontend && npm run lint && npm run build
```

CI runs the same commands plus Docker image builds and `terraform validate`.

## Adding an inventory strategy

1. Implement [`InventoryStrategy`](backend/src/main/java/com/ticketflow/inventory/InventoryStrategy.java) in `inventory/strategy/` as a Spring `@Component`. Own your transaction boundary (inject `TransactionTemplate`), and make `release` undo exactly what `tryDeduct` did.
2. Add a value to [`InventoryStrategyType`](backend/src/main/java/com/ticketflow/inventory/InventoryStrategyType.java). Set `safe = false` only for strategies that are broken on purpose.
3. Add it to the `@EnumSource` lists in `InventoryStrategyConcurrencyTest` if it runs on H2.
4. Put Lab results (before/after, your hardware) in the PR description, and a section in [`docs/strategies.md`](docs/strategies.md).

## Conventions

- Java: constructor injection, no field injection; `record`s for new DTOs; comments explain *why*, not *what*.
- State changes that can race are compare-and-set `UPDATE`s, not load–check–save.
- Never return exception messages from unexpected errors to clients.
- Commit messages: [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:` …).
- Applied Flyway migrations are never edited; add a new `V{n}__*.sql`.

By contributing you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md) and that your contribution is MIT-licensed.
