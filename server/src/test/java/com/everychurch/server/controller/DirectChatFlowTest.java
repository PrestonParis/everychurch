package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.DirectMessageRepository;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.security.JwtService;
import com.everychurch.server.service.DirectMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1:1 direct messages — no church/membership involved at all, unlike group chat
 * (ChatFlowTest, which this deliberately mirrors the shape of). Runs on a real port
 * for the same reason: a STOMP client needs an actual WebSocket, not MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DirectChatFlowTest extends AbstractIntegrationTest {

    private static final long TIMEOUT_S = 5;
    private static final long QUIET_MS = 1500;

    private static final MessageConverter RAW_BYTES = new MessageConverter() {
        @Override
        public Object fromMessage(Message<?> message, Class<?> targetClass) {
            return message.getPayload();
        }

        @Override
        public Message<?> toMessage(Object payload, MessageHeaders headers) {
            return MessageBuilder.createMessage(payload, headers);
        }
    };

    @Value("${local.server.port}") int port;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired DirectMessageRepository directMessageRepository;
    @Autowired JwtService jwtService;
    @Autowired DirectMessageService directMessageService;
    @Autowired SimpUserRegistry userRegistry;

    private final List<WebSocketStompClient> clients = new ArrayList<>();

    @AfterEach
    void stopClients() {
        clients.forEach(WebSocketStompClient::stop);
    }

    private User user(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(Role.MEMBER);
        return userRepository.save(user);
    }

    private String token(User user) {
        return jwtService.generateToken(user.getEmail(), user.getRole());
    }

    private static final class Conn {
        StompSession session;
        final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        final CountDownLatch errorFrame = new CountDownLatch(1);
        volatile String errorMessage;
    }

    private Conn connect(String bearerToken) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(RAW_BYTES);
        clients.add(client);

        Conn conn = new Conn();
        StompHeaders connectHeaders = new StompHeaders();
        if (bearerToken != null) connectHeaders.add("Authorization", "Bearer " + bearerToken);

        conn.session = client.connectAsync(
            "ws://localhost:" + port + "/ws/websocket",
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    conn.errorMessage = headers.getFirst("message");
                    conn.errorFrame.countDown();
                }
            }
        ).get(TIMEOUT_S, TimeUnit.SECONDS);
        return conn;
    }

    private void subscribe(Conn conn, String destination) {
        conn.session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                conn.received.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
            }
        });
    }

    private void subscribeAndWait(Conn conn, String destination) throws Exception {
        int before = subscriberCount(destination);
        subscribe(conn, destination);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S);
        while (subscriberCount(destination) <= before) {
            assertTrue(System.nanoTime() < deadline, "the server never registered the subscription");
            Thread.sleep(25);
        }
    }

    private int subscriberCount(String destination) {
        return userRegistry.findSubscriptions(sub -> destination.equals(sub.getDestination())).size();
    }

    private void send(Conn conn, String destination, String json) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        conn.session.send(headers, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void sendDm(Conn conn, Long recipientId, String body) throws Exception {
        send(conn, "/app/direct-messages/" + recipientId + "/send", objectMapper.writeValueAsString(java.util.Map.of("body", body)));
    }

    @Test
    void twoUsersReceiveEachOthersDirectMessages() throws Exception {
        User alice = user("alice");
        User bob = user("bob");
        String topic = directMessageService.topicFor(alice.getId(), bob.getId());

        Conn a = connect(token(alice));
        Conn b = connect(token(bob));
        subscribeAndWait(a, topic);
        subscribeAndWait(b, topic);

        sendDm(a, bob.getId(), "  hey bob  ");

        for (Conn conn : List.of(a, b)) {
            String json = conn.received.poll(TIMEOUT_S, TimeUnit.SECONDS);
            assertNotNull(json, "expected a broadcast");
            JsonNode message = objectMapper.readTree(json);
            assertEquals("hey bob", message.get("body").asText(), "body is trimmed");
            assertEquals(alice.getId(), message.get("senderId").asLong());
            assertEquals(bob.getId(), message.get("recipientId").asLong());
            assertFalse(message.has("email"), "no email address should be broadcast");
        }

        assertEquals(1, directMessageRepository.findConversationNewestFirst(
            alice.getId(), bob.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).size());
    }

    @Test
    void aThirdPartyCannotSubscribeToSomeoneElsesConversation() throws Exception {
        User alice = user("alice");
        User bob = user("bob");
        User eve = user("eve");
        String topic = directMessageService.topicFor(alice.getId(), bob.getId());

        Conn e = connect(token(eve));
        subscribe(e, topic);

        assertTrue(e.errorFrame.await(TIMEOUT_S, TimeUnit.SECONDS), "server should reject the subscription");
        assertTrue(e.errorMessage.contains("your own conversations"), "unexpected error: " + e.errorMessage);
    }

    @Test
    void sendingToYourselfIsRejected() throws Exception {
        // Same "logged and dropped, no STOMP error frame" behavior as ChatController's
        // rejections (blank/oversize messages, non-members) — see DirectChatController's
        // dropRejectedMessage comment.
        User alice = user("alice");
        Conn a = connect(token(alice));

        sendDm(a, alice.getId(), "talking to myself");

        assertNull(a.received.poll(QUIET_MS, TimeUnit.MILLISECONDS));
        assertEquals(0, directMessageRepository.findConversationNewestFirst(
            alice.getId(), alice.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).size());
    }

    @Test
    void historyPagesBackwardAndMarksTheOtherPersonsMessagesRead() throws Exception {
        User alice = user("alice");
        User bob = user("bob");
        for (int i = 1; i <= 5; i++) {
            directMessageService.send(i % 2 == 0 ? bob : alice, i % 2 == 0 ? alice.getId() : bob.getId(), "message " + i);
        }
        String auth = "Bearer " + token(alice);

        String latest = mockMvc.perform(get("/api/direct-messages/" + bob.getId() + "?limit=3")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].body").value("message 3"))
            .andExpect(jsonPath("$[2].body").value("message 5"))
            .andReturn().getResponse().getContentAsString();

        long oldestLoaded = objectMapper.readTree(latest).get(0).get("id").asLong();

        mockMvc.perform(get("/api/direct-messages/" + bob.getId() + "?limit=3&before=" + oldestLoaded)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].body").value("message 1"))
            .andExpect(jsonPath("$[1].body").value("message 2"));

        // alice just read everything bob sent (messages 2 and 4) — her conversation
        // list should show zero unread from him now
        mockMvc.perform(get("/api/users/me/conversations").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].unreadCount").value(0));

        // bob never opened the conversation — alice's messages (1, 3, 5) are still unread for him
        mockMvc.perform(get("/api/users/me/conversations").header("Authorization", "Bearer " + token(bob)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].unreadCount").value(3))
            .andExpect(jsonPath("$[0].lastMessageBody").value("message 5"))
            .andExpect(jsonPath("$[0].lastMessageFromMe").value(false));
    }

    @Test
    void unauthenticatedHistoryRequestIsRejected() throws Exception {
        User alice = user("alice");
        mockMvc.perform(get("/api/direct-messages/" + alice.getId()))
            .andExpect(status().isUnauthorized());
    }
}
