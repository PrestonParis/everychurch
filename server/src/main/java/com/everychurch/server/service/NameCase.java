package com.everychurch.server.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the ALL-CAPS names, streets and cities the HIFLD/IRS import arrived with into
 * ordinary capitalization ("ST JAMES A.M.E. CHURCH OF THE LIVING GOD" -> "St James A.M.E.
 * Church of the Living God", "920 3RD ST SE" -> "920 3rd St SE").
 *
 * Only a value with no lowercase letter at all is touched: anything already mixed-case was
 * typed by a person or came from a source that cased it, and that beats a guess. Reverting
 * is exact, since every converted value was upper-case: {@code UPPER(name)} restores it.
 */
public final class NameCase {

    private NameCase() {}

    // Kept upper-case wherever they appear: denominational and organizational acronyms,
    // address directionals and route prefixes, and Roman numerals.
    private static final Set<String> KEEP_UPPER = Set.of(
        "AME", "AMEZ", "CME", "COGIC", "UMC", "LDS", "USA", "PCA", "PCUSA", "ELCA", "LCMS", "ACNA",
        "SDA", "UPC", "UPCI", "CRC", "RCA", "UCC", "EPC", "IPHC", "PAW", "CMA", "YMCA", "YWCA",
        "NE", "NW", "SE", "SW", "PO", "US", "FM", "RR", "HC", "CR", "SR",
        "II", "III", "IV", "VI", "VII", "VIII", "IX", "XI", "XII");

    // Lower-case unless first ("Church of the Nazarene", "Iglesia de Dios").
    private static final Set<String> SMALL_WORDS = Set.of(
        "of", "the", "and", "in", "at", "for", "on", "by", "to", "an", "de", "del", "y", "en", "da", "do", "dos", "das");

    // A trailing state code ("NEW APOSTOLIC CHURCH OF WATERVLIET MI") stays upper-case.
    private static final Set<String> STATE_CODES = Set.of(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA",
        "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM",
        "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA",
        "WV", "WI", "WY", "PR");

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}']+");
    private static final Pattern ORDINAL = Pattern.compile("(\\d+)(ST|ND|RD|TH)");

    /** True when {@code value} has a letter and no lowercase letter. */
    public static boolean isAllCaps(String value) {
        if (value == null) return false;
        boolean letter = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLowerCase(ch)) return false;
            if (Character.isLetter(ch)) letter = true;
        }
        return letter;
    }

    /**
     * Ordinary capitalization for an all-caps value; anything else is returned unchanged.
     * A lone short word ("ICM") is left alone, since it is more likely an acronym.
     */
    public static String fix(String value) {
        if (!isAllCaps(value)) return value;
        String trimmed = value.trim();
        if (trimmed.length() <= 4 && WORD.matcher(trimmed).matches()) return value;

        Matcher m = WORD.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        int last = 0;
        boolean first = true;
        while (m.find()) {
            String word = m.group();
            String before = value.substring(last, m.start());
            boolean isLast = value.substring(m.end()).chars().noneMatch(Character::isLetterOrDigit);
            boolean afterHyphen = before.endsWith("-");
            out.append(before).append(word(word, first, isLast, afterHyphen));
            last = m.end();
            first = false;
        }
        return out.append(value.substring(last)).toString();
    }

    private static String word(String word, boolean first, boolean isLast, boolean afterHyphen) {
        if (KEEP_UPPER.contains(word)) return word;
        if (isLast && !first && STATE_CODES.contains(word)) return word;

        Matcher ordinal = ORDINAL.matcher(word);
        if (ordinal.matches()) return ordinal.group(1) + ordinal.group(2).toLowerCase();
        // Unit numbers, route numbers and the like ("12B", "I95") stay as written.
        if (word.chars().anyMatch(Character::isDigit)) return word;
        // Initials and directionals: "J", "N", the letters of "A M E".
        if (word.length() == 1) return word;

        String lower = word.toLowerCase();
        if (!first && SMALL_WORDS.contains(lower)) return lower;
        // "Seventh-day Adventist", "Latter-day Saints".
        if (afterHyphen && lower.equals("day")) return lower;
        if (lower.startsWith("mc") && lower.length() > 3) {
            return "Mc" + Character.toUpperCase(lower.charAt(2)) + lower.substring(3);
        }
        // "O'Brien", "D'Iberville" — but "Mary's", not "Mary'S".
        if (lower.length() > 2 && lower.charAt(1) == '\'') {
            return Character.toUpperCase(lower.charAt(0)) + "'" + Character.toUpperCase(lower.charAt(2)) + lower.substring(3);
        }
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
