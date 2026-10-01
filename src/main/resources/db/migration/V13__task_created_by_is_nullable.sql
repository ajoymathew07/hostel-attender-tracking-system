-- A task the dispatcher created has no human creator.
--
-- `created_by` was NOT NULL because, until auto-dispatch, every task came from a manager pressing
-- Assign (data model section 3.6). T2 now creates the task itself, during ingestion, with nobody
-- signed in — and attributing it to a seeded manager who did nothing would put a false claim in
-- the one place section 11.7 relies on being true.
--
-- `task_assignment.assigned_by` is already nullable for the same reason, so the two columns now
-- say the same thing: null means the system, not a person.
ALTER TABLE task ALTER COLUMN created_by DROP NOT NULL;
