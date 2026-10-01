package com.jdpublication.webrecorder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for the "unwanted title" filter used by the recorder,
 * the batch downloader and the Excel tools (tools/Excel_Fixer/filter_and_push.js).
 *
 * Matching is whole-word and case-insensitive, so "Sussex", "Essex" or
 * "Adulthood" are NOT filtered while "Adult Movie" is.
 */
public final class ContentFilter {

    private static final String[] WORDS = {
            // Sexual / adult
            "sex", "sexy", "sexual", "sexuality", "porn", "porno", "pornography", "pornographic",
            "xxx", "adult", "nude", "nudity", "naked", "erotic", "erotica", "hentai",
            "rape", "rapist", "rapists", "incest", "orgasm", "orgasms", "intercourse",
            "masturbate", "masturbation", "dildo", "dildos", "vagina", "penis",
            "blowjob", "blowjobs", "handjob", "handjobs", "cum", "cumming",
            "stripper", "strippers", "striptease",
            "escort", "escorts", "prostitute", "prostitutes", "prostitution", "brothel",
            "whore", "whores", "slut", "sluts", "slutty",
            "tits", "titties", "boobs", "topless", "bdsm", "fetish", "fetishes",
            "threesome", "threesomes", "orgy", "orgies",
            "pedophile", "pedophiles", "paedophile", "paedophiles",
            "molest", "molested", "molestation", "molester",
            "lust", "pimp", "pimps", "pimpin", "pimping", "kinky",
            // Abusive / profanity
            "fuck", "fucker", "fuckers", "fucking", "fucked", "motherfucker", "motherfuckers", "motherfucking",
            "shit", "shits", "bullshit", "shitty", "dipshit",
            "bitch", "bitches", "bitching", "bastard", "bastards",
            "ass", "asshole", "assholes", "dumbass", "jackass",
            "cunt", "cunts", "dickhead", "dickheads", "cocksucker", "cocksuckers",
            "nigger", "niggers", "nigga", "niggas", "faggot", "faggots", "fag", "fags",
            "retard", "retards", "retarded", "abuse", "abusive", "abuser", "vulgar",
            // Hindi / Urdu
            "chutiya", "chutiyo", "madarchod", "bhenchod", "gaand", "gand",
            "bhosadi", "bhosadike", "randi", "harami", "kameena", "kamina", "saala", "kamini"
    };

    private static final Pattern PATTERN = Pattern.compile(
            "\\b(" + String.join("|", WORDS) + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ContentFilter() {
    }

    /** @return true when the title contains a blocked whole word. */
    public static boolean isUnwanted(String title) {
        if (title == null || title.trim().isEmpty()) {
            return false;
        }
        return PATTERN.matcher(title).find();
    }

    /** @return the blocked word found in the title, or null. */
    public static String matchedWord(String title) {
        if (title == null) {
            return null;
        }
        Matcher m = PATTERN.matcher(title);
        return m.find() ? m.group(1) : null;
    }
}
