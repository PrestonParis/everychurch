package com.everychurch.server.service;

import com.everychurch.server.dto.MessageDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.Message;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The rules for the per-church group chat. Both entry points — the STOMP handler that
 * sends live and the REST endpoint that loads history — go through here, so who may
 * read or write a church's chat is decided in exactly one place (MemberService.canAccessGroup).
 */
@Service
@Transactional
public class ChatService {

    public static final int MAX_BODY_LENGTH = 2000;
    public static final int DEFAULT_HISTORY_LIMIT = 50;
    public static final int MAX_HISTORY_LIMIT = 100;

    private final MessageRepository messageRepository;
    private final ChurchRepository churchRepository;
    private final MemberService memberService;

    public ChatService(MessageRepository messageRepository, ChurchRepository churchRepository, MemberService memberService) {
        this.messageRepository = messageRepository;
        this.churchRepository = churchRepository;
        this.memberService = memberService;
    }

    public MessageDto send(Long churchId, User sender, String body) {
        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("No such church"));
        requireAccess(churchId, sender);

        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A message can't be empty");
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("A message can be at most " + MAX_BODY_LENGTH + " characters");
        }

        Message message = new Message();
        message.setChurch(church);
        message.setSender(sender);
        message.setBody(trimmed);
        return toDto(messageRepository.save(message));
    }

    /**
     * A page of history, oldest first within the page. {@code beforeId} is a cursor: pass
     * the id of the oldest message already loaded to get the page before it (null = the
     * latest page).
     */
    @Transactional(readOnly = true)
    public List<MessageDto> history(Long churchId, User requester, Long beforeId, Integer limit) {
        requireAccess(churchId, requester);

        int pageSize = limit == null ? DEFAULT_HISTORY_LIMIT : Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        var page = PageRequest.of(0, pageSize);

        List<Message> newestFirst = beforeId == null
            ? messageRepository.findByChurchIdOrderByIdDesc(churchId, page)
            : messageRepository.findByChurchIdAndIdLessThanOrderByIdDesc(churchId, beforeId, page);

        List<MessageDto> result = new ArrayList<>(newestFirst.stream().map(this::toDto).toList());
        Collections.reverse(result);
        return result;
    }

    private void requireAccess(Long churchId, User user) {
        if (!memberService.canAccessGroup(churchId, user)) {
            throw new AccessDeniedException("Only members of this church can use its chat");
        }
    }

    private MessageDto toDto(Message message) {
        return new MessageDto(
            message.getId(), message.getChurch().getId(),
            message.getSender().getDisplayName(), message.getBody(), message.getSentAt());
    }
}
