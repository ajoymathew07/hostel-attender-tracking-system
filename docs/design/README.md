# Phase 1 — Design Document

**Hostel Complaint & Attender Duty Tracking System**
CS5013 Programming with AI · Ajoy Mathew (CS23B101) · 11 September 2026

This directory is the source of truth for the Phase 1 design. The submission PDF
(`design-doc.pdf`) is generated from these files; do not edit the PDF by hand.

## Contents

| § | Document | Covers |
|---|---|---|
| 1 | [Overview & Scope](01-overview.md) | Problem, goals, non-goals, actors, glossary, NFRs |
| 2 | [System Architecture](02-architecture.md) | Module breakdown, layering, ports & adapters, deployment |
| 3 | [Data Model](03-data-model.md) | ERD, DDL, optimistic locking, audit tables |
| 4 | [State Machines](04-state-machines.md) | Complaint lifecycle, CMS docket sub-state, transition tables |
| 5 | [Ingestion & Categorization](05-ingestion-categorization.md) | Delta algorithm, idempotency, classifier, triage |
| 6 | [Dispatch Heuristic](06-dispatch.md) | Scoring formulation, weights, degenerate cases |
| 7 | [Points Ledger & Concurrency](07-ledger-concurrency.md) | Append-only ledger, OCC, shared tasks, leaderboard |
| 8 | [Analytics & Telemetry](08-analytics.md) | MTTR, bottlenecks, anomaly detection, small-sample caveats |
| 9 | [API & Data Contracts](09-api-contracts.md) | REST endpoints, DTOs, SSE envelope, error model |
| 10 | [Frontend Design](10-frontend.md) | Manager desktop and attender mobile layouts |
| 11 | [Security & Privacy](11-security-privacy.md) | AuthN/AuthZ, threat model, PII handling |
| 12 | [Test Plan](12-test-plan.md) | Fixtures, gold set, concurrency stress, traceability |
| 13 | [Revised Timeline](13-timeline.md) | Re-baselined milestones and cut lines |
| 14 | [Decision Record](14-decisions.md) | ADRs for the choices that shaped this design |

## Building the PDF

```bash
make -C docs/design pdf
```

Requires `pandoc` and `xelatex`. Mermaid diagrams render as images when
[mermaid-cli](https://github.com/mermaid-js/mermaid-cli) is installed
(`npm i -g @mermaid-js/mermaid-cli`); without it they appear as readable code
listings and the build still succeeds. Fonts are auto-detected, and emoji are
substituted width-preservingly so the ASCII wireframes stay aligned in print.

## Open questions pending stakeholder input

Tracked in [§1.7](01-overview.md#17-open-questions). These are designed around with
documented defaults so no work is blocked.
