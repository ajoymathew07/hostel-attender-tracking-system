# 03 — Implementation

Building the PRs from the plan, up to the mid-demo scope.

## 21 Sep 2026 — Continue the plan

> what is the status of the PR's.. give it as a table with PR number, branch, status (draft / queued / open / merged)

**Techniques:** output format.

> yes.. proceed.. complete PR-2 and then implement and push PR-3 and PR-4 as well.. same rules as before, tests must pass and evaluator must sign off before each one

**Techniques:** prompt chaining, acceptance criteria.

## 22 Sep 2026 — Room number parsing

> The room number are usualluy 100- 199 then 200- 299.. and so on and there can be rooms like 1000, 1001, .. 1099.. etc.. note.. that entire range maynot be there.. as it could be only 100 - 169.. 169 represents ground floor, similarly 10xx represents ground floor, 2xx and 2xxx represent 1st floor and so on.. there are 3 floors in Cauvery hostel.. email need not be required.. some examples of what should come out:
>
> - `"169"` → room 169, ground floor
> - `"1099"` → room 1099, ground floor (same floor as 169)
> - `"2043"` → room 2043, first floor
> - `"Room 214"` → room 214
> - `"a-214"` → room 214
> - `"12"` → not a room
>
> add these as unit tests first and then fix the parser so they pass

**Techniques:** few-shot (input → expected output), TDD as acceptance criteria.

## 22 Sep 2026 — Keep the code minimal

> no, no need to fix the doc.. now.. You can continue with next PR in the pipeline.. Also, one reminder, our goal is build the working prototype as soon as possible.. The code need not be too complex, we want a minimal code that satifies all the design choices and features that are required as per the design doc.. The reason why we are doing this way is because I will have to explain code in viva.. So goal is simple, don't overcomplicate or write too complex code.. don't add interfaces with only one implementation, extra abstraction layers or config for things that never change.. It should be sufficiently complex to satify all the features of the product as mentioned in the google doc. ..

**Techniques:** constraints with the reason, negative prompting (what over-engineering looks like).

## 26–27 Sep 2026 — Shipping and rebasing

> Ship the next 3 PR's.. open the pull request as well immediately.. remember the previous choices and rules that we decided.. TDD, evaluator before opening, short PR desc with test table, minimal code

**Techniques:** context (restating the rules), constraints.

> https://github.com/ajoymathew07/hostel-attender-tracking-system/pull/10.. fix the conflicts.. rebase to latest main.. don't change any behaviour while fixing the conflicts, if a conflict needs a decision ask me.. run the tests after rebase.. after this proceed with the rest of the PRs

**Techniques:** negative prompting, acceptance criteria (tests after rebase), constraints.
