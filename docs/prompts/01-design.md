# 01 — Design

Phase 1: turning the proposal into the design doc and the submitted design summary.

## 8 Sep 2026 — Kickoff and design brainstorm

> So this session is to build the course project on Hostel Complaint and Attender Tracking system. @CS5013_Project_Proposal.pdf this contains the proposal  on the problem statement, the purpose, why its required, what all it must do. .The system scope and technical architecture.. https://github.com/ajoymathew07/hostel-attender-tracking-system.git here is the repo in which we will be working on.. Act as a senior software engineer with experience in Java and Spring Boot who is designing this system from scratch. We will be following good production standards and formal way to creating branches for each features and mergeing through a pull request.. The project will be implemented in java except for the frontend and other things that are strictly necessary as mentioned in the tech stack . The goal of this task to bulid the phase 1 which is design doc for this project.. the tasks that need to be completed as part of design is given in the proposal itself.. Don't start writing the design doc yet. First make the list of tasks and lets quickly brainstorm any major challenges or decision decision before heading to the design.. give the output as a numbered list of tasks, then a list of open decisions with 2-3 options each and your recommendation

**Techniques:** context (`@` proposal), persona, prompt chaining (brainstorm before design), negative prompting, output format.

## 8 Sep 2026 — Design summary for submission

> few corrections.. lets add push notifications to attenders phone as well.. This design doc looks good.. we need to build a summary of this which is around 12 pages which I can submit for design submission.. these are the things that it must contain: short overview of problem, inscore, out of scope, actors, System architecutre: include the following, monolith Spring boo jar, context diagrams and module breakdown (represent it a flow chart or which suits best, more visual), this should include Module, Java package, short description of what it is for.. pipeline (you could combine this with context diagram), deployment topology, Er model, audit log data that would be present, state diagram of a complaint/task, priority logic, dispatch heurestic, tech stack for each phase/module of the project, include authentication also at the start.. add somehting about PII's as well , test plan.. This is something that needs to be submitted so we lets use latex for this along, along with flow charts or images wherever required.. Keep it within 12 pages, don't add anything that is not already decided in the design doc.. and make sure it compiles without errors before you give it to me

**Techniques:** constraints (page limit, required sections), output format (LaTeX with diagrams), negative prompting, acceptance criteria (compiles cleanly).

## 8 Sep 2026 — Cloud outage scope and simplification

> So when I mean outage, I mean cloud services outage.. this is the case for which we need local fallback.. Can you update the pdf and other markdown files based on this.. You can assume that we have access to internet at any time and there is not outage in internet.. Should we change the Auth type based on this?.. Also have we complicated any part in the system which is unnecessary identiy such parts and lets simplify them if possible.. (discuss with me).. because at the end I would have to explain every steps and code that we build.. Think step by step through each module and list what is over-engineered, why, and what the simpler option is.. don't change anything in the docs until I agree

**Techniques:** constraints (assumptions stated), chain of thought (module by module), negative prompting, prompt chaining (discuss before change).

## 8 Sep 2026 — Working system first

> Also, instead of trying to do everything together.. lets try to buld a working system and then build additional/optional features on top of that..Our first goal should be create a working app.. Update the .md file accordingly, so that minimal feature are given priority that solves the requirements of the problem and add the parts / feature after that which we should later add on to it.. don't have to change the summary the final architecture and feature looks good to me. Split it as two lists: "must have for working app" and "later add-ons".. Also I interuppted the previous task by mistake

**Techniques:** constraints, negative prompting (leave the summary alone), output format.

## 9 Sep 2026 — Auth decision

> lets use GAuth itself as that is the standard.. isn't that easier to implement.. answer me first.then we can decide.. compare it with password login on implementation effort, security and what I have to explain in viva.. as a small table

**Techniques:** prompt chaining (answer before deciding), output format (comparison table).

> wait.. why does manager need a username and password.. we can assume that manager will also have internet anytime he wants to access this data.. In this case, is Gauth better for manager or username + password

> apply it.. also tell me which section is changed.. also Within modules we use ports and adapters only where an external system is involved. This is not decoration:
> the ingestion port is what lets the Google Sheets adapter and the CSV fallback adapter share one normalization,
> dedupe and categorization pipeline instead of drifting into two code paths... can you explain this in detail here in chat.. maybe rephrase it to make it more natural and easy to understand.. explain it like you are explaining to a student who knows java but not this pattern, with a small example from our ingestion module

**Techniques:** context (quoted section), persona for the audience, few-shot (example from our own module).
