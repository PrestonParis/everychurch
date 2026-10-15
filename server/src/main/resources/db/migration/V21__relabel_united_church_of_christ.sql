-- The HIFLD import's name matching labelled every "... UNITED CHURCH OF CHRIST" as
-- "Church of Christ" (its "church of christ" keyword hit first). The United Church of
-- Christ is a Congregational body, unrelated to the Churches of Christ, and bulk_import.py
-- already buckets its "UCC" code as "Other" — so these move to "Other" to match.
-- Claimed churches are left to their owners. Idempotent.
UPDATE churches
SET denomination = 'Other'
WHERE denomination = 'Church of Christ'
  AND name ILIKE '%united church of christ%'
  AND claimed = false;
