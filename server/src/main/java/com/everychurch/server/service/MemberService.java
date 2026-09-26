package com.everychurch.server.service;

import com.everychurch.server.dto.MemberDto;
import com.everychurch.server.dto.MembershipStatusDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.Member;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchStaffRepository;
import com.everychurch.server.repository.MemberRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class MemberService {

    private final MemberRepository memberRepository;
    private final ChurchRepository churchRepository;
    private final ChurchStaffRepository churchStaffRepository;

    public MemberService(
        MemberRepository memberRepository,
        ChurchRepository churchRepository,
        ChurchStaffRepository churchStaffRepository
    ) {
        this.memberRepository = memberRepository;
        this.churchRepository = churchRepository;
        this.churchStaffRepository = churchStaffRepository;
    }

    public MemberDto join(Long churchId, User user) {
        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("No such church"));

        if (memberRepository.existsByChurchIdAndUserId(churchId, user.getId())) {
            throw new IllegalStateException("You're already a member of this church");
        }

        Member member = new Member();
        member.setChurch(church);
        member.setUser(user);
        return toDto(memberRepository.save(member));
    }

    public void leave(Long churchId, User user) {
        Member member = memberRepository.findByChurchIdAndUserId(churchId, user.getId())
            .orElseThrow(() -> new NoSuchElementException("You're not a member of this church"));
        memberRepository.delete(member);
    }

    @Transactional(readOnly = true)
    public List<MemberDto> list(Long churchId, User requester) {
        if (!canAccessGroup(churchId, requester)) {
            throw new AccessDeniedException("Only members of this church can see its member list");
        }
        return memberRepository.findByChurchIdOrderByJoinedAtAsc(churchId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public MembershipStatusDto status(Long churchId, User user) {
        boolean member = memberRepository.existsByChurchIdAndUserId(churchId, user.getId());
        boolean staff = churchStaffRepository.existsByChurchIdAndUserId(churchId, user.getId());
        return new MembershipStatusDto(member, staff, member || staff || user.getRole() == Role.PLATFORM_ADMIN);
    }

    /**
     * The one access rule for everything congregation-private (the member list, and the
     * group chat in Prompt 14): a member of that church, any of its staff, or a Platform
     * Admin. Keeping it here means chat and the member list can never drift apart.
     */
    @Transactional(readOnly = true)
    public boolean canAccessGroup(Long churchId, User user) {
        return status(churchId, user).canChat();
    }

    private MemberDto toDto(Member member) {
        return new MemberDto(
            member.getId(), member.getChurch().getId(), member.getUser().getId(),
            member.getUser().getDisplayName(), member.getJoinedAt());
    }
}
