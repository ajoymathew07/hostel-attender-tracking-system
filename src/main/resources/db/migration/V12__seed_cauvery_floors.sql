-- The three floors of Cauvery (data model section 3.4).
--
-- Dispatch cannot score anything until `floor` has rows: a complaint whose room maps to no floor
-- goes to triage as UNMAPPED_LOCATION, and with an empty table that would be every complaint.
-- Coverage (`floor_assignment`) stays the manager's to set — this seeds the building, not the
-- roster.
--
-- Cauvery room numbers are numeric only and the first digit is the floor, for both the 3-digit
-- (100-399) and the 4-digit (1000-3099) shapes: 169 and 1099 are both on floor 1. The wing is not
-- in the number, so the hostel is one wing here and `dispatch.wing` names it.
INSERT INTO floor (wing, floor_number, label) VALUES
    ('CAUVERY', 1, 'Ground floor'),
    ('CAUVERY', 2, 'First floor'),
    ('CAUVERY', 3, 'Second floor')
ON CONFLICT ON CONSTRAINT uq_floor DO NOTHING;
