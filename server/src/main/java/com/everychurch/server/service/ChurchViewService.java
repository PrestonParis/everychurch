package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchView;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchViewRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class ChurchViewService {

    public static final int DEFAULT_RECENT_LIMIT = 8;

    private final ChurchViewRepository churchViewRepository;
    private final ChurchRepository churchRepository;
    private final ChurchService churchService;

    public ChurchViewService(ChurchViewRepository churchViewRepository, ChurchRepository churchRepository, ChurchService churchService) {
        this.churchViewRepository = churchViewRepository;
        this.churchRepository = churchRepository;
        this.churchService = churchService;
    }

    /** Called once per profile-page load (ChurchProfileComponent). One row per
     *  (church, user) — a repeat visit just bumps viewedAt rather than growing an
     *  unbounded log. */
    public void recordView(Long churchId, User user) {
        ChurchView view = churchViewRepository.findByChurchIdAndUserId(churchId, user.getId())
            .orElseGet(() -> {
                Church church = churchRepository.findById(churchId)
                    .orElseThrow(() -> new NoSuchElementException("No such church"));
                ChurchView v = new ChurchView();
                v.setChurch(church);
                v.setUser(user);
                return v;
            });
        view.setViewedAt(Instant.now());
        churchViewRepository.save(view);
    }

    @Transactional(readOnly = true)
    public List<ChurchDto> recentlyViewed(User user, int limit) {
        return churchViewRepository.findByUserIdOrderByViewedAtDesc(user.getId(), PageRequest.of(0, limit)).stream()
            .map(v -> churchService.summaryOf(v.getChurch()))
            .toList();
    }
}
