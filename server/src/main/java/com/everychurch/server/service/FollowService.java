package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.FollowStatusDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.Follow;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.FollowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/** The "church social media" follow relationship — see Follow's class comment for how
 *  this differs from Favorite and Member. */
@Service
@Transactional
public class FollowService {

    private final FollowRepository followRepository;
    private final ChurchRepository churchRepository;
    private final ChurchService churchService;

    public FollowService(FollowRepository followRepository, ChurchRepository churchRepository, ChurchService churchService) {
        this.followRepository = followRepository;
        this.churchRepository = churchRepository;
        this.churchService = churchService;
    }

    public void follow(Long churchId, User user) {
        if (followRepository.existsByChurchIdAndUserId(churchId, user.getId())) {
            throw new IllegalStateException("Already following");
        }
        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("No such church"));

        Follow follow = new Follow();
        follow.setChurch(church);
        follow.setUser(user);
        followRepository.save(follow);
    }

    public void unfollow(Long churchId, User user) {
        Follow follow = followRepository.findByChurchIdAndUserId(churchId, user.getId())
            .orElseThrow(() -> new NoSuchElementException("Not following"));
        followRepository.delete(follow);
    }

    @Transactional(readOnly = true)
    public FollowStatusDto status(Long churchId, User user) {
        boolean following = user != null && followRepository.existsByChurchIdAndUserId(churchId, user.getId());
        return new FollowStatusDto(following, followRepository.countByChurchId(churchId));
    }

    @Transactional(readOnly = true)
    public List<ChurchDto> listMyFollowing(User user) {
        return followRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .map(f -> churchService.summaryOf(f.getChurch()))
            .toList();
    }
}
