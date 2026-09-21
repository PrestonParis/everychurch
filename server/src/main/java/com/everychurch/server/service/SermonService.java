package com.everychurch.server.service;

import com.everychurch.server.dto.PresignedUploadResponse;
import com.everychurch.server.dto.SermonDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchPermission;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.Sermon;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchStaffRepository;
import com.everychurch.server.repository.SermonRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SermonService {

    // Longer than ChurchImageService's 5 minutes — audio/video files are bigger and
    // slower to upload than images (Prompt 9).
    private static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(15);

    private final SermonRepository sermonRepository;
    private final ChurchRepository churchRepository;
    private final ChurchStaffRepository churchStaffRepository;
    private final S3PresignService s3PresignService;

    public SermonService(
        SermonRepository sermonRepository,
        ChurchRepository churchRepository,
        ChurchStaffRepository churchStaffRepository,
        S3PresignService s3PresignService
    ) {
        this.sermonRepository = sermonRepository;
        this.churchRepository = churchRepository;
        this.churchStaffRepository = churchStaffRepository;
        this.s3PresignService = s3PresignService;
    }

    public List<SermonDto> list(Long churchId) {
        return sermonRepository.findByChurchIdOrderByDateDesc(churchId).stream().map(this::toDto).toList();
    }

    /** Presigned S3 PUT URL for a new sermon file — same pattern as ChurchImageService.getUploadUrl. */
    public PresignedUploadResponse getUploadUrl(Long churchId, User requester, String contentType) {
        requireCanManage(churchId, requester);
        return s3PresignService.presignPut("church-sermons/" + churchId, contentType, UPLOAD_URL_EXPIRY);
    }

    public SermonDto add(Long churchId, User requester, String title, LocalDate date, String mediaUrl) {
        requireCanManage(churchId, requester);

        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("Church not found: " + churchId));

        Sermon sermon = new Sermon();
        sermon.setChurch(church);
        sermon.setTitle(title);
        sermon.setDate(date);
        sermon.setMediaUrl(mediaUrl);
        return toDto(sermonRepository.save(sermon));
    }

    public SermonDto update(Long churchId, User requester, Long sermonId, String title, LocalDate date, String mediaUrl) {
        requireCanManage(churchId, requester);
        Sermon sermon = findInChurch(churchId, sermonId);
        sermon.setTitle(title);
        sermon.setDate(date);
        sermon.setMediaUrl(mediaUrl);
        return toDto(sermonRepository.save(sermon));
    }

    public void remove(Long churchId, User requester, Long sermonId) {
        requireCanManage(churchId, requester);
        sermonRepository.delete(findInChurch(churchId, sermonId));
    }

    private Sermon findInChurch(Long churchId, Long sermonId) {
        Sermon sermon = sermonRepository.findById(sermonId)
            .orElseThrow(() -> new NoSuchElementException("No such sermon"));
        if (!sermon.getChurch().getId().equals(churchId)) {
            throw new NoSuchElementException("No such sermon for this church");
        }
        return sermon;
    }

    private void requireCanManage(Long churchId, User requester) {
        if (requester.getRole() == Role.PLATFORM_ADMIN) return;

        boolean canManage = churchStaffRepository.findByChurchIdAndUserId(churchId, requester.getId())
            .map(staff -> staff.hasPermission(ChurchPermission.MANAGE_SERMONS))
            .orElse(false);

        if (!canManage) {
            throw new AccessDeniedException("You don't have permission to manage sermons for this church");
        }
    }

    private SermonDto toDto(Sermon sermon) {
        return new SermonDto(sermon.getId(), sermon.getChurch().getId(), sermon.getTitle(), sermon.getDate(), sermon.getMediaUrl());
    }
}
