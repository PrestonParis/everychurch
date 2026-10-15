package com.everychurch.server.repository;

import com.everychurch.server.model.Church;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChurchRepository extends JpaRepository<Church, Long> {

    // Only GET /api/churches/{id} needs the full service-time schedule — see the
    // comment on Church.serviceTimes. Overriding findById here (rather than adding a
    // separately-named method) means every existing caller gets it for free.
    @Override
    @EntityGraph(attributePaths = "serviceTimes")
    Optional<Church> findById(Long id);

    // Per-word AND match, not a single whole-string fuzzy match: each whitespace-split
    // word of :query must independently pass word_similarity(word, name-or-city) >= 0.5
    // (NOT EXISTS a word that fails both). Found against the real bulk-imported data —
    // the previous `c.name % :query OR c.city % :query` fed the *entire* query string
    // ("Catholic shrine") into a single similarity() call, which is a set-overlap ratio
    // over the whole string's trigrams; a short common word can look deceptively similar
    // to an unrelated short city/name this way (e.g. "Catholic shrine" scored 0.37+
    // against a city literally named "Catherine", pulling in an unrelated Presbyterian
    // church — the word "shrine" never has to match anything for that to happen).
    // word_similarity finds the best-matching substring of the *long* operand for a
    // *short* probe word instead, which is the right primitive per-word, but its own
    // default operator threshold (`<%`, ~0.6) turned out to reject legitimate one-letter
    // typos ("Cathlic" scored 0.545 against "...CATHOLIC..."); 0.5 (explicit, not the
    // `<%` operator's GUC-controlled default) is the lowest threshold that still rejects
    // the "Catherine" false positives above (0.44 and 0.43) while keeping that typo and
    // others like it ("Batist" 0.50, "Luteran" 0.55, "Methdist" 0.58) — see the search
    // relevance fix (2026-09-22) for the full before/after comparison this was tuned
    // against. Blank filters are passed as "" rather than null so a single bind-parameter
    // set works for both the data and count queries without null-handling differences
    // across drivers.
    // Column list spelled out (not SELECT *) rather than pulling in geo_point — an
    // exotic PostGIS type with no JPA mapping that's safest kept out of an entity
    // auto-mapped native query result set entirely, not just unused by the entity.
    //
    // city/state compare UPPER(column) = UPPER(:param) rather than plain `=` — found
    // against the real bulk-imported data (V8 migration), which is stored upper-cased
    // ("CHICAGO", "IL"): a user typing the natural-cased "Chicago" into the city filter
    // got zero results with a bare `=`. The V8 functional indexes (idx_churches_city_upper
    // etc.) keep this seq-scan-free.
    //
    // denomination is matched by keyword/substring per curated label rather than an exact
    // string match: bulk-imported/scraped data stores raw abbreviations ("SBC", "NBC",
    // "COC") that never equal the curated DENOMINATIONS labels the client sends
    // ("Baptist", "Church of Christ"). Mirrors the client's own classification in
    // church-list.component.ts's denomStyle(). ILIKE '%...%' here is a seq scan, but at
    // this row count (tens of thousands) that's still fast; revisit with a trigram index
    // if it ever shows up in profiling. "Other" matches anything non-blank that doesn't
    // hit any other branch. UPPER(:denomination) = UPPER('Baptist') rather than a bare
    // `=` — caught by ChurchSearchTest.cityStateAndDenominationFiltersAreCaseInsensitive,
    // which (per its own case-insensitivity intent) sends the filter lowercased
    // ("presbyterian"); a bare `:denomination = 'Presbyterian'` never matched that and
    // silently fell through every branch to zero results.
    // "Church of Christ": the stored label (bulk_import.py / V8 map "COC" to it) plus a raw
    // "COC" code. It never matched the label itself until 2026-10-03 — the filter returned
    // nothing and those churches showed under "Other". "United Church of Christ" is a
    // different (Congregational) body and stays under "Other".
    @Query(
        value = """
            SELECT c.id, c.name, c.denomination, c.address, c.city, c.state, c.zip_code,
                   c.phone, c.email, c.website, c.description, c.image_url, c.pastor,
                   c.facebook, c.instagram, c.youtube, c.claimed, c.latitude, c.longitude,
                   c.service_summary
            FROM churches c
            WHERE (:query = '' OR NOT EXISTS (
                SELECT 1 FROM unnest(regexp_split_to_array(trim(:query), '\\s+')) AS q(word)
                WHERE word_similarity(q.word, c.name) < 0.5
                  AND word_similarity(q.word, COALESCE(c.city, '')) < 0.5
              ))
              AND (:denomination = '' OR
                (UPPER(:denomination) = UPPER('Baptist') AND (c.denomination ILIKE '%bapt%' OR c.denomination ILIKE '%sbc%' OR c.denomination ILIKE '%nbc%')) OR
                (UPPER(:denomination) = UPPER('Catholic') AND c.denomination ILIKE '%cath%') OR
                (UPPER(:denomination) = UPPER('Church of Christ') AND (c.denomination ILIKE '%coc%' OR (c.denomination ILIKE '%church%of christ%' AND c.denomination NOT ILIKE '%united church of christ%'))) OR
                (UPPER(:denomination) = UPPER('Eastern Orthodox') AND c.denomination ILIKE '%orthodox%') OR
                (UPPER(:denomination) = UPPER('Episcopal/Anglican') AND (c.denomination ILIKE '%episcop%' OR c.denomination ILIKE '%anglic%')) OR
                (UPPER(:denomination) = UPPER('Evangelical') AND c.denomination ILIKE '%evangel%') OR
                (UPPER(:denomination) = UPPER('Latter-day Saint (LDS)') AND (c.denomination ILIKE '%latter-day%' OR c.denomination ILIKE '%lds%' OR c.denomination ILIKE '%mormon%')) OR
                (UPPER(:denomination) = UPPER('Lutheran') AND c.denomination ILIKE '%luther%') OR
                (UPPER(:denomination) = UPPER('Methodist') AND c.denomination ILIKE '%method%') OR
                (UPPER(:denomination) = UPPER('Non-denominational') AND (c.denomination ILIKE '%non-denom%' OR c.denomination ILIKE '%nondenom%')) OR
                (UPPER(:denomination) = UPPER('Pentecostal / Assembly of God') AND (c.denomination ILIKE '%pentecost%' OR c.denomination ILIKE '%assembl%')) OR
                (UPPER(:denomination) = UPPER('Presbyterian') AND c.denomination ILIKE '%presbyter%') OR
                (UPPER(:denomination) = UPPER('Seventh-day Adventist') AND (c.denomination ILIKE '%advent%' OR UPPER(c.denomination) = 'SDA')) OR
                (UPPER(:denomination) = UPPER('Other') AND c.denomination IS NOT NULL AND c.denomination <> '' AND NOT (
                  c.denomination ILIKE '%bapt%' OR c.denomination ILIKE '%sbc%' OR c.denomination ILIKE '%nbc%' OR
                  c.denomination ILIKE '%cath%' OR c.denomination ILIKE '%coc%' OR (c.denomination ILIKE '%church%of christ%' AND c.denomination NOT ILIKE '%united church of christ%') OR c.denomination ILIKE '%orthodox%' OR
                  c.denomination ILIKE '%episcop%' OR c.denomination ILIKE '%anglic%' OR c.denomination ILIKE '%evangel%' OR
                  c.denomination ILIKE '%latter-day%' OR c.denomination ILIKE '%lds%' OR c.denomination ILIKE '%mormon%' OR
                  c.denomination ILIKE '%luther%' OR c.denomination ILIKE '%method%' OR
                  c.denomination ILIKE '%non-denom%' OR c.denomination ILIKE '%nondenom%' OR
                  c.denomination ILIKE '%pentecost%' OR c.denomination ILIKE '%assembl%' OR c.denomination ILIKE '%presbyter%' OR
                  c.denomination ILIKE '%advent%' OR UPPER(c.denomination) = 'SDA'
                )))
              AND (:state = '' OR UPPER(c.state) = UPPER(:state))
              AND (:city = '' OR UPPER(c.city) = UPPER(:city))
            ORDER BY
              CASE WHEN :query = '' THEN 0
                   ELSE GREATEST(similarity(c.name, :query), similarity(c.city, :query)) END DESC,
              CASE WHEN :query = '' AND :lat IS NOT NULL AND :lng IS NOT NULL
                   THEN c.geo_point <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography END ASC,
              CASE WHEN :query = '' AND :lat IS NULL THEN md5(:seed || c.id::text) END ASC,
              c.name ASC
            """,
        countQuery = """
            SELECT count(*) FROM churches c
            WHERE (:query = '' OR NOT EXISTS (
                SELECT 1 FROM unnest(regexp_split_to_array(trim(:query), '\\s+')) AS q(word)
                WHERE word_similarity(q.word, c.name) < 0.5
                  AND word_similarity(q.word, COALESCE(c.city, '')) < 0.5
              ))
              AND (:denomination = '' OR
                (UPPER(:denomination) = UPPER('Baptist') AND (c.denomination ILIKE '%bapt%' OR c.denomination ILIKE '%sbc%' OR c.denomination ILIKE '%nbc%')) OR
                (UPPER(:denomination) = UPPER('Catholic') AND c.denomination ILIKE '%cath%') OR
                (UPPER(:denomination) = UPPER('Church of Christ') AND (c.denomination ILIKE '%coc%' OR (c.denomination ILIKE '%church%of christ%' AND c.denomination NOT ILIKE '%united church of christ%'))) OR
                (UPPER(:denomination) = UPPER('Eastern Orthodox') AND c.denomination ILIKE '%orthodox%') OR
                (UPPER(:denomination) = UPPER('Episcopal/Anglican') AND (c.denomination ILIKE '%episcop%' OR c.denomination ILIKE '%anglic%')) OR
                (UPPER(:denomination) = UPPER('Evangelical') AND c.denomination ILIKE '%evangel%') OR
                (UPPER(:denomination) = UPPER('Latter-day Saint (LDS)') AND (c.denomination ILIKE '%latter-day%' OR c.denomination ILIKE '%lds%' OR c.denomination ILIKE '%mormon%')) OR
                (UPPER(:denomination) = UPPER('Lutheran') AND c.denomination ILIKE '%luther%') OR
                (UPPER(:denomination) = UPPER('Methodist') AND c.denomination ILIKE '%method%') OR
                (UPPER(:denomination) = UPPER('Non-denominational') AND (c.denomination ILIKE '%non-denom%' OR c.denomination ILIKE '%nondenom%')) OR
                (UPPER(:denomination) = UPPER('Pentecostal / Assembly of God') AND (c.denomination ILIKE '%pentecost%' OR c.denomination ILIKE '%assembl%')) OR
                (UPPER(:denomination) = UPPER('Presbyterian') AND c.denomination ILIKE '%presbyter%') OR
                (UPPER(:denomination) = UPPER('Seventh-day Adventist') AND (c.denomination ILIKE '%advent%' OR UPPER(c.denomination) = 'SDA')) OR
                (UPPER(:denomination) = UPPER('Other') AND c.denomination IS NOT NULL AND c.denomination <> '' AND NOT (
                  c.denomination ILIKE '%bapt%' OR c.denomination ILIKE '%sbc%' OR c.denomination ILIKE '%nbc%' OR
                  c.denomination ILIKE '%cath%' OR c.denomination ILIKE '%coc%' OR (c.denomination ILIKE '%church%of christ%' AND c.denomination NOT ILIKE '%united church of christ%') OR c.denomination ILIKE '%orthodox%' OR
                  c.denomination ILIKE '%episcop%' OR c.denomination ILIKE '%anglic%' OR c.denomination ILIKE '%evangel%' OR
                  c.denomination ILIKE '%latter-day%' OR c.denomination ILIKE '%lds%' OR c.denomination ILIKE '%mormon%' OR
                  c.denomination ILIKE '%luther%' OR c.denomination ILIKE '%method%' OR
                  c.denomination ILIKE '%non-denom%' OR c.denomination ILIKE '%nondenom%' OR
                  c.denomination ILIKE '%pentecost%' OR c.denomination ILIKE '%assembl%' OR c.denomination ILIKE '%presbyter%' OR
                  c.denomination ILIKE '%advent%' OR UPPER(c.denomination) = 'SDA'
                )))
              AND (:state = '' OR UPPER(c.state) = UPPER(:state))
              AND (:city = '' OR UPPER(c.city) = UPPER(:city))
            """,
        nativeQuery = true)
    Page<Church> search(
        @Param("query") String query,
        @Param("denomination") String denomination,
        @Param("state") String state,
        @Param("city") String city,
        @Param("seed") String seed,
        @Param("lat") Double lat,
        @Param("lng") Double lng,
        Pageable pageable
    );

    // Distinct-value typeahead for the city/state filter inputs — matched against the
    // same UPPER(column) expression as the V8 functional indexes (idx_churches_city_upper
    // etc.), via a left-anchored LIKE rather than ILIKE '%...%', so prefix lookups stay
    // index-friendly instead of falling back to a seq scan.
    //
    // DISTINCT ON (UPPER(...)) rather than plain DISTINCT: the data has inconsistent
    // casing across import batches ("CHICAGO" bulk-imported, "Chicago" from a newer
    // manual entry) that are the same city for filtering purposes (search already
    // compares UPPER(city) = UPPER(:city)) but would otherwise show as two separate
    // suggestions for the same place.
    @Query(
        value = """
            SELECT DISTINCT ON (UPPER(c.city)) c.city FROM churches c
            WHERE c.city IS NOT NULL AND c.city <> ''
              AND (:prefix = '' OR UPPER(c.city) LIKE UPPER(:prefix) || '%')
              AND (:state = '' OR UPPER(c.state) = UPPER(:state))
            ORDER BY UPPER(c.city), c.city
            LIMIT 8
            """,
        nativeQuery = true)
    java.util.List<String> suggestCities(@Param("prefix") String prefix, @Param("state") String state);

    @Query(
        value = """
            SELECT DISTINCT ON (UPPER(c.state)) c.state FROM churches c
            WHERE c.state IS NOT NULL AND c.state <> ''
              AND (:prefix = '' OR UPPER(c.state) LIKE UPPER(:prefix) || '%')
            ORDER BY UPPER(c.state), c.state
            LIMIT 8
            """,
        nativeQuery = true)
    java.util.List<String> suggestStates(@Param("prefix") String prefix);

    // geo_point isn't JPA-mapped (see Church/ChurchService) — kept in sync with
    // latitude/longitude via this native UPDATE, run right after every save.
    // NameCase backfill paging (ChurchService.normalizeCase): keyset by id, so each batch
    // is an index range scan wherever the caller resumes.
    java.util.List<Church> findByIdGreaterThanOrderByIdAsc(Long id, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query(value = "UPDATE churches SET geo_point = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography WHERE id = :id", nativeQuery = true)
    void updateGeoPoint(@Param("id") Long id, @Param("lat") double lat, @Param("lng") double lng);

    // PostGIS KNN (`<->`) ordering against the GiST index on geo_point — this is what
    // makes "nearest church" fast at 250k+ rows instead of an in-memory Haversine sort
    // over every geocoded church. radiusMeters null means "N closest regardless of
    // distance" (today's behavior); set, it also excludes anything farther than that.
    // Same reasoning as search() above re: not selecting geo_point itself, just
    // ordering/filtering by it.
    @Query(
        value = """
            SELECT id, name, denomination, address, city, state, zip_code,
                   phone, email, website, description, image_url, pastor,
                   facebook, instagram, youtube, claimed, latitude, longitude,
                   service_summary
            FROM churches
            WHERE geo_point IS NOT NULL
              AND (:radiusMeters IS NULL OR ST_DWithin(geo_point, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters))
            ORDER BY geo_point <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
            LIMIT :limit
            """,
        nativeQuery = true)
    java.util.List<Church> findNearest(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") Double radiusMeters,
        @Param("limit") int limit
    );

    // Same pg_trgm `%` similarity operator as search() above. Matches on name similarity
    // AND (same city OR same zip) — a hint for a human reviewer, not an exact-match dedup
    // key, so it's deliberately looser than the scraper's own name+zip pre-filter
    // (apps/scraper/scraper/dedup.py). excludeId lets a church being edited skip itself.
    @Query(
        value = """
            SELECT c.id, c.name, c.denomination, c.address, c.city, c.state, c.zip_code,
                   c.phone, c.email, c.website, c.description, c.image_url, c.pastor,
                   c.facebook, c.instagram, c.youtube, c.claimed, c.latitude, c.longitude,
                   c.service_summary
            FROM churches c
            WHERE c.name % :name
              AND (:excludeId IS NULL OR c.id <> :excludeId)
              AND ((:city <> '' AND UPPER(c.city) = UPPER(:city)) OR (:zipCode <> '' AND c.zip_code = :zipCode))
            ORDER BY similarity(c.name, :name) DESC
            LIMIT 5
            """,
        nativeQuery = true)
    java.util.List<Church> findPossibleDuplicates(
        @Param("name") String name,
        @Param("city") String city,
        @Param("zipCode") String zipCode,
        @Param("excludeId") Long excludeId
    );

    // Favorites-page suggestions (FavoriteService.suggestions): churches sharing a
    // denomination with ones the user has favorited/recently viewed, excluding what
    // they've already favorited. ORDER BY random() is an unindexed sort, but this runs
    // over an already denomination-filtered slice (not all 250k+ rows) for an
    // infrequent, non-hot-path call — acceptable; revisit if that stops being true.
    @Query(
        value = """
            SELECT c.id, c.name, c.denomination, c.address, c.city, c.state, c.zip_code,
                   c.phone, c.email, c.website, c.description, c.image_url, c.pastor,
                   c.facebook, c.instagram, c.youtube, c.claimed, c.latitude, c.longitude,
                   c.service_summary
            FROM churches c
            WHERE c.denomination IN (:denominations)
              AND NOT EXISTS (SELECT 1 FROM favorites f WHERE f.church_id = c.id AND f.user_id = :userId)
            ORDER BY random()
            LIMIT :limit
            """,
        nativeQuery = true)
    java.util.List<Church> findSuggestionsByDenomination(
        @Param("denominations") java.util.List<String> denominations,
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    // Fallback when there's no denomination signal yet (a brand new user, or one who's
    // favorited/viewed nothing with a denomination set) — or to top up the list above
    // if it came up short. excludeIds must never be empty (FavoriteService always adds
    // a -1 sentinel if it would be) — an empty `NOT IN ()` is invalid SQL.
    @Query(
        value = """
            SELECT c.id, c.name, c.denomination, c.address, c.city, c.state, c.zip_code,
                   c.phone, c.email, c.website, c.description, c.image_url, c.pastor,
                   c.facebook, c.instagram, c.youtube, c.claimed, c.latitude, c.longitude,
                   c.service_summary
            FROM churches c
            WHERE NOT EXISTS (SELECT 1 FROM favorites f WHERE f.church_id = c.id AND f.user_id = :userId)
              AND c.id NOT IN (:excludeIds)
            ORDER BY random()
            LIMIT :limit
            """,
        nativeQuery = true)
    java.util.List<Church> findRandomSuggestions(
        @Param("userId") Long userId,
        @Param("excludeIds") java.util.List<Long> excludeIds,
        @Param("limit") int limit
    );
}
