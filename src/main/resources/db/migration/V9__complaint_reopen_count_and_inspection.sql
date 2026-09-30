-- State machines section 4.2 — the two columns the transition table needs and that the column
-- list in data model section 3.5 predates.
--
-- `reopen_count` is the `n` of the resolve credit's idempotency key,
-- RESOLVE:{task}:{attender}:r{n} (section 7.3). Without it, a re-resolution after a reopen (T17)
-- would present the same key as the credit the reopen had just reversed, be recognised as a
-- replay, and the attender would redo the work for nothing.
--
-- Note what this is not: it is a count of an event, not a stored balance. Section 7.3 forbids a
-- points counter because a balance is a SUM over immutable ledger rows; the reopen count is a
-- fact about the complaint, and it is what keeps that SUM correct across a reopen cycle.
--
-- `inspection_started_at` is the single side effect section 4.2 gives T7.
ALTER TABLE complaint ADD COLUMN reopen_count          INTEGER NOT NULL DEFAULT 0;
ALTER TABLE complaint ADD COLUMN inspection_started_at TIMESTAMP;

COMMENT ON COLUMN complaint.reopen_count IS
    'Times a manager has reopened this complaint: the n of RESOLVE:{task}:{attender}:r{n} (7.3).';
COMMENT ON COLUMN complaint.inspection_started_at IS
    'When the assigned attender started the inspection (transition T7).';
