# 05 — Scope change and deployment

Finishing the remaining PRs, dropping the keyword classifier, and deploying to Render.

## 1 Oct 2026 — Finish and deploy

> refer Hostel attender trackign system PR scheduler and get the PR timeline and the latest status.. Complete the rest of the PRs and deploy/host the app.. before deploying give me the list of env vars and accounts you need from me

**Techniques:** context (PR plan), prompt chaining.

## 1 Oct 2026 — Remove the classifier

> what was implemented just before.. Note, we don't need to categorize complaints.. lets assume that there is column which contains whether the complaint is civil, electrical, lan or RO or others.. So if any matching was used to categorize the complaints.. remove those code.. for example the form column will have values like:
>
> - `Electrical` → ELECTRICAL
> - `civil` → CIVIL
> - `LAN` → NETWORK
> - `RO` → RO_PLANT
> - anything else → UNCLASSIFIED, so the manager can set it by hand
>
> don't touch the dispatch or priority logic, only the categorization part.. all tests should still pass after removing it

**Techniques:** few-shot (column value → category), negative prompting, acceptance criteria.

> since the classifier is removed.. can you revert the change in main that was pushed in that related to classifier

## 2 Oct 2026 — Replace polling with a push

> can we use the google sheets app script to make the request to the app whenever there is a modification.. shouldn't that fix this issue and then we can remove polling from the system.. explain the trade-offs first (cold start, missed triggers, security of the endpoint) and then we can decide

**Techniques:** prompt chaining (trade-offs before decision), constraints.

## 3 Oct 2026 — End-to-end check and cold start

> fix the errors.. I have submitted a test response.. also what can we do about the long cold start.. how can we reduce the cold start latency.. measure the current startup time from the render logs first, then suggest options with the expected saving for each.. don't add any paid service

**Techniques:** context, prompt chaining (measure first), output format, negative prompting.

> make the jvm flag change its alright.. 15 seconds is too much
