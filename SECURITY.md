# Security policy

TicketFlow is a reference and learning project, not a hosted service. Still, if you find a vulnerability in the code (authentication bypass, injection, secrets handling, an unsafe default), please report it privately through [GitHub security advisories](https://github.com/yuhang-deng-cs/ticketflow/security/advisories/new) rather than a public issue.

Before deploying anything based on this repo:

- Set `JWT_SECRET` (at least 32 bytes) and database credentials from a secret store. The prod profile refuses to start without `JWT_SECRET`.
- Set `ticketflow.demo.seed=false` so the demo accounts are not created.
- Consider `LAB_ENABLED=false`: the Oversell Lab is public by design and runs real load against your database (one run at a time, capped size).
