package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Status codes as a real servlet container returns them.
 *
 * Every other controller test uses MockMvc, which never performs the container's ERROR
 * dispatch. That matters: Spring Security answers "authenticated but not allowed" with
 * response.sendError(403), the container then dispatches an internal request to /error,
 * and if that dispatch is itself secured (it's unauthenticated — the JWT filter doesn't
 * run on it) the 403 is overwritten with a 401. MockMvc-based tests all saw 403 while a
 * real client got 401 for every permission failure in the API. This test goes over a
 * real socket so it can't be fooled by that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealContainerStatusCodeTest extends AbstractIntegrationTest {

    @Value("${local.server.port}") int port;

    @Autowired UserRepository userRepository;
    @Autowired ChurchRepository churchRepository;
    @Autowired JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();

    private int get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String tokenForNewUser() {
        User user = new User();
        user.setEmail("realcontainer-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(Role.MEMBER);
        userRepository.save(user);
        return jwtService.generateToken(user.getEmail(), user.getRole());
    }

    private long newChurch() {
        Church church = new Church();
        church.setName("Real Container Church " + UUID.randomUUID());
        return churchRepository.save(church).getId();
    }

    @Test
    void authenticatedButNotPermittedIsForbiddenNotUnauthorized() throws Exception {
        long churchId = newChurch();
        String outsider = tokenForNewUser();

        // AccessDeniedException thrown from a service
        assertEquals(403, get("/api/churches/" + churchId + "/members", outsider));
        assertEquals(403, get("/api/churches/" + churchId + "/messages", outsider));
        assertEquals(403, get("/api/churches/" + churchId + "/staff", outsider));
        // a role check declared in SecurityConfig itself
        assertEquals(403, get("/api/admin/claims", outsider));
    }

    @Test
    void noCredentialsIsStillUnauthorized() throws Exception {
        long churchId = newChurch();

        assertEquals(401, get("/api/churches/" + churchId + "/members", null));
        assertEquals(401, get("/api/admin/claims", null));
        assertEquals(401, get("/api/churches/" + churchId + "/members", "not-a-real-token"));
    }

    @Test
    void publicEndpointsStayPublic() throws Exception {
        long churchId = newChurch();

        assertEquals(200, get("/api/churches/" + churchId, null));
        assertEquals(200, get("/api/churches", null));
    }
}
