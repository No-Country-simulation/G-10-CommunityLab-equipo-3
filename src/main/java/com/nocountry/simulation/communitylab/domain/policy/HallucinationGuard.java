package com.nocountry.simulation.communitylab.domain.policy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;

public final class HallucinationGuard {

    private static final List<Pattern> COMPANY_PATTERNS = List.of(
            "globant", "accenture", "ibm", "microsoft", "google", "amazon",
            "oracle", "meta", "apple", "netflix", "tesla", "spotify",
            "mercadolibre", "meli", "platzi", "despegar", "auth0", "uala",
            "santander", "bbva", "telefonica", "telefónica", "claro", "telmex").stream()
            .map(term -> Pattern.compile("\\b" + Pattern.quote(term) + "\\b"))
            .toList();

    // Money values: $5.000, USD 5000, 5000 pesos/dólares/euros, 30%.
    private static final Pattern MONEY = Pattern.compile(
            "\\$\\s?[\\d.,]+|[\\d.,]+\\s?(usd|eur|mxn|cop|ars|clp|pen|dólares|dolares|euros|pesos)|\\b\\d+\\s?%");

    // Dates: 12/03/2026, 12-03-26, years 1900-2099, month names.
    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b|\\b(19|20)\\d{2}\\b"
                    + "|\\b(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)\\b");

    // Metrics: x2, 2x, 10k, +30%.
    private static final Pattern METRIC = Pattern.compile(
            "\\bx\\d+\\b|\\b\\d+x\\b|\\b\\d+k\\b|\\+\\d+\\s?%");

    private HallucinationGuard() {
    }

    public static boolean passes(String sourceText, String outputContentProcessed, String flag) {
        if (EnrichedComment.LLM_FALLBACK.equals(flag)) {
            return true;
        }
        return passes(sourceText, outputContentProcessed);
    }

    public static boolean passes(String sourceText, String outputContentProcessed) {
        if (sourceText == null || sourceText.isBlank() || outputContentProcessed == null || outputContentProcessed.isBlank()) {
            return false;
        }
        String source = normalize(sourceText);
        String text = normalize(outputContentProcessed);

        for (Pattern company : COMPANY_PATTERNS) {
            if (company.matcher(text).find() && !company.matcher(source).find()) {
                return false;
            }
        }
        return allMatchesContained(MONEY, text, source)
                && allMatchesContained(DATE, text, source)
                && allMatchesContained(METRIC, text, source);
    }

    private static boolean allMatchesContained(Pattern pattern, String text, String source) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (!source.contains(matcher.group())) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
