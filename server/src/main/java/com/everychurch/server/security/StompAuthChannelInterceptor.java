package com.everychurch.server.security;

import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.DirectMessageService;
import com.everychurch.server.service.MemberService;
import com.everychurch.server.service.RateLimitService;
import io.jsonwebtoken.Claims;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auth for the STOMP layer. JWTs don't flow over WebSocket the way they do over HTTP:
 * the handshake is a plain HTTP upgrade the browser won't attach an Authorization header
 * to, so the client sends the token as a header on the STOMP CONNECT frame instead, and
 * this interceptor turns it into the session's Principal — mirroring what
 * JwtAuthenticationFilter does for HTTP.
 *
 * It also has to guard the two things the HTTP filter chain can't see:
 * - SUBSCRIBE: without a check, any logged-in user could subscribe to any church's chat
 *   topic and read its messages. Only chat topics are subscribable, and only by people
 *   MemberService.canAccessGroup allows.
 * - SEND: the simple broker broadcasts a client SEND to /topic/** to every subscriber
 *   as-is, skipping ChatController entirely (no persistence, no membership check).
 *   Clients may only send to /app/**, which is routed through the controller.
 *
 * Rejecting a frame by throwing makes Spring answer with a STOMP ERROR frame and close
 * the connection.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern CHAT_TOPIC = Pattern.compile("^/topic/churches/(\\d+)/chat$");
    // Canonical form only — DirectMessageService.topicFor always puts the lower id
    // first, so a given pair has exactly one topic regardless of who subscribes.
    private static final Pattern DIRECT_MESSAGE_TOPIC = Pattern.compile("^/topic/direct-messages/(\\d+)-(\\d+)$");

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final MemberService memberService;
    private final DirectMessageService directMessageService;
    private final RateLimitService rateLimitService;

    public StompAuthChannelInterceptor(
        JwtService jwtService,
        UserRepository userRepository,
        MemberService memberService,
        DirectMessageService directMessageService,
        RateLimitService rateLimitService
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.memberService = memberService;
        this.directMessageService = directMessageService;
        this.rateLimitService = rateLimitService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor == null ? null : accessor.getCommand();
        if (command == null) return message;

        switch (command) {
            case CONNECT -> authenticate(accessor);
            case SUBSCRIBE -> requireTopicAccess(accessor);
            case SEND -> requireAppDestination(accessor);
            default -> { /* DISCONNECT, ACK, heartbeats, etc. need no check */ }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingException("Missing bearer token");
        }

        Claims claims = jwtService.parseClaims(header.substring(7));
        if (claims == null) {
            throw new MessagingException("Invalid or expired token");
        }

        User user = userRepository.findByEmail(claims.getSubject())
            .orElseThrow(() -> new MessagingException("Unknown user"));

        accessor.setUser(new UsernamePasswordAuthenticationToken(
            user.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }

    private void requireTopicAccess(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        Matcher chatMatch = destination == null ? null : CHAT_TOPIC.matcher(destination);
        if (chatMatch != null && chatMatch.matches()) {
            User user = currentUser(accessor);
            long churchId = Long.parseLong(chatMatch.group(1));
            if (!memberService.canAccessGroup(churchId, user)) {
                throw new MessagingException("Only members of this church can join its chat");
            }
            return;
        }

        Matcher dmMatch = destination == null ? null : DIRECT_MESSAGE_TOPIC.matcher(destination);
        if (dmMatch != null && dmMatch.matches()) {
            User user = currentUser(accessor);
            long userA = Long.parseLong(dmMatch.group(1));
            long userB = Long.parseLong(dmMatch.group(2));
            // Compare against the canonical topic string (DirectMessageService.topicFor
            // is the one place that ordering is decided) rather than re-deriving "lower
            // id first" here — a non-canonical destination is rejected as a mismatch.
            boolean canonical = destination.equals(directMessageService.topicFor(userA, userB));
            boolean participant = user.getId() == userA || user.getId() == userB;
            if (!canonical || !participant) {
                throw new MessagingException("You can only join your own conversations");
            }
            return;
        }

        throw new MessagingException("Only church chat or direct-message topics can be subscribed to");
    }

    private void requireAppDestination(StompHeaderAccessor accessor) {
        User user = currentUser(accessor);
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/app/")) {
            throw new MessagingException("Messages can only be sent to /app/**");
        }
        RateLimitService.Decision decision = rateLimitService.tryConsume(
            "stomp-send", Long.toString(user.getId()), 60, Duration.ofMinutes(1));
        if (!decision.allowed()) {
            throw new MessagingException("Message rate limit exceeded; retry later");
        }
    }

    private User currentUser(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new MessagingException("Not authenticated");
        }
        return userRepository.findByEmail(principal.getName())
            .orElseThrow(() -> new MessagingException("Unknown user"));
    }
}
