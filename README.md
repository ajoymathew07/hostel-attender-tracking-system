# Hostel Complaint & Attender Duty Tracking System

Automates the daily maintenance-complaint workflow at Cauvery Hostel, IIT Madras — replacing a
manual Google Forms → spreadsheet → verbal-checkin process that costs the Hostel Manager
30–45 minutes every day.

**Course:** CS5013 — Programming with AI · **Author:** Ajoy Mathew (CS23B101) · **Stakeholder:** Hostel Manager, Cauvery Hostel, IIT Madras

## What it does

| Capability | Description |
|---|---|
| Automated ingestion | Polls the Google Form response sheet for new complaints; CSV drag-drop fallback |
| Categorization | Rule + fuzzy-match classifier into Electrical / Civil / RO Plant / Network; low-confidence items land in a triage queue |
| Heuristic dispatch | Scores attenders on floor proximity, availability and pending load; assigns the best fit |
| Field verification | Attender logs the issue on the IITM EU CMS app and records the docket number for institutional traceability |
| Points & leaderboard | +2 resolution credits, custom special-duty points, −1 penalties, on an append-only ledger |
| Manager dashboard | Live throughput, backlogs, MTTR, anomaly alerts, one-click `.xlsx` export |

## Tech stack

Java 21 · Spring Boot 3.x (MVC, Data JPA, Security, Validation) · Maven · PostgreSQL
· Thymeleaf + Tailwind + Chart.js · Apache POI · Google Sheets API v4 · SSE

## Documentation

| Document | Status |
|---|---|
| [Project Proposal](docs/proposal/CS5013_Project_Proposal.pdf) | Approved |
| [Phase 1 — Design Document](docs/design/) | In progress |

## Development

Requires JDK 21, Maven 3.9+, Docker (for the local Postgres and Testcontainers).

```bash
docker compose up -d                                        # start local Postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local      # http://localhost:8080
./mvnw verify                                                # unit + integration tests
```

There is no default Spring profile: `local` or `cloud` must be named, so that a deployment cannot
start with developer settings by accident. Deploying is
[`docs/runbook/deploy.md`](docs/runbook/deploy.md); the Google console setup it depends on is
[`docs/runbook/google-setup.md`](docs/runbook/google-setup.md).

## Contributing workflow

Trunk-based. `main` is protected; every change lands through a reviewed pull request.

- Branches: `feat/<slug>`, `fix/<slug>`, `docs/<slug>`, `chore/<slug>`
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
- PRs squash-merge; CI (`mvn verify`) must be green

## AI usage

Built with AI coding assistants per the course policy. Every line submitted is code the author
understands and can explain.
