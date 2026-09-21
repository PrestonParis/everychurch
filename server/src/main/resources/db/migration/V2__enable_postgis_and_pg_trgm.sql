-- PostGIS gives us a true geography type + GiST KNN index for nearest-church lookups
-- at 250k+ rows (V3); pg_trgm gives fuzzy trigram matching for name/city search
-- (Prompt 2), both far better suited to that scale than in-app Haversine sorting or a
-- plain LIKE '%...%' scan.
--
-- Fallback if Neon's plan doesn't allow CREATE EXTENSION postgis: this migration will
-- fail loudly on startup against that instance — intentional, so the gap is visible
-- immediately rather than silently degrading. The fallback in that case is a plain
-- bounding-box pre-filter (WHERE latitude BETWEEN ... AND longitude BETWEEN ...) backed
-- by a composite B-tree index on churches(latitude, longitude) instead of true PostGIS
-- KNN — meaningfully less precise (a box, not a radius) and unindexed for true distance
-- ordering, but works on any plain Postgres. Local dev's docker-compose.yml already
-- runs postgis/postgis instead of postgres:16-alpine specifically so this succeeds
-- locally regardless of what Neon ends up supporting.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
