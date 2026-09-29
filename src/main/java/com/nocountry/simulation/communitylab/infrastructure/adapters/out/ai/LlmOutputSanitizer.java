package com.nocountry.simulation.communitylab.infrastructure.adapters.out.ai;

import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

// Why this class exists: AnalyzeMessageLlmAdapter mixed two jobs — LLM configuration
// and wire-format hygiene for raw model output. All string-level handling
// lives here (no business rules); the service keeps only prompt/options/call/
// retry. A Spring bean so the service receives it by injection; domain stays
// untouched (R3): this is provider wire format, not business policy.
@Slf4j
@Component
public class LlmOutputSanitizer {

    private final BeanOutputConverter<ResponseModel> converter;

    public LlmOutputSanitizer() {
        this(new BeanOutputConverter<>(ResponseModel.class, lenientMapper()));
    }

    // Why package-visible: tests inject a converter; the service uses the default.
    LlmOutputSanitizer(BeanOutputConverter<ResponseModel> converter) {
        this.converter = converter;
    }

    // Mapper for response of the model.
    private static JsonMapper lenientMapper() {
        return JsonMapper.builder()
                .addModules(JacksonUtils.instantiateAvailableModules())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .build();
    }

    // Schema text appended to the system prompt (moved with its converter).
    public String schemaFormat() {
        return converter.getFormat();
    }

    // Full pipeline: fences off, exact object cut, typed conversion.
    // Why first parseable candidate wins: chatter braces ({vale}, {fin}) also
    // balance-scan cleanly but are not JSON — only a real parse proves the cut.
    // Candidates run outer-first so the true object beats any nested segment;
    // total failure preserves the old semantics (fingerprint + rethrow).
    // On parse failure logs the structural fingerprint (no content, Q3) and rethrows.
    public ResponseModel convert(String raw) {
        String text = stripOuterFences(raw);
        RuntimeException last = null;
        for (int start = nextBrace(text, 0); start >= 0; start = nextBrace(text, start + 1)) {
            String candidate = balancedFrom(text, start);
            if (candidate == null) {
                continue;
            }
            try {
                return converter.convert(candidate);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        try {
            // No brace at all (or null): let the converter report it as before.
            return converter.convert(text);
        } catch (RuntimeException e) {
            log.debug("llm raw fingerprint: {}", fingerprint(raw));
            throw last != null ? last : e;
        }
    }

    private static int nextBrace(String text, int from) {
        if (text == null) {
            return -1;
        }
        return text.indexOf('{', from);
    }

    // Composed hygiene step, exposed for tests.
    public String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        return extractBalancedObject(stripOuterFences(raw));
    }

    // Intern cleaning for ```
    static String stripOuterFences(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int nl = text.indexOf('\n');
        String firstLine = nl < 0 ? text : text.substring(0, nl);
        String rest = nl < 0 ? "" : text.substring(nl + 1);
        String first = firstLine.replaceFirst("^```[a-zA-Z]*", "");
        String combined = rest.isEmpty() ? first : (first + "\n" + rest);
        String stripped = combined.strip();
        // Why lastIndexOf instead of endsWith: providers also close the fence
        // on the same line or leave trailing chatter after it; anything from
        // the last fence on is wrapper, never payload (fences are forbidden
        // inside values by the prompt, so payload cannot contain ```).
        int close = stripped.lastIndexOf("```");
        if (close >= 0) {
            stripped = stripped.substring(0, close).stripTrailing();
        }
        return stripped;
    }

    // Why balance-aware scan instead of first-{ to last-}: trailing chatter
    // containing braces (e.g. "...y eso es todo {fin}") used to get swallowed
    // into the payload, and braces inside string values confused the cut.
    // Strings and backslash-escapes are respected; an unclosed object (real
    // truncation) is returned as-is so the converter reports it with its
    // fingerprint, preserving the previous failure semantics.
    static String extractBalancedObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }
        String segment = balancedFrom(text, start);
        return segment != null ? segment : text;
    }

    // Balanced scan from a given start; null when never closed (truncation).
    private static String balancedFrom(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }


    // Why a structural fingerprint instead of logging raw: Q3 forbids content in
    // logs, but blind retries teach nothing. Counts and parity classify the failure
    // (odd quotes = unescaped text, unbalanced braces = truncation, non-brace
    // first char = preamble) without leaking a single word of content.
    static String fingerprint(String raw) {
        if (raw == null) {
            return "raw=null";
        }
        int braces = 0;
        int quotes = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') {
                braces++;
            } else if (c == '}') {
                braces--;
            } else if (c == '"') {
                quotes++;
            }
        }
        String stripped = raw.strip();
        char first = stripped.isEmpty() ? '?' : stripped.charAt(0);
        return "len=" + raw.length() + " braceBalance=" + braces
                + " quotesParity=" + (quotes % 2 == 0 ? "even" : "odd")
                + " first=" + first + " endsBrace=" + stripped.endsWith("}");
    }
}
