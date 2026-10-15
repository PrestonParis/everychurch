package com.everychurch.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameCaseTest {

    @Test
    void capitalizesNamesWithSmallWordsLowercase() {
        assertEquals("Friendship Baptist Church of Massillon", NameCase.fix("FRIENDSHIP BAPTIST CHURCH OF MASSILLON"));
        assertEquals("The Church of Jesus Christ of Latter-day Saints",
            NameCase.fix("THE CHURCH OF JESUS CHRIST OF LATTER-DAY SAINTS"));
        assertEquals("Iglesia de Dios Septimo Dia Inc", NameCase.fix("IGLESIA DE DIOS SEPTIMO DIA INC"));
        assertEquals("Seventh-day Adventist Church", NameCase.fix("SEVENTH-DAY ADVENTIST CHURCH"));
    }

    @Test
    void keepsAcronymsInitialsAndTrailingStateCodes() {
        assertEquals("Bethel AME Church", NameCase.fix("BETHEL AME CHURCH"));
        assertEquals("St James A.M.E. Zion Church", NameCase.fix("ST JAMES A.M.E. ZION CHURCH"));
        assertEquals("Mt Zion A M E Church", NameCase.fix("MT ZION A M E CHURCH"));
        assertEquals("New Apostolic Church of Watervliet MI", NameCase.fix("NEW APOSTOLIC CHURCH OF WATERVLIET MI"));
        assertEquals("Pius XII Parish", NameCase.fix("PIUS XII PARISH"));
        // A state code mid-name is just a word.
        assertEquals("Church in the Pines", NameCase.fix("CHURCH IN THE PINES"));
    }

    @Test
    void handlesApostrophesAndMcNames() {
        assertEquals("St. Mary's Catholic Church", NameCase.fix("ST. MARY'S CATHOLIC CHURCH"));
        assertEquals("O'Brien Memorial Chapel", NameCase.fix("O'BRIEN MEMORIAL CHAPEL"));
        assertEquals("McAllen", NameCase.fix("MCALLEN"));
        assertEquals("Winston-Salem", NameCase.fix("WINSTON-SALEM"));
    }

    @Test
    void formatsStreetAddresses() {
        assertEquals("920 3rd St SE", NameCase.fix("920 3RD ST SE"));
        assertEquals("2001 North Maize Road", NameCase.fix("2001 NORTH MAIZE ROAD"));
        assertEquals("PO Box 12", NameCase.fix("PO BOX 12"));
        assertEquals("1200 FM 1960 Rd W", NameCase.fix("1200 FM 1960 RD W"));
        assertEquals("45 US Highway 1 Unit 12B", NameCase.fix("45 US HIGHWAY 1 UNIT 12B"));
        assertEquals("21st Ave N", NameCase.fix("21ST AVE N"));
    }

    @Test
    void leavesMixedCaseBlankAndShortAcronymsAlone() {
        assertEquals("Grace Church of NYC", NameCase.fix("Grace Church of NYC"));
        assertEquals("ICM", NameCase.fix("ICM"));
        assertEquals("1234", NameCase.fix("1234"));
        assertNull(NameCase.fix(null));
        assertTrue(NameCase.isAllCaps("ST LOUIS"));
        assertFalse(NameCase.isAllCaps("St Louis"));
        assertFalse(NameCase.isAllCaps("123 - 45"));
    }
}
