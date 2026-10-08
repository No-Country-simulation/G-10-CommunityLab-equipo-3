package com.nocountry.simulation.communitylab.infrastructure.adapters.out.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("XPublisherAdapterTest")
class XPublisherAdapterTest {

    @Mock
    private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Given missing credentials, isConfigured returns false and publishToX returns mock simulation response")
    void givenMissingCredentials_returnsSimulationResponse() {
        XPublisherAdapter adapter = new XPublisherAdapter(
                "", "", "", "", "", "https://api.twitter.com/2/tweets",
                httpClient, objectMapper
        );

        assertThat(adapter.isConfigured()).isFalse();

        PublishToXResponse response = adapter.publishToX("Tweet de prueba para simulación");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("simulado");
        assertThat(response.tweetId()).startsWith("mock-");
        assertThat(response.url()).contains("https://x.com/communitylab/status/mock-");
        assertThat(response.mensaje()).contains("simulada exitosamente");
    }

    @Test
    @DisplayName("Given valid credentials and successful 201 response from Twitter API, returns publicado with tweetId")
    void givenValidCredentialsAnd201_returnsPublicado() throws Exception {
        XPublisherAdapter adapter = new XPublisherAdapter(
                "apiKey", "apiSecret", "accessToken", "accessSecret", "",
                "https://api.twitter.com/2/tweets",
                httpClient, objectMapper
        );

        assertThat(adapter.isConfigured()).isTrue();

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(201);
        when(mockResponse.body()).thenReturn("""
                {
                  "data": {
                    "id": "1843657389201948672",
                    "text": "Tweet de prueba en vivo"
                  }
                }
                """);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        PublishToXResponse response = adapter.publishToX("Tweet de prueba en vivo");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("publicado");
        assertThat(response.tweetId()).isEqualTo("1843657389201948672");
        assertThat(response.url()).isEqualTo("https://x.com/i/status/1843657389201948672");
        assertThat(response.mensaje()).contains("exitosamente");
    }

    @Test
    @DisplayName("Given valid credentials but 403 Forbidden from Twitter API, returns error response with detail")
    void givenApiError403_returnsErrorResponse() throws Exception {
        XPublisherAdapter adapter = new XPublisherAdapter(
                "apiKey", "apiSecret", "accessToken", "accessSecret", "",
                "https://api.twitter.com/2/tweets",
                httpClient, objectMapper
        );

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(403);
        when(mockResponse.body()).thenReturn("""
                {
                  "title": "Forbidden",
                  "detail": "You are not allowed to create a Tweet with duplicate content."
                }
                """);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        PublishToXResponse response = adapter.publishToX("Tweet duplicado");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.tweetId()).isNull();
        assertThat(response.url()).isNull();
        assertThat(response.mensaje()).contains("duplicate content");
    }

    @Test
    @DisplayName("Given network failure when calling Twitter API, returns error response gracefully")
    void givenNetworkException_returnsErrorResponse() throws Exception {
        XPublisherAdapter adapter = new XPublisherAdapter(
                "apiKey", "apiSecret", "accessToken", "accessSecret", "",
                "https://api.twitter.com/2/tweets",
                httpClient, objectMapper
        );

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection timed out"));

        PublishToXResponse response = adapter.publishToX("Tweet con error de red");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.mensaje()).contains("Connection timed out");
    }
}
