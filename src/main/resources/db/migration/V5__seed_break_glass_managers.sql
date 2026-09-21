-- Item 1.3 of section 13.3, and ADR-002's "break-glass is a row, not a mechanism".
--
-- Two MANAGER rows exist from day one. If either account becomes inaccessible the other can still
-- administer, including repointing the allowlist at a different Google address — which is the
-- whole recovery story now that no password exists anywhere in the schema.
--
-- BOTH ADDRESSES ARE PLACEHOLDERS AND MUST BE REPLACED BEFORE THE FIRST SIGN-IN.
--
-- They use the reserved `.invalid` top-level domain (RFC 2606), so neither can be a real Google
-- account: until someone replaces them nobody can sign in at all. That is deliberate. A seed that
-- shipped a *plausible* address would be a live manager account whose owner nobody had checked,
-- and a deployment that silently works with a stranger as manager is worse than one that visibly
-- does not work yet.
--
-- To replace them, either edit them on the provisioning screen once one real manager is in, or —
-- the day-zero path, before anyone can sign in — run:
--
--   UPDATE app_user SET email = 'real.manager@gmail.com'
--    WHERE email = 'manager.placeholder@replace-me.invalid';
--   UPDATE app_user SET email = 'hostel.office@gmail.com'
--    WHERE email = 'breakglass.placeholder@replace-me.invalid';
--
-- Forward-only: this file is never edited once applied. The addresses are changed with an UPDATE,
-- not by rewriting history (architecture section 2.7).

INSERT INTO app_user (display_name, role, email, active, created_at, created_by)
VALUES ('PLACEHOLDER — hostel manager',
        'MANAGER',
        'manager.placeholder@replace-me.invalid',
        TRUE,
        TIMESTAMP '2026-09-21 00:00:00',
        NULL),
       ('PLACEHOLDER — break-glass manager',
        'MANAGER',
        'breakglass.placeholder@replace-me.invalid',
        TRUE,
        TIMESTAMP '2026-09-21 00:00:00',
        NULL);
