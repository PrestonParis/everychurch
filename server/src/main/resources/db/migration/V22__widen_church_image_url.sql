-- Wikimedia Commons thumbnail URLs (wikidata_fill.py) carry the file name, and 16 of the
-- first nationwide batch ran past 255 characters (up to 368), failing the whole /fill
-- request. 1024 matches posts.image_url (V13).
ALTER TABLE churches ALTER COLUMN image_url TYPE character varying(1024);
