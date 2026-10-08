package com.nocountry.simulation.communitylab.infrastructure.adapters.out.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXResponse;
import com.nocountry.simulation.communitylab.application.port.out.SocialPublisherPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class XPublisherAdapter implements SocialPublisherPort {

    private final String apiKey;
    private final String apiSecret;
    private final String accessToken;
    private final String accessSecret;
    private final String bearerToken;
    private final String apiUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public XPublisherAdapter(
            @Value("${x.api.key:}") String apiKey,
            @Value("${x.api.secret:}") String apiSecret,
            @Value("${x.access.token:}") String accessToken,
            @Value("${x.access.secret:}") String accessSecret,
            @Value("${x.bearer.token:}") String bearerToken,
            @Value("${x.api.url:https://api.twitter.com/2/tweets}") String apiUrl,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this(apiKey, apiSecret, accessToken, accessSecret, bearerToken, apiUrl, HttpClient.newHttpClient(), objectMapper != null ? objectMapper : new ObjectMapper());
    }

    public XPublisherAdapter(
            String apiKey,
            String apiSecret,
            String accessToken,
            String accessSecret,
            String bearerToken,
            String apiUrl,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.apiSecret = apiSecret != null ? apiSecret.trim() : "";
        this.accessToken = accessToken != null ? accessToken.trim() : "";
        this.accessSecret = accessSecret != null ? accessSecret.trim() : "";
        this.bearerToken = bearerToken != null ? bearerToken.trim() : "";
        this.apiUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl.trim() : "https://api.twitter.com/2/tweets";
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public boolean isConfigured() {
        boolean hasOAuth1 = !apiKey.isEmpty() && !apiSecret.isEmpty() && !accessToken.isEmpty() && !accessSecret.isEmpty();
        boolean hasBearer = !bearerToken.isEmpty();
        return hasOAuth1 || hasBearer;
    }

    @Override
    public PublishToXResponse publishToX(String text) {
        if (!isConfigured()) {
            String mockId = "mock-" + UUID.randomUUID().toString().substring(0, 8);
            String mockUrl = "https://x.com/communitylab/status/" + mockId;
            log.warn("[X/Twitter MOCK] Credenciales de X no configuradas. Simulación exitosa de post: '{}'", text);
            return new PublishToXResponse(
                    "simulado",
                    mockId,
                    mockUrl,
                    "Publicación simulada exitosamente. Configure X_API_KEY, X_API_SECRET, X_ACCESS_TOKEN, X_ACCESS_SECRET para publicar en vivo en X."
            );
        }

        try {
            String authHeader = resolveAuthorizationHeader();
            String jsonPayload = objectMapper.writeValueAsString(Map.of("text", text));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            String responseBody = response.body();

            if (statusCode == 201 || statusCode == 200) {
                JsonNode root = objectMapper.readTree(responseBody);
                String tweetId = root.path("data").path("id").asText();
                String publishedUrl = "https://x.com/i/status/" + tweetId;
                log.info("Tweet publicado exitosamente en X. Tweet ID: {}, URL: {}", tweetId, publishedUrl);
                return new PublishToXResponse(
                        "publicado",
                        tweetId,
                        publishedUrl,
                        "Tweet publicado exitosamente en X (Twitter)"
                );
            } else {
                log.error("Fallo al publicar en X API v2. Status: {}, Respuesta: {}", statusCode, responseBody);
                String detail = extractErrorDetail(responseBody, statusCode);
                return new PublishToXResponse(
                        "error",
                        null,
                        null,
                        "Error de X API (HTTP " + statusCode + "): " + detail
                );
            }
        } catch (Exception e) {
            log.error("Excepción inesperada al publicar en X: {}", e.getMessage(), e);
            return new PublishToXResponse(
                    "error",
                    null,
                    null,
                    "Error de red o comunicación con X: " + e.getMessage()
            );
        }
    }

    private String resolveAuthorizationHeader() {
        if (!apiKey.isEmpty() && !apiSecret.isEmpty() && !accessToken.isEmpty() && !accessSecret.isEmpty()) {
            return OAuth1Helper.buildAuthorizationHeader(
                    "POST",
                    apiUrl,
                    apiKey,
                    apiSecret,
                    accessToken,
                    accessSecret
            );
        }
        return "Bearer " + bearerToken;
    }

    private String extractErrorDetail(String responseBody, int statusCode) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("detail")) {
                return root.get("detail").asText();
            }
            if (root.has("title")) {
                return root.get("title").asText();
            }
            if (root.has("errors") && root.get("errors").isArray() && !root.get("errors").isEmpty()) {
                return root.get("errors").get(0).path("message").asText();
            }
        } catch (Exception ignored) {
            // fallback al body raw o código
        }
        return (responseBody != null && !responseBody.isBlank()) ? responseBody : ("HTTP " + statusCode);
    }
}
