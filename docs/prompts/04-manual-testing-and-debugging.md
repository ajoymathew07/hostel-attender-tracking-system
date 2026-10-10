# 04 — Manual testing and debugging

First end-to-end run of the app after the mid-demo PRs were merged.

## 30 Sep 2026 — Boot the app

> Alright, all the PR's that were opened are merged.. I want to to test the current setup.. let me know what details are required from my side.. and boot up the app so that I can see its working.. give instruction on how to use it and test it as well.. as numbered steps for manager and then for attender, with what I should see after each step

**Techniques:** prompt chaining (requirements first), output format (step + expected result).

## 30 Sep 2026 — Dashboard copy, assign field, attender bug

> The offline fallback for when the Google Sheet cannot be read. Export the response sheet as CSV and upload it here. Uploading the same file twice is safe — a response that is already held is recognised and skipped, so nothing is duplicated... Reads the Google Form responses immediately instead of waiting for the next poll.Student names, roll numbers and phone numbers are the residents' personal data, shown here for complaint handling and oversight (section 11.4)... remove this from the dashboard.. before that.. rest seems working fine.. Althought, the[Image #2].. assigning to attender is not very intuitive in this case.. beacuse the field is too small and narrow.. fix it.. Also, I noticed that attender is not able to view his alloted complaints even though a complaint was assigned to him.. check whats the issue here.. Steps to reproduce: sign in as manager, assign a complaint to the attender, sign in as that attender, the task list is empty. First reproduce this in a failing test, find the root cause and explain it to me, then fix it.. don't change the assignment rules or the ownership check to make it pass

**Techniques:** context (screenshot, quoted text), reproduction steps, prompt chaining (root cause before fix), regression test, negative prompting.

## 30 Sep 2026 — 403 on resolve

> Whitelabel Error Page
>
> This application has no explicit mapping for /error, so you are seeing this as a fallback.
>
> Wed Sep 30 10:50:47 IST 2026
> There was an unexpected error (type=Forbidden, status=403).
>
> .. getting this when attender tries to resolve the task.. check the app log for that request and tell me which check is refusing it and why before you change anything.. it should still be refused for an attender who isn't assigned to the task

**Techniques:** context (exact error), prompt chaining (diagnose before fix), constraints (keep the security rule).

> Number the Engineering Unit gave you.. change this to enter the cms docket number
