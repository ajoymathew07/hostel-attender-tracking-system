-- Act-as (section 2.1): when the manager performs an attender's step during roll-call, the audit
-- row names the manager as the actor and the attender here. The points still go to the attender.
ALTER TABLE audit_event ADD COLUMN on_behalf_of BIGINT REFERENCES app_user(id);
