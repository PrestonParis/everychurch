package com.everychurch.server.service;

import com.everychurch.server.dto.ConversationSummaryDto;
import com.everychurch.server.dto.DirectMessageDto;
import com.everychurch.server.model.DirectMessage;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.DirectMessageRepository;
import com.everychurch.server.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 1:1 messaging between any two users — no shared church required, unlike the group
 * chat (ChatService/MemberService.canAccessGroup). Both entry points — the STOMP
 * handler (DirectMessageController) and the REST history/conversation-list endpoints —
 * go through here.
 */
@Service
@Transactional
public class DirectMessageService {

    public static final int MAX_BODY_LENGTH = 2000;
    public static final int DEFAULT_HISTORY_LIMIT = 50;
    public static final int MAX_HISTORY_LIMIT = 100;

    private final DirectMessageRepository directMessageRepository;
    private final UserRepository userRepository;
    private final BlockService blockService;

    public DirectMessageService(DirectMessageRepository directMessageRepository, UserRepository userRepository, BlockService blockService) {
        this.directMessageRepository = directMessageRepository;
        this.userRepository = userRepository;
        this.blockService = blockService;
    }

    public DirectMessageDto send(User sender, Long recipientId, String body) {
        if (recipientId == null || recipientId.equals(sender.getId())) {
            throw new IllegalArgumentException("Can't send a direct message to yourself");
        }
        User recipient = userRepository.findById(recipientId)
            .orElseThrow(() -> new NoSuchElementException("No such user"));
        requireNotBlocked(sender.getId(), recipientId);

        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A message can't be empty");
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("A message can be at most " + MAX_BODY_LENGTH + " characters");
        }

        DirectMessage message = new DirectMessage();
        message.setSender(sender);
        message.setRecipient(recipient);
        message.setBody(trimmed);
        return toDto(directMessageRepository.save(message));
    }

    /**
     * A page of history with the other user, oldest first within the page (same
     * cursor convention as ChatService.history: pass the id of the oldest message
     * already loaded to get the page before it). Also marks everything the other
     * person sent as read, same as opening a conversation would in any DM UI.
     */
    public List<DirectMessageDto> history(User requester, Long otherUserId, Long beforeId, Integer limit) {
        if (!userRepository.existsById(otherUserId)) {
            throw new NoSuchElementException("No such user");
        }

        int pageSize = limit == null ? DEFAULT_HISTORY_LIMIT : Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        var page = PageRequest.of(0, pageSize);

        List<DirectMessage> newestFirst = beforeId == null
            ? directMessageRepository.findConversationNewestFirst(requester.getId(), otherUserId, page)
            : directMessageRepository.findConversationBeforeNewestFirst(requester.getId(), otherUserId, beforeId, page);

        directMessageRepository.markConversationRead(requester.getId(), otherUserId);

        List<DirectMessageDto> result = new ArrayList<>(newestFirst.stream().map(this::toDto).toList());
        Collections.reverse(result);
        return result;
    }

    /** One row per person the requester has ever DMed, newest activity first. Not a
     *  single query — see the repository's own comment — but a conversation count
     *  per user is small enough that this stays fast; revisit with a window-function
     *  query if it ever shows up in profiling. */
    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> listConversations(User requester) {
        List<Long> partnerIds = directMessageRepository.findConversationPartnerIds(requester.getId());

        List<ConversationSummaryDto> summaries = new ArrayList<>();
        for (Long partnerId : partnerIds) {
            List<DirectMessage> latest = directMessageRepository
                .findConversationNewestFirst(requester.getId(), partnerId, PageRequest.of(0, 1));
            if (latest.isEmpty()) continue; // shouldn't happen — partnerId came from this exact pair
            DirectMessage last = latest.get(0);
            long unread = directMessageRepository.countByRecipientIdAndSenderIdAndReadAtIsNull(requester.getId(), partnerId);

            summaries.add(new ConversationSummaryDto(
                partnerId,
                last.getSender().getId().equals(partnerId) ? last.getSender().getDisplayName() : last.getRecipient().getDisplayName(),
                last.getBody(),
                last.getSentAt(),
                last.getSender().getId().equals(requester.getId()),
                unread
            ));
        }

        summaries.sort(Comparator.comparing(ConversationSummaryDto::lastMessageSentAt).reversed());
        return summaries;
    }

    /** The one topic a given pair of users' DMs ever broadcast to, regardless of who's
     *  sending — lower id first, so both StompAuthChannelInterceptor and
     *  DirectMessageController agree on the same string without either re-deriving
     *  the ordering rule. */
    public String topicFor(long userIdA, long userIdB) {
        long lo = Math.min(userIdA, userIdB);
        long hi = Math.max(userIdA, userIdB);
        return "/topic/direct-messages/" + lo + "-" + hi;
    }

    /** Blocked in EITHER direction stops the send — not just the sender blocking the
     *  recipient. Deliberately the same generic message regardless of which direction
     *  it is: revealing "they blocked you" specifically (vs. "you blocked them") is a
     *  privacy leak neither side needs, and the caller can't act on the distinction
     *  differently anyway. */
    private void requireNotBlocked(Long userA, Long userB) {
        if (blockService.isBlockedEitherDirection(userA, userB)) {
            throw new IllegalStateException("You can't send messages to this person");
        }
    }

    private DirectMessageDto toDto(DirectMessage message) {
        return new DirectMessageDto(
            message.getId(), message.getSender().getId(), message.getRecipient().getId(),
            message.getSender().getDisplayName(), message.getBody(), message.getSentAt());
    }
}
