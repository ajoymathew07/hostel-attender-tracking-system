# 02 — PR plan and workflow

Setting up how work gets shipped, then planning the PRs to the mid-demo.

## 18 Sep 2026 — PR tooling

> @docs/design/submission/CS5013_Project_Design.pdf contains the design doc for the project that I am building and @docs/proposal/CS5013_Project_Proposal.pdf contains the project proposal that contains all details.. This is a course project that I am doing.. so it need not be of production grade.. something that is well written and can be deployed.. Now during my semester I have to ensure that I make consistent PRs to my github repo.. https://github.com/ajoymathew07/hostel-attender-tracking-system.. give commands to setup with git auth and create a script so that it schedules PR's at times random times.. and then sends me a mail once the pull request is up..So that I can review and approve the PR.. don't store any token or password in the repo, keep the secrets in the macOS keychain.. Let me know once this is done,, then we can proceed with implementation of the project

**Techniques:** context (`@` design doc and proposal), constraints, negative prompting (no secrets in repo), prompt chaining (tooling before implementation).

## 18 Sep 2026 — Sync docs with the final design

> ok..now based on @../../docs/design/submission/CS5013_Project_Design.pdf update the docs if there are any discrepancies.. since the project design is the final design of the project.. after that.. create the PR.. give a natural and easy to understand description and what it changes and any why or how if applicable.. make sure PR descriptions are human readable and concise.. something like:
>
> ## What this is
> One line on what the PR does.
> ## What changed and why
> 2-4 short points.
>
> ..add it to prbot.. and open it now and I will approve it via mail

**Techniques:** context, constraints (design PDF is the source of truth), few-shot (PR description example), output format.

## 18 Sep 2026 — PR plan

> I have merged the PR.. delete the old branches.. and once verify that main contains the latest changes.. Now lets build a PR timeline.. no dates required.. only the PR plan .. ensure that we first complete whatever is required for mid demo based on the proposal and the design.. make the PR timeline accordingly.. Make PR small and independent such that it is reviewable.. give the pr plan as a table with PR number, title, what it contains and which PRs it depends on.. don't write any code yet

**Techniques:** prompt chaining (plan before code), constraints, output format (table), negative prompting.

> Alright, the pr plans looks good... but I think this is too granular now.. can we combine some these to make the pr count between 15 - 20.. give the udpated pr plan

**Techniques:** iterative refinement, constraints (15–20 PRs).

## 18 Sep 2026 — Implementation workflow

> yes.. update the pr plan.. Now lets proceed with the implementation.. Act as a senior Java engineer who follows TDD. This is how the workflow is going to be each pr/feature will be implemented in a feature branch and then will be merged through a pr. we will doing TDD where each unit of the program will have unit tests and integration tests (if applicable).. (keep a tests/javatest dir for this.. whichever is apt).. Before opening a PR, always ensure that the unit tests are passing, and add a short table in the pr desc as to which all unit tests passed (as testing section).. like:
>
> | Test | What it checks | Result |
> |---|---|---|
> | CsvComplaintSourceTest | duplicate rows are skipped | pass |
>
> The PR description should be in natural language and easy to read for a human. include any why or how (if necessary).. The PR descripton should be concise and to the point (no jargon).. before opening a PR, run a evaluator to check if all targets or in-scopes of the pr is done and check if there is any fix or code quality changes that needs to done.. the evaluator should act as a senior Java reviewer.. first write the prompt that you will give to the evaluator and show it to me.. once the evaluator verifies or gives a good score, only then open the pr. use prbot to schedule the PR openings.. complete till pr 4 for now and confirm from me before proceeding with the rest of the prs use a an orchestrator that manages teammates and assign work to teammate for like implementing, evaluating etc.. ask me for clarification or design choices if you are stuck .. do not assume.. design doc is the source of truth

**Techniques:** persona (implementer and reviewer), few-shot (test table), acceptance criteria (tests pass, evaluator sign-off), meta-prompting (write the evaluator's prompt first), constraints, prompt chaining (stop after PR 4).
