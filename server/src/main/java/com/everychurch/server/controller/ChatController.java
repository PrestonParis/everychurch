package com.everychurch.server.controller;

import com.everychurch.server.dto.ChatMessageRequest;
import com.everychurch.server.dto.MessageDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Live chat delivery over STOMP: a member SENDs to /app/churches/{id}/chat.send, we
 * persist it, then broadcast to everyone subscribed to /topic/churches/{id}/chat.
 * History is a separate REST call (MessageController).
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(ChatService chatService, UserRepository userRepository, SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/churches/{churchId}/chat.send")
    public void send(@DestinationVariable Long churchId, ChatMessageRequest request, Principal principal) {
        User sender = userRepository.findByEmail(principal.getName())
            .orElseThrow(() -> new IllegalStateException("Sender no longer exists"));

        // Persisted (and the transaction committed) before it's broadcast, so nobody
        // can receive a message that then isn't in the history.
        MessageDto saved = chatService.send(churchId, sender, request == null ? null : request.body());
        messagingTemplate.convertAndSend("/topic/churches/" + churchId + "/chat", saved);
    }

    /**
     * STOMP has no per-message equivalent of an HTTP error status, so a rejected message
     * (not a member, empty, too long) is logged and dropped rather than answered. The
     * message body is deliberately not logged — it's private congregation chat.
     */
    @MessageExceptionHandler
    public void dropRejectedMessage(Exception e, Principal principal) {
        log.warn("Dropped a chat message from {}: {}", principal == null ? "unknown" : principal.getName(), e.getMessage());
    }
}
