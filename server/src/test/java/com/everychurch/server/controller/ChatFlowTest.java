package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.Member;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.MemberRepository;
import com.everychurch.server.repository.MessageRepository;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.security.JwtService;
import com.everychurch.server.service.ChatService;
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
 * Prompt 14: the per-church group chat. Runs on a real port so the STOMP client goes over
 * an actual WebSocket; users, churches and memberships are created directly through the
 * repositories (there's no HTTP path to make a bare Member without a whole login dance,
 * and this test is about chat, not registration).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ChatFlowTest extends AbstractIntegrationTest {

    private static final long TIMEOUT_S = 5;
    /** How long to wait to be sure something did NOT arrive. */
    private static final long QUIET_MS = 1500;

    /**
     * Hands payloads through as raw bytes whatever the content type. Spring's stock
     * String/ByteArray converters each only accept a narrow set of MIME types and refuse
     * application/json, which is what the chat speaks in both directions.
     */
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
    @Autowired ChurchRepository churchRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired JwtService jwtService;
    @Autowired ChatService chatService;
    @Autowired org.springframework.messaging.simp.user.SimpUserRegistry userRegistry;

    private final List<WebSocketStompClient> clients = new ArrayList<>();

    @AfterEach
    void stopClients() {
        clients.forEach(WebSocketStompClient::stop);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private User user(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(Role.MEMBER);
        return userRepository.save(user);
    }

    private Church church() {
        Church church = new Church();
        church.setName("Chat Church " + UUID.randomUUID());
        return churchRepository.save(church);
    }

    private void join(Church church, User user) {
        Member member = new Member();
        member.setChurch(church);
        member.setUser(user);
        memberRepository.save(member);
    }

    private String token(User user) {
        return jwtService.generateToken(user.getEmail(), user.getRole());
    }

    // ── a small STOMP client ────────────────────────────────────────────────

    /** One connected (or refused) STOMP client, with everything the server sent it. */
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
                    // an ERROR frame: the server rejected something and is closing us
                    conn.errorMessage = headers.getFirst("message");
                    conn.errorFrame.countDown();
                }
            }
        ).get(TIMEOUT_S, TimeUnit.SECONDS);
        return conn;
    }

    private void subscribe(Conn conn, Church church) {
        conn.session.subscribe("/topic/churches/" + church.getId() + "/chat", new StompFrameHandler() {
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

    /**
     * Subscribes, then waits until the server's own registry shows one more subscriber on
     * that topic, so a later send can't race the subscription. (Spring's simple broker
     * doesn't implement STOMP receipts, so there's nothing to wait on client-side.)
     */
    private void subscribeAndWait(Conn conn, Church church) throws Exception {
        String destination = "/topic/churches/" + church.getId() + "/chat";
        int before = subscriberCount(destination);
        subscribe(conn, church);

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

    private void sendChat(Conn conn, Church church, String body) throws Exception {
        send(conn, "/app/churches/" + church.getId() + "/chat.send", objectMapper.writeValueAsString(java.util.Map.of("body", body)));
    }

    // ── live delivery ───────────────────────────────────────────────────────

    @Test
    void membersOfTheSameChurchReceiveEachOthersMessages() throws Exception {
        Church church = church();
        User alice = user("alice");
        User bob = user("bob");
        join(church, alice);
        join(church, bob);

        Conn a = connect(token(alice));
        Conn b = connect(token(bob));
        subscribeAndWait(a, church);
        subscribeAndWait(b, church);

        sendChat(a, church, "  Good morning, everyone  ");

        for (Conn conn : List.of(a, b)) {
            String json = conn.received.poll(TIMEOUT_S, TimeUnit.SECONDS);
            assertNotNull(json, "expected a broadcast");
            JsonNode message = objectMapper.readTree(json);
            assertEquals("Good morning, everyone", message.get("body").asText(), "body is trimmed");
            assertEquals(alice.getDisplayName(), message.get("senderDisplayName").asText());
            assertFalse(message.has("email"), "the sender's email address must not be broadcast");
            assertEquals(church.getId(), message.get("churchId").asLong());
            // the client does new Date(sentAt): it must be an ISO-8601 string, not a numeric epoch
            assertTrue(message.get("sentAt").isTextual(), "sentAt should be a string, was " + message.get("sentAt"));
            assertDoesNotThrow(() -> java.time.Instant.parse(message.get("sentAt").asText()));
        }

        var stored = messageRepository.findByChurchIdOrderByIdDesc(church.getId(), org.springframework.data.domain.PageRequest.of(0, 10));
        assertEquals(1, stored.size(), "the message was persisted exactly once");
    }

    @Test
    void aChurchesChatIsIsolatedFromOtherChurches() throws Exception {
        Church churchA = church();
        Church churchB = church();
        User alice = user("alice");
        User bob = user("bob");
        join(churchA, alice);
        join(churchB, bob);

        Conn a = connect(token(alice));
        Conn b = connect(token(bob));
        subscribeAndWait(a, churchA);
        subscribeAndWait(b, churchB);

        sendChat(a, churchA, "only for church A");

        assertNotNull(a.received.poll(TIMEOUT_S, TimeUnit.SECONDS));
        assertNull(b.received.poll(QUIET_MS, TimeUnit.MILLISECONDS), "another church's member must not receive it");
    }

    // ── access control ──────────────────────────────────────────────────────

    @Test
    void aNonMemberCannotSubscribeToAChurchsChat() throws Exception {
        Church church = church();
        User outsider = user("outsider");

        Conn conn = connect(token(outsider)); // a valid login can connect...
        subscribe(conn, church);              // ...but not listen in

        assertTrue(conn.errorFrame.await(TIMEOUT_S, TimeUnit.SECONDS), "server should have answered with an ERROR frame");
        assertTrue(conn.errorMessage.contains("Only members"), "unexpected error: " + conn.errorMessage);
    }

    @Test
    void aNonMembersMessageIsDroppedAndNeverReachesMembers() throws Exception {
        Church church = church();
        User member = user("member");
        User outsider = user("outsider");
        join(church, member);

        Conn m = connect(token(member));
        subscribeAndWait(m, church);
        Conn o = connect(token(outsider));

        sendChat(o, church, "let me in");

        assertNull(m.received.poll(QUIET_MS, TimeUnit.MILLISECONDS), "members must not receive a non-member's message");
        assertEquals(0, messageRepository.findByChurchIdOrderByIdDesc(church.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).size());
    }

    @Test
    void churchStaffAndPlatformAdminsCanChatWithoutBeingMembers() throws Exception {
        Church church = church();
        User admin = user("admin");
        admin.setRole(Role.PLATFORM_ADMIN);
        userRepository.save(admin);
        User member = user("member");
        join(church, member);

        Conn m = connect(token(member));
        subscribeAndWait(m, church);
        Conn a = connect(token(admin));

        sendChat(a, church, "hello from the platform team");

        assertNotNull(m.received.poll(TIMEOUT_S, TimeUnit.SECONDS));
    }

    @Test
    void aClientCannotPublishStraightToATopic() throws Exception {
        Church church = church();
        User member = user("member");
        User other = user("other");
        join(church, member);
        join(church, other);

        Conn listener = connect(token(other));
        subscribeAndWait(listener, church);
        Conn sender = connect(token(member));

        // The simple broker would relay this to every subscriber as-is, skipping
        // persistence and the membership check — the interceptor must refuse it.
        send(sender, "/topic/churches/" + church.getId() + "/chat", "{\"body\":\"forged\"}");

        assertTrue(sender.errorFrame.await(TIMEOUT_S, TimeUnit.SECONDS), "server should reject a direct topic publish");
        assertNull(listener.received.poll(QUIET_MS, TimeUnit.MILLISECONDS), "the forged message must not be broadcast");
        assertEquals(0, messageRepository.findByChurchIdOrderByIdDesc(church.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).size());
    }

    @Test
    void connectingWithoutAValidTokenIsRefused() {
        assertThrows(Exception.class, () -> connect(null), "no token");
        assertThrows(Exception.class, () -> connect("not-a-real-token"), "bad token");
    }

    @Test
    void blankAndOversizeMessagesAreDropped() throws Exception {
        Church church = church();
        User member = user("member");
        join(church, member);

        Conn conn = connect(token(member));
        subscribeAndWait(conn, church);

        sendChat(conn, church, "   ");
        sendChat(conn, church, "x".repeat(ChatService.MAX_BODY_LENGTH + 1));

        assertNull(conn.received.poll(QUIET_MS, TimeUnit.MILLISECONDS));
        assertEquals(0, messageRepository.findByChurchIdOrderByIdDesc(church.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).size());

        // ...and a valid one still goes through on the same connection afterwards
        sendChat(conn, church, "x".repeat(ChatService.MAX_BODY_LENGTH));
        assertNotNull(conn.received.poll(TIMEOUT_S, TimeUnit.SECONDS));
    }

    // ── history over REST ───────────────────────────────────────────────────

    @Test
    void historyIsOrderedOldestFirstAndPagesBackwardWithACursor() throws Exception {
        Church church = church();
        User member = user("member");
        join(church, member);
        for (int i = 1; i <= 5; i++) {
            chatService.send(church.getId(), member, "message " + i);
        }
        String auth = "Bearer " + token(member);

        // the latest page, oldest-first within it
        String latest = mockMvc.perform(get("/api/churches/" + church.getId() + "/messages?limit=3")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].body").value("message 3"))
            .andExpect(jsonPath("$[2].body").value("message 5"))
            .andReturn().getResponse().getContentAsString();

        long oldestLoaded = objectMapper.readTree(latest).get(0).get("id").asLong();

        // the page before that, via the cursor
        mockMvc.perform(get("/api/churches/" + church.getId() + "/messages?limit=3&before=" + oldestLoaded)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].body").value("message 1"))
            .andExpect(jsonPath("$[1].body").value("message 2"));
    }

    @Test
    void historyRequiresMembershipAndAuthentication() throws Exception {
        Church church = church();
        User member = user("member");
        User outsider = user("outsider");
        join(church, member);
        chatService.send(church.getId(), member, "private");

        mockMvc.perform(get("/api/churches/" + church.getId() + "/messages")
                .header("Authorization", "Bearer " + token(outsider)))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/churches/" + church.getId() + "/messages"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/churches/" + church.getId() + "/messages")
                .header("Authorization", "Bearer " + token(member)))
            .andExpect(status().isOk());
    }
}
