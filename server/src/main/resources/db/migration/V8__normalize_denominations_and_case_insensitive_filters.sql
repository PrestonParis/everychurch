-- Scaffolding pass (2026-09-22): the Kaggle bulk import (Prompt 7) wrote the dataset's
-- raw `denom` column straight into churches.denomination (apps/scraper/bulk_import.py
-- _to_church_dto_shape) without mapping it onto the site's DENOMINATIONS taxonomy
-- (apps/client/src/app/models/church.model.ts). Against the real 42k-row import, that
-- means the denomination filter dropdown ("Baptist", "Catholic", ...) matched almost
-- nothing, because the stored values were dataset abbreviation codes ("BAPT", "SBC",
-- "AME", ...). This backfills the existing rows; bulk_import.py now applies the same
-- mapping (DENOMINATION_CODE_MAP) at ingest time so re-running the import doesn't
-- reintroduce raw codes. See docs/DATA_MODEL.md "Church.denomination" for the mapping
-- table and the caveat that it's a best-effort alignment, not an authoritative one.
--
-- One addition to the taxonomy itself: LDS accounts for ~5% of the imported rows
-- (2,114 of 42,101) and has no honest home in the existing 12 buckets, so it gets its
-- own "Latter-day Saint (LDS)" entry instead of being folded into "Other" — matches
-- Prompt 7's own framing of the dataset as "inclusive of some non-Trinitarian Christian
-- groups (JW, LDS)". Update DENOMINATIONS in church.model.ts to match (done in this
-- same pass).

UPDATE churches SET denomination = CASE denomination
    -- Baptist
    WHEN 'BAPT'      THEN 'Baptist'
    WHEN 'ABC'       THEN 'Baptist'  -- American Baptist Churches USA
    WHEN 'SBC'       THEN 'Baptist'  -- Southern Baptist Convention
    WHEN 'NBC'       THEN 'Baptist'  -- National Baptist Convention
    WHEN 'MISSBAPT'  THEN 'Baptist'
    WHEN 'BGC'       THEN 'Baptist'  -- Baptist General Conference
    WHEN 'CBF'       THEN 'Baptist'  -- Cooperative Baptist Fellowship
    WHEN 'GBA'       THEN 'Baptist'  -- General/Baptist association
    WHEN 'BBF'       THEN 'Baptist'  -- Baptist Bible Fellowship
    WHEN 'ABC-NBC'   THEN 'Baptist'
    WHEN 'NBC-ABC'   THEN 'Baptist'
    -- Methodist (incl. historically-Black Methodist bodies)
    WHEN 'AME'       THEN 'Methodist'  -- African Methodist Episcopal
    WHEN 'AMEZ'      THEN 'Methodist'  -- African Methodist Episcopal Zion
    WHEN 'UMC'       THEN 'Methodist'  -- United Methodist Church
    WHEN 'CME'       THEN 'Methodist'  -- Christian Methodist Episcopal
    WHEN 'WES'       THEN 'Methodist'  -- Wesleyan Church (Methodist-holiness lineage)
    -- Latter-day Saint
    WHEN 'LDS'       THEN 'Latter-day Saint (LDS)'
    -- Presbyterian / Reformed-Presbyterian
    WHEN 'PCA'       THEN 'Presbyterian'  -- Presbyterian Church in America
    WHEN 'PCUSA'     THEN 'Presbyterian'  -- Presbyterian Church (USA)
    WHEN 'PRESB'     THEN 'Presbyterian'
    WHEN 'EPC'       THEN 'Presbyterian'  -- Evangelical Presbyterian Church
    WHEN 'KPC'       THEN 'Presbyterian'  -- Korean Presbyterian Church
    WHEN 'KPCA'      THEN 'Presbyterian'  -- Korean Presbyterian Church in America
    -- Episcopal/Anglican
    WHEN 'EPIS'      THEN 'Episcopal/Anglican'
    WHEN 'ANGLICAN'  THEN 'Episcopal/Anglican'
    -- Lutheran
    WHEN 'ELCA'      THEN 'Lutheran'  -- Evangelical Lutheran Church in America
    WHEN 'LCMS'      THEN 'Lutheran'  -- Lutheran Church-Missouri Synod
    WHEN 'LUTH'      THEN 'Lutheran'
    -- Church of Christ
    WHEN 'COC'       THEN 'Church of Christ'
    WHEN 'COC ACA'   THEN 'Church of Christ'  -- a cappella
    -- Non-denominational
    WHEN 'NONDENOM'  THEN 'Non-denominational'
    -- Pentecostal / Assembly of God (incl. Holiness-Pentecostal bodies)
    WHEN 'AG'        THEN 'Pentecostal / Assembly of God'
    WHEN 'COG'       THEN 'Pentecostal / Assembly of God'   -- Church of God
    WHEN 'COGA'      THEN 'Pentecostal / Assembly of God'   -- Church of God (Anderson)
    WHEN 'COGIC'     THEN 'Pentecostal / Assembly of God'   -- Church of God in Christ
    WHEN '4SQ'       THEN 'Pentecostal / Assembly of God'   -- Foursquare
    WHEN 'PENT'      THEN 'Pentecostal / Assembly of God'
    WHEN 'APOSTOLIC' THEN 'Pentecostal / Assembly of God'
    WHEN 'UPC'       THEN 'Pentecostal / Assembly of God'   -- United Pentecostal Church
    WHEN 'PAW'       THEN 'Pentecostal / Assembly of God'   -- Pentecostal Assemblies of the World
    WHEN 'IPHC'      THEN 'Pentecostal / Assembly of God'   -- Intl Pentecostal Holiness Church
    -- Evangelical (incl. Evangelical-Holiness bodies with no closer bucket)
    WHEN 'CAL'       THEN 'Evangelical'  -- Calvary Chapel
    WHEN 'VINE'      THEN 'Evangelical'  -- Vineyard
    WHEN 'EF'        THEN 'Evangelical'  -- Evangelical Free
    WHEN 'EVAN'      THEN 'Evangelical'
    WHEN 'CMA'       THEN 'Evangelical'  -- Christian & Missionary Alliance
    WHEN 'EC'        THEN 'Evangelical'  -- Evangelical Covenant
    WHEN 'NAZ'       THEN 'Evangelical'  -- Church of the Nazarene
    WHEN 'CCCC'      THEN 'Evangelical'  -- Conservative Congregational Christian Conf.
    -- Seventh-day Adventist
    WHEN 'SDA'       THEN 'Seventh-day Adventist'
    -- Catalog already-canonical values through unchanged (defensive, no-op)
    WHEN 'Catholic' THEN 'Catholic'
    WHEN 'Non-denominational' THEN 'Non-denominational'
    WHEN 'Lutheran' THEN 'Lutheran'
    WHEN 'Baptist' THEN 'Baptist'
    WHEN 'Presbyterian' THEN 'Presbyterian'
    WHEN 'Other' THEN 'Other'
    -- No honest match in the current taxonomy — bucketed as Other rather than guessed:
    -- CRC/RCA (Reformed), UCC/MCC/UCC/DOC/CHRISTIAN (Congregational/Disciples lineage,
    -- too ambiguous from a 3-8 char code alone), BRETHREN, QUAKER, RELSCI, FCA, PNB.
    WHEN 'CRC' THEN 'Other'
    WHEN 'RCA' THEN 'Other'
    WHEN 'UCC' THEN 'Other'
    WHEN 'MCC/UCC' THEN 'Other'
    WHEN 'DOC' THEN 'Other'
    WHEN 'CHRISTIAN' THEN 'Other'
    WHEN 'BRETHREN' THEN 'Other'
    WHEN 'QUAKER' THEN 'Other'
    WHEN 'RELSCI' THEN 'Other'
    WHEN 'FCA' THEN 'Other'
    WHEN 'PNB' THEN 'Other'
    -- "UNK" is the dataset's own "unknown" marker, not a denomination — clear it so it
    -- behaves the same as the ~18% of rows that came in with no denom value at all.
    WHEN 'UNK' THEN NULL
    ELSE denomination
END
WHERE denomination IS NOT NULL;

-- Filters (city/state/denomination on GET /api/churches, Prompt 2) were exact
-- case-sensitive matches against free-text input, while the bulk-imported data is
-- stored upper-cased ("CHICAGO", "IL") — a user typing the natural-cased "Chicago"
-- got zero results. ChurchRepository.search now compares UPPER(column) = UPPER(:param)
-- (see that change in the same pass); these functional indexes keep that seq-scan-free
-- at 250k+ rows the same way the plain-column indexes did for the old exact match.
CREATE INDEX idx_churches_city_upper ON churches (UPPER(city));
CREATE INDEX idx_churches_state_upper ON churches (UPPER(state));
CREATE INDEX idx_churches_denomination_upper ON churches (UPPER(denomination));
