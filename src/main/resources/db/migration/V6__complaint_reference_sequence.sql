-- The allocator behind `complaint.public_ref` — `CAU-{year}-{zero-padded sequence}` (section 5.4).
--
-- A sequence rather than `max(public_ref) + 1`. Two ingestion runs, or the Sheets poll racing a
-- CSV upload, would read the same maximum and allocate the same reference; the second insert would
-- then fail on `uq_complaint_ref` and lose a complaint that was otherwise perfectly good.
-- `nextval` is atomic and deliberately does not roll back, so a failed transaction burns a number
-- rather than colliding. A gap in the sequence is invisible to everyone; a duplicate reference is
-- a complaint the manager cannot find.
--
-- Global rather than per year, with the year as a prefix: `uq_complaint_ref` needs the whole
-- string to be unique, not the counter, and a per-year sequence would need a reset run correctly
-- once a year by something — a moving part bought for cosmetics.

CREATE SEQUENCE complaint_ref_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;
