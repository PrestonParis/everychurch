-- geo_point is deliberately NOT mapped through JPA (Hibernate doesn't natively speak
-- PostGIS types) — the application writes/reads it via native SQL only, keeping it in
-- sync with latitude/longitude on every write (see ChurchRepository/ChurchService).
ALTER TABLE churches ADD COLUMN geo_point geography(Point, 4326);

CREATE INDEX idx_churches_geo_point ON churches USING gist (geo_point);

-- Trigram indexes back the fuzzy `%` similarity search in Prompt 2's
-- ChurchRepository.search.
CREATE INDEX idx_churches_name_trgm ON churches USING gin (name gin_trgm_ops);
CREATE INDEX idx_churches_city_trgm ON churches USING gin (city gin_trgm_ops);
