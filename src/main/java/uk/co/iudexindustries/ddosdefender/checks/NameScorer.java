package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds up every name check into one bot-likeness score.
 *
 * What the score is used for (see LoginListener and MojangBudget):
 *   under 25   looks human — first claim on the Mojang lookup budget
 *   25 to 99   suspicious — gets a lookup only while the budget is quiet
 *   100+       blocked outright, no lookup spent
 *
 * The weights were tuned against 300,962 real account names and eight kinds of
 * generated bot names: about 3% of real names reach 25 and 0.01% reach 100.
 *
 * "Swarm" checks look at the recent join window (names and times of the last
 * minute's logins) and only score a name that matches the pattern most of that
 * window shares, so a real player joining during a bot wave isn't dragged in.
 */
@UtilityClass
public class NameScorer {

    public static final int BLOCK_SCORE = 100;

    /**
     * @param name            the username trying to join
     * @param uuid            the UUID the client sent, or null (pre-1.19 clients)
     * @param recentNames     names that tried to join in the last minute, oldest first
     * @param recentJoinTimes when they tried, epoch millis, same order
     */
    public int score(String name, String uuid, List<String> recentNames, List<Long> recentJoinTimes) {
        if (name == null) return 0;
        int total = 0;

        // The join window.
        total += LengthSwarmCheck.score(name, recentNames);
        total += CapitalisationSwarmCheck.score(name, recentNames);
        total += JoinTimingSwarmCheck.score(recentJoinTimes, System.currentTimeMillis());
        total += SimilarNameSwarmCheck.score(name, recentNames);
        total += SharedBaseWordSwarmCheck.score(name, recentNames);
        total += NumberSequenceSwarmCheck.score(name, recentNames);

        // The name on its own.
        for (int value : singleNameScores(name).values()) total += value;

        total += UuidVersionCheck.score(uuid);
        return total;
    }

    /**
     * Every single-name check's score, labelled, for /ddosdefender test. The
     * swarm checks are left out: they need a live join window.
     */
    public Map<String, Integer> breakdown(String name) {
        return name == null ? new LinkedHashMap<>() : singleNameScores(name);
    }

    private Map<String, Integer> singleNameScores(String name) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("Letter pairs",            LetterPairCheck.score(name));
        scores.put("Random casing",           RandomCasingCheck.score(name));
        scores.put("Letter/digit mixing",     LetterDigitMixCheck.score(name));
        scores.put("Synthetic word",          SyntheticWordCheck.score(name));
        scores.put("Leet speak",              LeetSpeakCheck.score(name));
        scores.put("Keyboard walk",           KeyboardWalkCheck.score(name));
        scores.put("Entropy",                 EntropyCheck.score(name));
        scores.put("Numeric dominance",       NumericDominanceCheck.score(name));
        scores.put("Repeated characters",     RepeatedCharacterCheck.score(name));
        scores.put("Degenerate name",         DegenerateNameCheck.score(name));
        scores.put("Synthetic word + number", syntheticWordsBeforeNumber(name));
        scores.put("Lowercase repeat",        LowercaseRepeatCheck.score(name));
        scores.put("Truncated repeat",        TruncatedRepeatCheck.score(name));
        scores.put("Word/number echo",        WordNumberEchoCheck.score(name));
        scores.put("Repeated half",           RepeatedHalfCheck.score(name));
        scores.put("Decoration wrapper",      DecorationWrapperCheck.score(name));
        scores.put("Bot keyword",             BotKeywordCheck.score(name));
        scores.put("Impersonation",           ImpersonationCheck.score(name));
        scores.put("Attack vocabulary",       AttackVocabularyCheck.score(name));
        return scores;
    }

    /**
     * Names shaped "Words + 2-4 digits" where most of the word parts don't
     * look like real words ("Xkqzv_Prrt42"). Real names of that shape use real
     * words ("Dark_Wolf42"), so it only scores when 75%+ of the parts fail
     * HumanSegment.
     */
    private int syntheticWordsBeforeNumber(String name) {
        int len = name.length();
        if (len < 4) return 0;

        int digitStart = len;
        while (digitStart > 0 && isDigit(name.charAt(digitStart - 1))) digitStart--;
        int digits = len - digitStart;
        if (digits < 2 || digits > 4 || digitStart == 0) return 0;

        // Split the word part at underscores and camelCase boundaries.
        int parts = 0, synthetic = 0, start = 0;
        for (int i = 1; i <= digitStart; i++) {
            boolean boundary = i == digitStart
                    || name.charAt(i) == '_'
                    || (isLower(name.charAt(i - 1)) && isUpper(name.charAt(i)));
            if (!boundary) continue;
            if (i > start && name.charAt(start) != '_') {
                parts++;
                if (!HumanSegment.isHumanLike(name.substring(start, i))) synthetic++;
            }
            start = (i < digitStart && name.charAt(i) == '_') ? i + 1 : i;
        }
        return parts > 0 && synthetic * 100 / parts >= 75 ? 10 : 0;
    }

    private boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private boolean isLower(char c) { return c >= 'a' && c <= 'z'; }
    private boolean isUpper(char c) { return c >= 'A' && c <= 'Z'; }
}
