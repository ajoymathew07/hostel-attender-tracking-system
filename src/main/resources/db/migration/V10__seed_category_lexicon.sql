-- The starting lexicon for the weighted classifier (section 5.5, ADR-004).
--
-- A starting point the manager extends, not an exhaustive vocabulary. The whole reason the lexicon
-- is a table rather than a constant is that it is editable without a redeploy: terms are added,
-- reweighted and retired from the data, and this file is never edited once applied.
--
-- WEIGHTS. Section 5.5: multi-word phrases carry higher weights than single tokens because they
-- disambiguate. `water` alone is nearly useless — it appears in RO Plant, Civil and Electrical
-- complaints — so it is not seeded at all, while `no water` and `water leaking` are decisive and
-- carry 3.00. Three bands:
--
--   3.00  a decisive phrase — on its own it settles the domain
--   2.00–2.50  a strong single term, or a phrase that is suggestive rather than decisive
--   1.50  an ordinary single term
--   1.00  a weak term that only counts alongside something else
--
-- The 1.50 band is chosen against the gate rather than by feel. S_min is 1.00 and a fuzzy hit
-- earns 0.70 of a term's weight, so a single ordinary term matched through a misspelling scores
-- 1.05 and still clears the absolute gate — which is the entire point of fuzzy matching. A 1.00
-- term matched fuzzily scores 0.70 and does not, which is also right: one weak word, spelled
-- wrong, is not evidence.
--
-- A term deliberately appears in more than one domain where the real word is ambiguous (`leak`,
-- `tap`). That is not a mistake to be resolved by picking one: it is how the ambiguity the manager
-- has to settle shows up in the scores, as two domains close together and a confidence near zero.

INSERT INTO category_term (domain, term, weight, fuzzy, active, created_at) VALUES
    -- Electrical
    ('ELECTRICAL', 'short circuit',   3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'power cut',       3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'tube light',      2.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'air conditioner', 2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'wiring',          2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'shock',           2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'spark',           2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'electrical',      1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'fan',             1.50, FALSE, TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'bulb',            1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'switch',          1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'socket',          1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'fuse',            1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'geyser',          1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('ELECTRICAL', 'light',           1.50, FALSE, TRUE, TIMESTAMP '2026-09-27 00:00:00'),

    -- Civil
    ('CIVIL', 'water leaking',  3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'false ceiling',  2.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'seepage',        2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'carpentry',      2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'drainage',       2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'sewage',         2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'plaster',        1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'ceiling',        1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'leaking',        1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'leak',           1.50, FALSE, TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'crack',          1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'window',         1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'door',           1.50, FALSE, TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'tiles',          1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'broken',         1.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('CIVIL', 'wall',           1.00, FALSE, TRUE, TIMESTAMP '2026-09-27 00:00:00'),

    -- RO plant and water supply
    ('RO_PLANT', 'no water',        3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'ro plant',        3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'drinking water',  3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'water purifier',  3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'water supply',    2.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'water cooler',    2.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'purifier',        2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'borewell',        1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'overflow',        1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'filter',          1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'tap',             1.50, FALSE, TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('RO_PLANT', 'tank',            1.00, FALSE, TRUE, TIMESTAMP '2026-09-27 00:00:00'),

    -- Network
    ('NETWORK', 'no internet',   3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('NETWORK', 'slow internet', 3.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('NETWORK', 'lan port',      2.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('NETWORK', 'internet',      2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('NETWORK', 'wifi',          2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('NETWORK', 'ethernet',      2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('NETWORK', 'router',        2.00, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('NETWORK', 'network',       1.50, TRUE,  TRUE, TIMESTAMP '2026-09-27 00:00:00'),
    ('NETWORK', 'lan',           1.50, FALSE, TRUE, TIMESTAMP '2026-09-27 00:00:00');

-- `fuzzy = FALSE` on the short, common words. Jaro–Winkler is generous about a shared prefix, and
-- `fan`, `tap`, `lan`, `leak`, `door`, `wall`, `light` and `tank` all sit one or two characters
-- from ordinary English that has nothing to do with maintenance. Those terms still match exactly;
-- what is switched off is guessing at them.
