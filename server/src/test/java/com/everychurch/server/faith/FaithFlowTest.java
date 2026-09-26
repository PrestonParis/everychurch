package com.everychurch.server.faith;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.model.*;
import com.everychurch.server.repository.*;
import com.everychurch.server.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.everychurch.server.faith.FaithTypes.*;

@SpringBootTest
@AutoConfigureMockMvc
class FaithFlowTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ChurchRepository churches;
    @Autowired ChurchStaffRepository staff;
    @Autowired JwtService jwt;
    @Autowired FaithCatalogService catalogs;
    @Autowired FaithProfileService profiles;
    @Autowired FaithEvaluationService evaluations;
    @Autowired JdbcTemplate jdbc;
    User user(Role role){User u=new User();u.setEmail(UUID.randomUUID()+"@faith.test");u.setPasswordHash("unused");u.setDisplayName("Faith Tester");u.setRole(role);return users.save(u);}
    String token(User u){return "Bearer "+jwt.generateToken(u.getEmail(),u.getRole());}
    Church church(){Church c=new Church();c.setName("Quiz Test Church");c.setCity(UUID.randomUUID().toString());c.setState("IL");return churches.save(c);}
    ProfileInput input(Long base){return new ProfileInput("2026-09-v1","catholic",Map.of("godhead",new Statement("trinity",null,null),"scripture",new Statement("bible",null,null),"authority",new Statement("magisterium",null,null),"baptism-recipients",new Statement("infants",null,null),"communion",new Statement("transubstantiation",null,null)),"Ask our priest before receiving Communion.",base);}
    Evaluation request(Church c){return new Evaluation("2026-09-v1",Map.of("godhead",new Answer("trinity",2),"communion",new Answer("transubstantiation",2)),c.getCity(),c.getState(),null,null,null);}
    @Test void anonymousQuizIsAvailableAndDoesNotPersist() throws Exception {
        mvc.perform(get("/api/learn/quiz")).andExpect(status().isOk()).andExpect(jsonPath("$.dimensions.length()").value(24));
        Integer before=jdbc.queryForObject("SELECT count(*) FROM faith_saved_attempts",Integer.class);
        mvc.perform(post("/api/learn/quiz/evaluate").contentType("application/json").content(json.writeValueAsString(request(church())))).andExpect(status().isOk()).andExpect(jsonPath("$.rulesVersion").value("alignment-v1"));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM faith_saved_attempts",Integer.class));
    }
    @Test void draftIsPrivateAndOnlyAuthorizedStaffCanPublish() throws Exception {
        Church c=church();User owner=user(Role.MEMBER),stranger=user(Role.MEMBER);
        ChurchStaff s=new ChurchStaff();s.setChurch(c);s.setUser(owner);s.setOwner(true);s.setPermissions(Set.of());staff.save(s);
        String path="/api/churches/"+c.getId()+"/beliefs";
        mvc.perform(get(path+"/editor")).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"/editor").header("Authorization",token(stranger))).andExpect(status().isForbidden());
        mvc.perform(put(path+"/draft").header("Authorization",token(owner)).contentType("application/json").content(json.writeValueAsString(input(null)))).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(post(path+"/publish").header("Authorization",token(stranger)).contentType("application/json").content(json.writeValueAsString(input(null)))).andExpect(status().isForbidden());
        mvc.perform(post(path+"/publish").header("Authorization",token(owner)).contentType("application/json").content(json.writeValueAsString(input(null)))).andExpect(status().isOk()).andExpect(jsonPath("$.confirmedBy").value("Faith Tester"));
        mvc.perform(get(path)).andExpect(jsonPath("$.content.answers.godhead.option").value("trinity"));
        mvc.perform(post(path+"/publish").header("Authorization",token(owner)).contentType("application/json").content(json.writeValueAsString(input(null)))).andExpect(status().isConflict());
    }
    @Test void manageProfileDoesNotGrantManageBeliefsButExplicitPermissionDoes() throws Exception {
        Church c=church();User u=user(Role.MEMBER);ChurchStaff s=new ChurchStaff();s.setChurch(c);s.setUser(u);s.setPermissions(Set.of(ChurchPermission.EDIT_PROFILE));staff.save(s);
        String path="/api/churches/"+c.getId()+"/beliefs/editor";
        mvc.perform(get(path).header("Authorization",token(u))).andExpect(status().isForbidden());
        s.setPermissions(Set.of(ChurchPermission.MANAGE_BELIEFS));staff.save(s);
        mvc.perform(get(path).header("Authorization",token(u))).andExpect(status().isOk());
    }
    @Test void matchingUsesConfirmedLocalAnswersNotDenominationLabel() {
        Church c=church();c.setDenomination("Baptist");churches.save(c);
        assertTrue(evaluations.evaluate(request(c)).churches().isEmpty());
        profiles.publish(c.getId(),user(Role.PLATFORM_ADMIN),input(null));
        Result result=evaluations.evaluate(request(c));
        assertEquals(1,result.churches().size());assertEquals(100,result.churches().getFirst().doctrine());
        assertEquals("Ask our priest before receiving Communion.",result.churches().getFirst().participation());
    }
    @Test void savedResultsAreOwnerOnlyAndRemainSnapshotsAfterProfileChanges() throws Exception {
        Church c=church();User admin=user(Role.PLATFORM_ADMIN),u=user(Role.MEMBER),other=user(Role.MEMBER);
        Profile p=profiles.publish(c.getId(),admin,input(null));
        Saved saved=evaluations.save(u,request(c));
        Map<String,Statement> changed=new HashMap<>(input(p.revision()).answers());changed.put("communion",new Statement("memorial",null,null));
        profiles.publish(c.getId(),admin,new ProfileInput("2026-09-v1",null,changed,"Different guidance",p.revision()));
        assertEquals(p.revision(),evaluations.get(u,saved.id()).result().churches().getFirst().profileRevision());
        assertNotEquals(p.revision(),evaluations.evaluate(request(c)).churches().getFirst().profileRevision());
        mvc.perform(get("/api/users/me/quiz-attempts").header("Authorization",token(other))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/users/me/quiz-attempts/"+saved.id()).header("Authorization",token(other))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/me/quiz-attempts/"+saved.id()).header("Authorization",token(u))).andExpect(status().isNoContent());
        assertTrue(evaluations.list(u).isEmpty());
    }
    @Test void validatesUntrustedAnswersAndSourceUrls() throws Exception {
        Church c=church();User admin=user(Role.PLATFORM_ADMIN);
        mvc.perform(post("/api/learn/quiz/evaluate").contentType("application/json").content("{\"catalogVersion\":\"2026-09-v1\",\"answers\":{\"made-up\":{\"option\":\"yes\",\"importance\":1}}}")).andExpect(status().isBadRequest());
        var invalid=new ProfileInput("2026-09-v1",null,Map.of("godhead",new Statement("trinity",null,"javascript:alert(1)")),"",null);
        mvc.perform(put("/api/churches/"+c.getId()+"/beliefs/draft").header("Authorization",token(admin)).contentType("application/json").content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
    }
    @Test void geographyFiltersWithoutDiscardingMoreAlignedChurches() {
        Church c=church();profiles.publish(c.getId(),user(Role.PLATFORM_ADMIN),input(null));
        jdbc.update("UPDATE churches SET latitude=41.88,longitude=-87.63,geo_point=ST_SetSRID(ST_MakePoint(-87.63,41.88),4326)::geography WHERE id=?",c.getId());
        var nearby=new Evaluation("2026-09-v1",request(c).answers(),null,null,41.88,-87.63,1.0);
        assertTrue(evaluations.evaluate(nearby).churches().stream().anyMatch(m->m.id().equals(c.getId().toString())));
        var far=new Evaluation("2026-09-v1",request(c).answers(),null,null,0.0,0.0,1.0);
        assertTrue(evaluations.evaluate(far).churches().isEmpty());
    }
    @Test void catalogPublishingIsAdminOnlyAndPublishedVersionsAreImmutable() throws Exception {
        Catalog c=catalogs.get("2026-09-v1");
        mvc.perform(post("/api/admin/faith/catalogs").header("Authorization",token(user(Role.MEMBER))).contentType("application/json").content(json.writeValueAsString(c))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/faith/catalogs").header("Authorization",token(user(Role.PLATFORM_ADMIN))).contentType("application/json").content(json.writeValueAsString(c))).andExpect(status().isConflict());
    }
    @Test void fullLengthDisplayNameCanConfirmAProfile() {
        User admin=user(Role.PLATFORM_ADMIN);
        admin.setDisplayName("A".repeat(255));users.save(admin);
        Profile profile=profiles.publish(church().getId(),admin,input(null));
        assertEquals(admin.getDisplayName(),profile.confirmedBy());
    }

}
