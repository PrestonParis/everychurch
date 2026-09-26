package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.FavoriteStatusDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.Favorite;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchViewRepository;
import com.everychurch.server.repository.FavoriteRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

@Service
@Transactional
public class FavoriteService {

    /** How many suggestions the Favorites page's empty state shows. */
    public static final int SUGGESTION_LIMIT = 6;
    /** How many of the user's most recent views count as "signal" for the
     *  denomination-matching suggestion query — deliberately small: this is meant to
     *  reflect current interest, not their whole browsing history. */
    private static final int VIEW_SIGNAL_LIMIT = 10;

    private final FavoriteRepository favoriteRepository;
    private final ChurchRepository churchRepository;
    private final ChurchViewRepository churchViewRepository;
    private final ChurchService churchService;

    public FavoriteService(
        FavoriteRepository favoriteRepository,
        ChurchRepository churchRepository,
        ChurchViewRepository churchViewRepository,
        ChurchService churchService
    ) {
        this.favoriteRepository = favoriteRepository;
        this.churchRepository = churchRepository;
        this.churchViewRepository = churchViewRepository;
        this.churchService = churchService;
    }

    public void favorite(Long churchId, User user) {
        if (favoriteRepository.existsByChurchIdAndUserId(churchId, user.getId())) {
            throw new IllegalStateException("Already favorited");
        }
        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("No such church"));

        Favorite favorite = new Favorite();
        favorite.setChurch(church);
        favorite.setUser(user);
        favoriteRepository.save(favorite);
    }

    public void unfavorite(Long churchId, User user) {
        Favorite favorite = favoriteRepository.findByChurchIdAndUserId(churchId, user.getId())
            .orElseThrow(() -> new NoSuchElementException("Not favorited"));
        favoriteRepository.delete(favorite);
    }

    @Transactional(readOnly = true)
    public FavoriteStatusDto status(Long churchId, User user) {
        return new FavoriteStatusDto(favoriteRepository.existsByChurchIdAndUserId(churchId, user.getId()));
    }

    @Transactional(readOnly = true)
    public List<ChurchDto> listMyFavorites(User user) {
        return favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .map(f -> churchService.summaryOf(f.getChurch()))
            .toList();
    }

    /**
     * Up to {@link #SUGGESTION_LIMIT} churches the user hasn't favorited yet, for the
     * Favorites page's empty (or "want more?") state. First tries to match the
     * denomination of churches they've already favorited or recently viewed — actual
     * signal beats a cold-start guess — then tops up with a random sample if that
     * didn't fill the list (including for a brand new user with no signal at all).
     */
    @Transactional(readOnly = true)
    public List<ChurchDto> suggestions(User user) {
        List<String> denominations = signalDenominations(user);

        List<Church> results = new ArrayList<>();
        if (!denominations.isEmpty()) {
            results.addAll(churchRepository.findSuggestionsByDenomination(denominations, user.getId(), SUGGESTION_LIMIT));
        }

        if (results.size() < SUGGESTION_LIMIT) {
            List<Long> alreadyPicked = results.stream().map(Church::getId).toList();
            List<Long> excludeIds = alreadyPicked.isEmpty() ? List.of(-1L) : alreadyPicked;
            int remaining = SUGGESTION_LIMIT - results.size();
            results.addAll(churchRepository.findRandomSuggestions(user.getId(), excludeIds, remaining));
        }

        return results.stream().map(churchService::summaryOf).toList();
    }

    /** Denominations from the user's own signal, most-favorited-first then
     *  most-recently-viewed — a LinkedHashSet so a denomination appearing in both
     *  only contributes once, keeping its earliest (strongest) position. */
    private List<String> signalDenominations(User user) {
        Set<String> denominations = new LinkedHashSet<>();
        favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .map(f -> f.getChurch().getDenomination())
            .filter(Objects::nonNull)
            .forEach(denominations::add);
        churchViewRepository.findByUserIdOrderByViewedAtDesc(user.getId(), PageRequest.of(0, VIEW_SIGNAL_LIMIT)).stream()
            .map(v -> v.getChurch().getDenomination())
            .filter(Objects::nonNull)
            .forEach(denominations::add);
        return new ArrayList<>(denominations);
    }
}
