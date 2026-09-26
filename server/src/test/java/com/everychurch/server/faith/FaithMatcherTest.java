package com.everychurch.server.faith;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.everychurch.server.faith.FaithTypes.*;

class FaithMatcherTest {
    Catalog catalog;
    FaithMatcher matcher=new FaithMatcher();
    @BeforeEach void catalog() throws Exception {
        try(var stream=getClass().getResourceAsStream("/faith/catalog-v1.json")) {catalog=new ObjectMapper().readValue(stream,Catalog.class);}
    }
    Match score(Map<String,Answer> user,Map<String,Statement> church) {return matcher.score(catalog,user,church,"1","Example","church",1L,"2026-09-22",null);}
    Statement s(String option){return new Statement(option,null,null);}
    @Test void uncertaintyNeverCreatesAgreement() {
        var m=score(Map.of("godhead",new Answer("trinity",2)),Map.of());
        assertNull(m.doctrine());assertEquals(0,m.lowerBound());assertEquals(100,m.upperBound());assertEquals(0,m.coverage());
    }
    @Test void allUnknownIsExplorationNotIdentity() {
        var m=score(Map.of("godhead",new Answer("unknown",3)),Map.of("godhead",s("trinity")));
        assertEquals("explore",m.status());assertNull(m.doctrine());assertEquals(0,m.comparedTopics());
    }
    @Test void essentialConflictCannotBeOverriddenByDevotions() {
        var m=score(Map.of("godhead",new Answer("trinity",3),"rosary",new Answer("seek",2)),Map.of("godhead",s("distinct"),"rosary",s("regular")));
        assertEquals("essential_conflict",m.status());assertEquals(100,m.practices());
    }
    @Test void essentialUnknownNeedsConfirmation() {
        assertEquals("needs_confirmation",score(Map.of("godhead",new Answer("trinity",3)),Map.of()).status());
    }
    @Test void multipleViewsDoesNotGetFullAgreement() {
        var m=score(Map.of("godhead",new Answer("trinity",3)),Map.of("godhead",s("multiple")));
        assertEquals(50,m.doctrine());assertEquals("needs_confirmation",m.status());
    }
    @Test void uncertainUserAndUnimportantUserDoNotAffectScore() {
        var m=score(Map.of("godhead",new Answer("trinity",2),"saints",new Answer("no",0)),Map.of("godhead",s("trinity"),"saints",s("yes")));
        assertEquals(100,m.doctrine());assertEquals(1,m.answeredTopics());
    }
    @Test void EucharisticOverlapIsPartialNotIdentical() {
        assertEquals(50,score(Map.of("communion",new Answer("union",1)),Map.of("communion",s("transubstantiation"))).doctrine());
    }
    @Test void preferencesDoNotRewriteDoctrine() {
        var church=Map.of("godhead",s("trinity"),"rosary",s("regular"));
        var first=score(Map.of("godhead",new Answer("trinity",2),"rosary",new Answer("seek",2)),church);
        var second=score(Map.of("godhead",new Answer("trinity",2),"rosary",new Answer("avoid",2)),church);
        assertEquals(first.doctrine(),second.doctrine());assertNotEquals(first.practices(),second.practices());
    }
    @Test void sparseExactAgreementCannotClaimStrongAlignment() {
        assertEquals("limited_information",score(Map.of("godhead",new Answer("trinity",1)),Map.of("godhead",s("trinity"))).status());
    }
    @Test void validatesUnknownIdsAndOptionsAndImportance() {
        assertThrows(IllegalArgumentException.class,()->matcher.validate(catalog,Map.of("made-up",new Answer("yes",1))));
        assertThrows(IllegalArgumentException.class,()->matcher.validate(catalog,Map.of("godhead",new Answer("yes",1))));
        assertThrows(IllegalArgumentException.class,()->matcher.validate(catalog,Map.of("godhead",new Answer("trinity",99))));
    }
    @Test void notOfferingADevotionIsNotAClaimAboutDoctrine() {
        var m=score(Map.of("rosary",new Answer("avoid",1)),Map.of("rosary",s("private")));
        assertEquals(100,m.practices());assertNull(m.doctrine());
    }
    @Test void resultsAreDeterministicAndExplainEveryWeightedAnswer() {
        var answers=Map.of("godhead",new Answer("trinity",1),"confession",new Answer("sacrament",2));
        var church=Map.of("godhead",s("trinity"));
        assertEquals(score(answers,church),score(answers,church));
        assertEquals(2,score(answers,church).details().size());
    }
}
