package com.nocountry.simulation.communitylab.infrastructure.adapters.out.social;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

public final class OAuth1Helper {

    private OAuth1Helper() {}

    public static String buildAuthorizationHeader(
            String httpMethod,
            String url,
            String consumerKey,
            String consumerSecret,
            String accessToken,
            String tokenSecret
    ) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        Map<String, String> oauthParams = new TreeMap<>();
        oauthParams.put("oauth_consumer_key", consumerKey);
        oauthParams.put("oauth_nonce", nonce);
        oauthParams.put("oauth_signature_method", "HMAC-SHA1");
        oauthParams.put("oauth_timestamp", timestamp);
        oauthParams.put("oauth_token", accessToken);
        oauthParams.put("oauth_version", "1.0");

        String paramString = oauthParams.entrySet().stream()
                .map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue()))
                .collect(Collectors.joining("&"));

        String signatureBase = httpMethod.toUpperCase() + "&" +
                percentEncode(url) + "&" +
                percentEncode(paramString);

        String signingKey = percentEncode(consumerSecret) + "&" + percentEncode(tokenSecret);

        String signature = computeHmacSha1(signatureBase, signingKey);
        oauthParams.put("oauth_signature", signature);

        return "OAuth " + oauthParams.entrySet().stream()
                .map(e -> percentEncode(e.getKey()) + "=\"" + percentEncode(e.getValue()) + "\"")
                .collect(Collectors.joining(", "));
    }

    public static String percentEncode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String computeHmacSha1(String data, String key) {
        try {
            SecretKeySpec signingKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(signingKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Error al calcular firma HMAC-SHA1 para OAuth 1.0a", e);
        }
    }
}
