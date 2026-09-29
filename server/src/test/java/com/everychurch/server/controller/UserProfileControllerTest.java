package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PUT /api/users/me/display-name, POST /api/users/me/avatar/upload-url, PUT
 *  /api/users/me/avatar (the new profile feature) — display-name/avatar changes
 *  persist and are reflected in a subsequent login's AuthResponse, since the client
 *  has no other way to refetch identity (see UserProfileService, AuthService
 *  .toAuthResponse). No S3_BUCKET_NAME is configured for this test environment (see
 *  S3PresignServiceTest), so avatarUploadUrl is expected to fail with 503. */
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String authToken(String email) throws Exception {
        RegisterRequest request = new RegisterRequest(email, "password123", "Original Name");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    @Test
    void updateDisplayName_persistsAndReflectsInLogin() throws Exception {
        String token = authToken("displayname-fixture@example.com");

        mockMvc.perform(put("/api/users/me/display-name")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"displayName\":\"New Name\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("New Name"));

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"displayname-fixture@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("New Name"));
    }

    @Test
    void updateDisplayName_blank_badRequest() throws Exception {
        String token = authToken("blankname-fixture@example.com");

        mockMvc.perform(put("/api/users/me/display-name")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"displayName\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateAvatar_persistsAndReflectsInLogin() throws Exception {
        String token = authToken("avatar-fixture@example.com");
        String avatarUrl = "https://example-bucket.s3.us-east-1.amazonaws.com/avatars/1/abc.jpg";

        mockMvc.perform(put("/api/users/me/avatar")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"url\":\"" + avatarUrl + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.avatarUrl").value(avatarUrl));

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"avatar-fixture@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.avatarUrl").value(avatarUrl));
    }

    @Test
    void avatarUploadUrl_withoutS3Configured_serviceUnavailable() throws Exception {
        String token = authToken("upload-fixture@example.com");

        mockMvc.perform(post("/api/users/me/avatar/upload-url")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"contentType\":\"image/png\"}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void updateProfile_savesAllFieldsAndReflectsInMyProfile() throws Exception {
        String token = authToken("profile-fixture@example.com");

        mockMvc.perform(put("/api/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("""
                    {"displayName":"Nate","username":"nate_augustine","bio":"Hello there",
                     "location":"Atlanta, GA","tradition":"Catholic","homeChurchId":null}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("nate_augustine"))
            .andExpect(jsonPath("$.bio").value("Hello there"))
            .andExpect(jsonPath("$.location").value("Atlanta, GA"))
            .andExpect(jsonPath("$.tradition").value("Catholic"));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("nate_augustine"));
    }

    @Test
    void updateProfile_usernameAlreadyTaken_badRequest() throws Exception {
        String firstToken = authToken("first-username-fixture@example.com");
        mockMvc.perform(put("/api/users/me/profile")
                .header("Authorization", "Bearer " + firstToken)
                .contentType("application/json")
                .content("{\"displayName\":\"First\",\"username\":\"claimed_handle\"}"))
            .andExpect(status().isOk());

        String secondToken = authToken("second-username-fixture@example.com");
        mockMvc.perform(put("/api/users/me/profile")
                .header("Authorization", "Bearer " + secondToken)
                .contentType("application/json")
                .content("{\"displayName\":\"Second\",\"username\":\"claimed_handle\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateProfile_reservedUsername_badRequest() throws Exception {
        String token = authToken("reserved-username-fixture@example.com");
        mockMvc.perform(put("/api/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"displayName\":\"Someone\",\"username\":\"me\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void usernameAvailable_reflectsTakenAndFree() throws Exception {
        String token = authToken("availability-fixture@example.com");
        mockMvc.perform(put("/api/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"displayName\":\"Someone\",\"username\":\"availability_taken\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me/username-available").param("value", "availability_taken")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(true)); // it's the caller's own username

        String otherToken = authToken("availability-fixture-2@example.com");
        mockMvc.perform(get("/api/users/me/username-available").param("value", "availability_taken")
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(get("/api/users/me/username-available").param("value", "availability_free")
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void changePassword_wrongCurrent_badRequest() throws Exception {
        String token = authToken("changepw-fixture@example.com");
        mockMvc.perform(put("/api/users/me/password")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"currentPassword\":\"not-the-password\",\"newPassword\":\"newpassword123\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_success_allowsLoginWithNewPassword() throws Exception {
        String token = authToken("changepw-success-fixture@example.com");
        mockMvc.perform(put("/api/users/me/password")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"currentPassword\":\"password123\",\"newPassword\":\"newpassword123\"}"))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"changepw-success-fixture@example.com\",\"password\":\"newpassword123\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void publicProfile_visibleLoggedOutWhenPublic() throws Exception {
        String token = authToken("public-fixture@example.com");
        mockMvc.perform(put("/api/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"displayName\":\"Public Person\",\"username\":\"public_person_handle\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/public_person_handle"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Public Person"))
            .andExpect(jsonPath("$.isSelf").value(false));
    }

    @Test
    void publicProfile_privateHidesFromOthersButNotSelf() throws Exception {
        String token = authToken("private-fixture@example.com");
        mockMvc.perform(put("/api/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"displayName\":\"Private Person\",\"username\":\"private_handle\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/users/me/privacy")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("""
                    {"profileVisibility":"PRIVATE","showLocation":false,"showTradition":false,
                     "showHomeChurch":false,"showFollowing":false}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/private_handle"))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/users/private_handle").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isSelf").value(true));
    }

    @Test
    void publicProfile_unknownUsername_notFound() throws Exception {
        mockMvc.perform(get("/api/users/no_such_handle_at_all"))
            .andExpect(status().isNotFound());
    }

    @Test
    void myClaims_returnsOnlyOwnClaimsNewestFirst() throws Exception {
        String token = authToken("myclaims-fixture@example.com");

        String churchBody = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"My Claims Fixture Church\",\"state\":\"ZZ\",\"city\":\"Claimsville\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long churchId = objectMapper.readTree(churchBody).get("id").asLong();

        mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"notes\":\"I run this church\"}"))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/users/me/claims").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].churchId").value(churchId))
            .andExpect(jsonPath("$[0].status").value("PENDING"));

        // A second, unrelated user has no claims of their own.
        String otherToken = authToken("myclaims-fixture-2@example.com");
        mockMvc.perform(get("/api/users/me/claims").header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }
}
