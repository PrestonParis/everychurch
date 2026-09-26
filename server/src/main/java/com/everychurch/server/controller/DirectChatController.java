package com.everychurch.server.controller;

import com.everychurch.server.dto.DirectMessageDto;
import com.everychurch.server.dto.DirectMessageRequest;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.DirectMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Live delivery for 1:1 direct messages over STOMP — same shape as ChatController's
 * group-chat handler, just keyed by a (sender, recipient) pair instead of a church.
 * A client SENDs to /app/direct-messages/{recipientId}/send; we persist it, then
 * broadcast to DirectMessageService.topicFor(sender, recipient), which both
 * participants (and only them — see StompAuthChannelInterceptor) are subscribed to.
 * History is a separate REST call (DirectMessageController).
 */
@Controller
public class DirectChatController {

    private static final Logger log = LoggerFactory.getLogger(DirectChatController.class);

    private final DirectMessageService directMessageService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public DirectChatController(DirectMessageService directMessageService, UserRepository userRepository, SimpMessagingTemplate messagingTemplate) {
        this.directMessageService = directMessageService;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/direct-messages/{recipientId}/send")
    public void send(@DestinationVariable Long recipientId, DirectMessageRequest request, Principal principal) {
        User sender = userRepository.findByEmail(principal.getName())
            .orElseThrow(() -> new IllegalStateException("Sender no longer exists"));

        DirectMessageDto saved = directMessageService.send(sender, recipientId, request == null ? null : request.body());
        String topic = directMessageService.topicFor(sender.getId(), recipientId);
        messagingTemplate.convertAndSend(topic, saved);
    }

    /** Same reasoning as ChatController's identical handler: STOMP has no per-message
     *  error response, so a rejected send (unknown recipient, empty/too-long body) is
     *  logged and dropped. The body is deliberately not logged — private messages. */
    @MessageExceptionHandler
    public void dropRejectedMessage(Exception e, Principal principal) {
        log.warn("Dropped a direct message from {}: {}", principal == null ? "unknown" : principal.getName(), e.getMessage());
    }
}
