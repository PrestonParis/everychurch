package com.everychurch.server.repository;

import com.everychurch.server.model.DirectMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, Long> {

    /** Every other user the given user has ever exchanged a DM with, in either
     *  direction — the seed for the conversation list (DirectMessageService). */
    @Query("""
        SELECT DISTINCT CASE WHEN m.sender.id = :userId THEN m.recipient.id ELSE m.sender.id END
        FROM DirectMessage m
        WHERE m.sender.id = :userId OR m.recipient.id = :userId
        """)
    List<Long> findConversationPartnerIds(@Param("userId") Long userId);

    /** Newest first, either direction between the two users. */
    @Query("""
        SELECT m FROM DirectMessage m
        WHERE (m.sender.id = :userA AND m.recipient.id = :userB)
           OR (m.sender.id = :userB AND m.recipient.id = :userA)
        ORDER BY m.id DESC
        """)
    List<DirectMessage> findConversationNewestFirst(@Param("userA") Long userA, @Param("userB") Long userB, Pageable pageable);

    @Query("""
        SELECT m FROM DirectMessage m
        WHERE ((m.sender.id = :userA AND m.recipient.id = :userB)
            OR (m.sender.id = :userB AND m.recipient.id = :userA))
          AND m.id < :beforeId
        ORDER BY m.id DESC
        """)
    List<DirectMessage> findConversationBeforeNewestFirst(
        @Param("userA") Long userA, @Param("userB") Long userB, @Param("beforeId") Long beforeId, Pageable pageable);

    long countByRecipientIdAndSenderIdAndReadAtIsNull(Long recipientId, Long senderId);

    /** Called when the recipient opens a conversation (DirectMessageService.history) —
     *  clears the unread badge for everything the other person sent up to that point. */
    @Modifying
    @Query("UPDATE DirectMessage m SET m.readAt = CURRENT_TIMESTAMP WHERE m.recipient.id = :recipientId AND m.sender.id = :senderId AND m.readAt IS NULL")
    void markConversationRead(@Param("recipientId") Long recipientId, @Param("senderId") Long senderId);
}
