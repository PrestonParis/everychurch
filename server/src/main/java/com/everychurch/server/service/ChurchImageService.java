package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchImageDto;
import com.everychurch.server.dto.PresignedUploadResponse;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchImage;
import com.everychurch.server.model.ChurchPermission;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchImageRepository;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchStaffRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ChurchImageService {

    private static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(5);

    private final ChurchImageRepository churchImageRepository;
    private final ChurchRepository churchRepository;
    private final ChurchStaffRepository churchStaffRepository;
    private final S3PresignService s3PresignService;

    public ChurchImageService(
        ChurchImageRepository churchImageRepository,
        ChurchRepository churchRepository,
        ChurchStaffRepository churchStaffRepository,
        S3PresignService s3PresignService
    ) {
        this.churchImageRepository = churchImageRepository;
        this.churchRepository = churchRepository;
        this.churchStaffRepository = churchStaffRepository;
        this.s3PresignService = s3PresignService;
    }

    public List<ChurchImageDto> list(Long churchId) {
        return churchImageRepository.findByChurchIdOrderBySortOrderAsc(churchId).stream().map(this::toDto).toList();
    }

    /** Presigned S3 PUT URL for a new image — the client uploads directly to S3, then calls add() with the result. */
    public PresignedUploadResponse getUploadUrl(Long churchId, User requester, String contentType) {
        requireCanEdit(churchId, requester);
        return s3PresignService.presignPut("church-images/" + churchId, contentType, UPLOAD_URL_EXPIRY);
    }

    public ChurchImageDto add(Long churchId, User requester, String url, int sortOrder) {
        requireCanEdit(churchId, requester);

        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("Church not found: " + churchId));

        ChurchImage image = new ChurchImage();
        image.setChurch(church);
        image.setUrl(url);
        image.setSortOrder(sortOrder);
        return toDto(churchImageRepository.save(image));
    }

    public ChurchImageDto update(Long churchId, User requester, Long imageId, String url, int sortOrder) {
        requireCanEdit(churchId, requester);
        ChurchImage image = findInChurch(churchId, imageId);
        image.setUrl(url);
        image.setSortOrder(sortOrder);
        return toDto(churchImageRepository.save(image));
    }

    public void remove(Long churchId, User requester, Long imageId) {
        requireCanEdit(churchId, requester);
        churchImageRepository.delete(findInChurch(churchId, imageId));
    }

    private ChurchImage findInChurch(Long churchId, Long imageId) {
        ChurchImage image = churchImageRepository.findById(imageId)
            .orElseThrow(() -> new NoSuchElementException("No such image"));
        if (!image.getChurch().getId().equals(churchId)) {
            throw new NoSuchElementException("No such image for this church");
        }
        return image;
    }

    /** Images are part of the church profile, so EDIT_PROFILE (or ownership/Platform Admin) gates writes. */
    private void requireCanEdit(Long churchId, User requester) {
        if (requester.getRole() == Role.PLATFORM_ADMIN) return;

        boolean canEdit = churchStaffRepository.findByChurchIdAndUserId(churchId, requester.getId())
            .map(staff -> staff.hasPermission(ChurchPermission.EDIT_PROFILE))
            .orElse(false);

        if (!canEdit) {
            throw new AccessDeniedException("You don't have permission to edit this church's profile");
        }
    }

    private ChurchImageDto toDto(ChurchImage image) {
        return new ChurchImageDto(image.getId(), image.getChurch().getId(), image.getUrl(), image.getSortOrder());
    }
}
