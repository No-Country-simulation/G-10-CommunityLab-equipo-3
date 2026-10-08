package com.nocountry.simulation.communitylab.infrastructure.adapters.out.social;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuth1HelperTest")
class OAuth1HelperTest {

    @Test
    @DisplayName("Given valid parameters, builds valid RFC 5849 OAuth header")
    void givenValidParams_buildsValidOAuthHeader() {
        String header = OAuth1Helper.buildAuthorizationHeader(
                "POST",
                "https://api.twitter.com/2/tweets",
                "consumerKey123",
                "consumerSecret456",
                "accessToken789",
                "tokenSecretABC"
        );

        assertThat(header).startsWith("OAuth ");
        assertThat(header).contains("oauth_consumer_key=\"consumerKey123\"");
        assertThat(header).contains("oauth_signature_method=\"HMAC-SHA1\"");
        assertThat(header).contains("oauth_token=\"accessToken789\"");
        assertThat(header).contains("oauth_version=\"1.0\"");
        assertThat(header).contains("oauth_signature=");
        assertThat(header).contains("oauth_nonce=");
        assertThat(header).contains("oauth_timestamp=");
    }

    @Test
    @DisplayName("Percent encodes special characters according to RFC 3986")
    void testPercentEncode() {
        assertThat(OAuth1Helper.percentEncode("hello world")).isEqualTo("hello%20world");
        assertThat(OAuth1Helper.percentEncode("a*b~c")).isEqualTo("a%2Ab~c");
        assertThat(OAuth1Helper.percentEncode("test@example.com")).isEqualTo("test%40example.com");
    }
}
