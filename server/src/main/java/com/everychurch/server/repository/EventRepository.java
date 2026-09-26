package com.everychurch.server.repository;

import com.everychurch.server.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    /** Events with ANY co-host row for this church, regardless of status — matches
     *  the old findByChurchesId's behavior (?churchId= on GET /api/events). A PENDING
     *  or DECLINED co-host still shows up here; EventDto itself only ever surfaces
     *  DECLINED via nothing (it's dropped) and PENDING only to people who can see it. */
    @Query("SELECT DISTINCT e FROM Event e JOIN e.coHosts c WHERE c.church.id = :churchId")
    List<Event> findByChurchId(@Param("churchId") Long churchId);
}
