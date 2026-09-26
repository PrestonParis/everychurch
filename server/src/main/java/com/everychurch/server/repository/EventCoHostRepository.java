package com.everychurch.server.repository;

import com.everychurch.server.model.EventCoHost;
import com.everychurch.server.model.EventCoHostStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventCoHostRepository extends JpaRepository<EventCoHost, Long> {

    Optional<EventCoHost> findByEventIdAndChurchId(Long eventId, Long churchId);

    /** Every PENDING invitation sitting against any church in `churchIds` — the
     *  "co-host invitations" list a church's own MANAGE_EVENTS staff sees (see
     *  UserController.coHostInvitations). */
    @Query("""
        SELECT c FROM EventCoHost c
        WHERE c.church.id IN :churchIds AND c.status = :status
        ORDER BY c.createdAt DESC
        """)
    @EntityGraph(attributePaths = {"event", "church"})
    List<EventCoHost> findByChurchIdInAndStatus(@Param("churchIds") List<Long> churchIds, @Param("status") EventCoHostStatus status);
}
