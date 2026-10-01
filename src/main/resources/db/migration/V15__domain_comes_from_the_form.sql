-- The resident says which domain it is; nothing infers it any more.
--
-- The Google Form now asks for the category directly — Civil, Electrical, LAN, RO or Others — so
-- the weighted lexicon of section 5.5, its fuzzy matching and its confidence gate have no job. A
-- resident choosing from five options is better evidence than a term list guessing from their
-- prose, and "Others" is a resident saying they do not know, which is exactly what the triage
-- queue is for.
--
-- Three consequences, in order:

-- 1. The lexicon's table goes. Nothing reads it.
DROP TABLE IF EXISTS category_term;

-- 2. There is no confidence to record. The column held the gate's score, and without a gate the
--    number would be a 1.000 on every row, which is worse than absent: it would read as
--    certainty the system never computed.
ALTER TABLE complaint DROP CONSTRAINT IF EXISTS ck_conf;
ALTER TABLE complaint DROP COLUMN IF EXISTS domain_confidence;

-- 3. `classified_by` keeps its three values but one is renamed: RULES described the lexicon
--    clearing its gate, and what happens now is that the resident reported it. Existing rows are
--    moved rather than left to fail the enum read on the next startup.
--    The column is now REPORTED | MANUAL | FALLBACK; V2's comment naming RULES is stale, and
--    migrations are forward-only (architecture section 2.7), so this is where that is recorded.
UPDATE complaint SET classified_by = 'REPORTED' WHERE classified_by = 'RULES';
