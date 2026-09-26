package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchStaffDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchPermission;
import com.everychurch.server.model.ChurchStaff;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchStaffRepository;
import com.everychurch.server.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class ChurchStaffService {

    private final ChurchStaffRepository churchStaffRepository;
    private final ChurchRepository churchRepository;
    private final UserRepository userRepository;

    public ChurchStaffService(
        ChurchStaffRepository churchStaffRepository,
        ChurchRepository churchRepository,
        UserRepository userRepository
    ) {
        this.churchStaffRepository = churchStaffRepository;
        this.churchRepository = churchRepository;
        this.userRepository = userRepository;
    }

    /** Called on claim approval — grants full ownership, bypassing the normal add-staff authorization checks. */
    public ChurchStaff grantOwnership(Church church, User owner) {
        ChurchStaff staff = new ChurchStaff();
        staff.setChurch(church);
        staff.setUser(owner);
        staff.setOwner(true);
        staff.setPermissions(Set.of(ChurchPermission.values()));
        return churchStaffRepository.save(staff);
    }

    public List<ChurchStaffDto> listStaff(Long churchId, User requester) {
        requireCanManage(churchId, requester);
        return churchStaffRepository.findByChurchId(churchId).stream().map(this::toDto).toList();
    }

    /** Self-service, no permission check needed — a user can always see their own
     *  memberships. Powers client-side checks like "does this person have anything to
     *  manage events for" (EventListComponent/EventDetailComponent) without offering a
     *  New Event/Edit/Delete button that a plain Member could never actually use. */
    public List<ChurchStaffDto> listMyStaffChurches(User requester) {
        return churchStaffRepository.findByUserId(requester.getId()).stream().map(this::toDto).toList();
    }

    public ChurchStaffDto addStaff(Long churchId, User requester, String email, Set<ChurchPermission> permissions) {
        requireCanManage(churchId, requester);

        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("No such church"));
        User target = userRepository.findByEmail(email)
            .orElseThrow(() -> new NoSuchElementException("No user with that email"));

        if (churchStaffRepository.existsByChurchIdAndUserId(churchId, target.getId())) {
            throw new IllegalStateException("That user is already staff for this church");
        }

        ChurchStaff staff = new ChurchStaff();
        staff.setChurch(church);
        staff.setUser(target);
        staff.setOwner(false);
        staff.setPermissions(permissions == null ? Set.of() : permissions);
        return toDto(churchStaffRepository.save(staff));
    }

    public ChurchStaffDto updatePermissions(Long churchId, User requester, Long staffId, Set<ChurchPermission> permissions) {
        requireCanManage(churchId, requester);
        ChurchStaff staff = findStaffInChurch(churchId, staffId);

        if (staff.isOwner()) {
            throw new IllegalStateException("The owner always has full permissions and can't be changed here");
        }

        staff.setPermissions(permissions == null ? Set.of() : permissions);
        return toDto(churchStaffRepository.save(staff));
    }

    public void removeStaff(Long churchId, User requester, Long staffId) {
        requireCanManage(churchId, requester);
        ChurchStaff staff = findStaffInChurch(churchId, staffId);

        if (staff.isOwner()) {
            throw new IllegalStateException("Cannot remove the church owner");
        }

        churchStaffRepository.delete(staff);
    }

    private ChurchStaff findStaffInChurch(Long churchId, Long staffId) {
        ChurchStaff staff = churchStaffRepository.findById(staffId)
            .orElseThrow(() -> new NoSuchElementException("No such staff member"));
        if (!staff.getChurch().getId().equals(churchId)) {
            throw new NoSuchElementException("No such staff member for this church");
        }
        return staff;
    }

    /** Platform Admins bypass per-church checks; everyone else needs MANAGE_STAFF (owners have every permission implicitly). */
    private void requireCanManage(Long churchId, User requester) {
        if (requester.getRole() == Role.PLATFORM_ADMIN) return;

        boolean canManage = churchStaffRepository.findByChurchIdAndUserId(churchId, requester.getId())
            .map(staff -> staff.hasPermission(ChurchPermission.MANAGE_STAFF))
            .orElse(false);

        if (!canManage) {
            throw new AccessDeniedException("You don't have permission to manage staff for this church");
        }
    }

    private ChurchStaffDto toDto(ChurchStaff staff) {
        return new ChurchStaffDto(staff.getId(), staff.getChurch().getId(), staff.getUser().getEmail(), staff.isOwner(), staff.getPermissions());
    }
}
