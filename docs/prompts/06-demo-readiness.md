# 06 — Demo readiness

Walking through the demo flow on the deployed app and fixing what came up.

## 4 Oct 2026 — Demo walkthrough

> I want to test the functionalities that I would do for demo and see if everything works.. for that .. tell me what all does our current app offer.. list it by role (manager, attender) and mark which ones are part of the mid demo scope in @docs/design/pr-plan.md

**Techniques:** context (`@` PR plan), output format.

> Hostel Duty Tracker
> Cauvery Hostel, IIT Madras
>
> That Google account is not permitted to use this system. Ask the hostel manager to add it.
>
> .. I am getting this error.. its because I didn't update the db.. update the db with my email and also merge and deploy the the latest branch(open the PR first), I will merge it

## 5 Oct 2026 — Settings, working days, UI copy

> Floor proximity itself is fixed, because only the order of its values means anything: the floor itself scores 1.0 as primary and 0.7 as secondary, an adjacent floor 0.4, and each further floor 0.05 less, never below 0.1... remove this line from settings.. even days a week, in Asia/Kolkata — a hostel does not close on Sunday... remove this line.. and make it 5 days a week in backend.. Assume that hostel is working from Monday to Friday.. Also, its open on saturday till noon as well, but we will look into it later.. first lets keep it simple, and have it from monday to friday.. [Image #1] why is it repeated twice.. Uploading the same file twice is safe — a response that is already held is recognised and skipped, so nothing is duplicated... you can remove this sentence from the frontend.. he offline fallback for when the Google Sheet cannot be read. Export the response sheet as CSV and upload it here.. can you phrase this better from a user/manager perspective insteead of the devloper.. the manager is not technical, so no words like fallback or sheet sync.. [Image #2].. why am I unable to resolve this task, add cms docket number etc.. it used to be there before.. check which commit removed it before fixing

**Techniques:** context (screenshots, quoted text), constraints (Mon–Fri only for now), persona of the reader, debugging via git history.

## 5 Oct 2026 — CI failure

> https://github.com/ajoymathew07/hostel-attender-tracking-system/actions/runs/37268755584/job/111648086753.. check why this is failing and fix it.. tell me the failing test and the root cause first.. don't skip or disable the test to make it pass

**Techniques:** context (CI run), prompt chaining (root cause first), negative prompting.
