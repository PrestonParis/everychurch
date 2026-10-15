-- RELEASED: an approved claim whose ownership was later given up by the owner or taken
-- back by a Platform Admin (ClaimService.release). Kept rather than deleted so the
-- claim history still shows who owned the church and when.
ALTER TABLE claims DROP CONSTRAINT claims_status_check;
ALTER TABLE claims ADD CONSTRAINT claims_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'RELEASED'));
