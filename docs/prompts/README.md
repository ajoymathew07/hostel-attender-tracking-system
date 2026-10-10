# Prompts

The prompts used to build this project with Claude Code, in the order they were given. Each file
covers one phase of the project and lists its prompts by date.

Each prompt is edited to show the prompting principles from the Principles & Practices document.
The wording stays the author's own. The **Techniques** line under each prompt names the principles
it uses.

| File | Phase | Dates |
|---|---|---|
| [01-design.md](01-design.md) | Proposal → design doc | 8–9 Sep 2026 |
| [02-pr-plan-and-workflow.md](02-pr-plan-and-workflow.md) | PR tooling, PR plan, implementation rules | 18 Sep 2026 |
| [03-implementation.md](03-implementation.md) | Building the PRs to the mid-demo | 21–27 Sep 2026 |
| [04-manual-testing-and-debugging.md](04-manual-testing-and-debugging.md) | First run of the app, bug fixes | 30 Sep 2026 |
| [05-scope-change-and-deployment.md](05-scope-change-and-deployment.md) | Dropping the classifier, Render deploy, Apps Script wake | 1–3 Oct 2026 |
| [06-demo-readiness.md](06-demo-readiness.md) | Demo walkthrough, UI fixes, CI failure | 4–5 Oct 2026 |
| [07-refactoring.md](07-refactoring.md) | Simplifying structure for the mid-eval | 6–7 Oct 2026 |

## Techniques used

| Technique | What it means here |
|---|---|
| Context | Pointing at the files that matter with `@` (design doc, proposal, PR plan) |
| Constraints | Scope limits, "design doc is the source of truth", keep it minimal |
| Negative prompting | What not to touch or use |
| Few-shot | Example inputs and expected outputs (room numbers, PR descriptions) |
| Persona | Senior engineer for design/implementation, senior Java reviewer for evaluation |
| Prompt chaining | Plan first, confirm, then implement in steps |
| Acceptance criteria | Tests that must pass, evaluator sign-off before a PR opens |
| Output format | How the answer or PR description should look |
| Meta-prompting | Asking the model to write the detailed prompt for a sub-agent |
