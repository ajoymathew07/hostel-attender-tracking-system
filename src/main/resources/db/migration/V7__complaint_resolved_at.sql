-- Section 11.4 — the attender's view of the student's name, roll number and phone number expires
-- 7 days after the complaint is resolved.
--
-- The column list in data model section 3.6 has `closed_at` but no resolution timestamp, and the
-- window is measured from resolution, not closure: a manager may close early (T15) or reopen
-- (T17), so `closed_at` answers a different question. `updated_at` is not it either — any later
-- write moves it. State machines section 4.2 needs the same instant for T16 (`auto_close` fires
-- 7 days after resolution), so one nullable column serves both rules.
ALTER TABLE complaint ADD COLUMN resolved_at TIMESTAMP;

COMMENT ON COLUMN complaint.resolved_at IS
    'When the complaint reached RESOLVED. Start of the 7-day attender PII window (section 11.4).';
