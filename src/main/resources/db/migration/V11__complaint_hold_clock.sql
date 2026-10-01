-- When the SLA clock was paused, so that T13 can extend the due date by the hold (section 4.5).
--
-- Hold time is excluded from the SLA and from aging: "waiting three days for the Engineering Unit"
-- is not attender slowness, and counting it as such would point section 8's bottleneck analysis at
-- exactly the wrong stage.
--
-- One nullable column rather than a hold-period child table. The complaint can be on hold once at
-- a time, `sla_due_at` already carries the accumulated effect of every earlier hold, and the
-- individual hold spans are in `audit_event` — which is where the history belongs (ADR-012).
-- NULL means the clock is running.

ALTER TABLE complaint ADD COLUMN hold_started_at TIMESTAMP;
