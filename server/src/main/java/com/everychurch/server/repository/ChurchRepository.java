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

    // pg_trgm `%` similarity search on name/city, tunable via
    // pg_trgm.similarity_threshold (left at Postgres's default of 0.3 — no real data
    // volume yet to tune against; revisit once real churches are loaded). Blank
    // filters are passed as "" rather than null so a single bind-parameter set works
    // for both the data and count queries without null-handling differences across
    // drivers.
    // Column list spelled out (not SELECT *) rather than pulling in geo_point — an
    // exotic PostGIS type with no JPA mapping that's safest kept out of an entity
    // auto-mapped native query result set entirely, not just unused by the entity.
    @Query(
        value = """
            SELECT c.id, c.name, c.denomination, c.address, c.city, c.state, c.zip_code,
                   c.phone, c.email, c.website, c.description, c.image_url, c.pastor,
                   c.facebook, c.instagram, c.youtube, c.claimed, c.latitude, c.longitude
            FROM churches c
            WHERE (:query = '' OR c.name % :query OR c.city % :query)
              AND (:denomination = '' OR c.denomination = :denomination)
              AND (:state = '' OR c.state = :state)
              AND (:city = '' OR c.city = :city)
            ORDER BY
              CASE WHEN :query = '' THEN 0
                   ELSE GREATEST(similarity(c.name, :query), similarity(c.city, :query)) END DESC,
              c.name ASC
            """,
        countQuery = """
            SELECT count(*) FROM churches c
            WHERE (:query = '' OR c.name % :query OR c.city % :query)
              AND (:denomination = '' OR c.denomination = :denomination)
              AND (:state = '' OR c.state = :state)
              AND (:city = '' OR c.city = :city)
            """,
        nativeQuery = true)
    Page<Church> search(
        @Param("query") String query,
        @Param("denomination") String denomination,
        @Param("state") String state,
        @Param("city") String city,
        Pageable pageable
    );

    // geo_point isn't JPA-mapped (see Church/ChurchService) — kept in sync with
    // latitude/longitude via this native UPDATE, run right after every save.
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
                   facebook, instagram, youtube, claimed, latitude, longitude
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
}
