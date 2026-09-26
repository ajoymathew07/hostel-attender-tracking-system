-- The lock the scheduled Sheets poll takes, so two instances cannot double-ingest
-- (architecture section 2.5, point 4).
--
-- It lives in the one database that is already the single source of truth (section 2.6): a second
-- piece of infrastructure — Redis, ZooKeeper — purely to hold one lock would be the wrong trade at
-- this scale. The shape is ShedLock's, which owns these four columns.
--
-- This is belt and braces over `uq_source_row`, not a replacement for it. The constraint is what
-- makes a second complaint impossible; the lock is what stops a second instance paying to find
-- that out.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
