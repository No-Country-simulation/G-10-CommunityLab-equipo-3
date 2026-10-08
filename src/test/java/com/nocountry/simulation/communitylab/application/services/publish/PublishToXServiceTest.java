package com.nocountry.simulation.communitylab.application.services.publish;

import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXRequest;
import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXResponse;
import com.nocountry.simulation.communitylab.application.port.out.SocialPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublishToXServiceTest")
class PublishToXServiceTest {

    @Mock
    private SocialPublisherPort socialPublisherPort;

    private PublishToXService service;

    @BeforeEach
    void setUp() {
        service = new PublishToXService(socialPublisherPort);
    }

    @Test
    @DisplayName("Given valid tweet under 280 characters, delegates to publisher port")
    void givenValidTweet_whenPublish_delegatesToPort() {
        String tweetText = "¡Felicidades a los graduados del programa ONE! Gran logro de la comunidad. #ONE #TalentosTech";
        PublishToXResponse expectedResponse = new PublishToXResponse(
                "publicado",
                "123456",
                "https://x.com/i/status/123456",
                "Tweet publicado exitosamente"
        );

        when(socialPublisherPort.publishToX(tweetText)).thenReturn(expectedResponse);

        PublishToXResponse response = service.publish(new PublishToXRequest(tweetText));

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("publicado");
        assertThat(response.tweetId()).isEqualTo("123456");
        assertThat(response.url()).isEqualTo("https://x.com/i/status/123456");

        verify(socialPublisherPort).publishToX(tweetText);
    }

    @Test
    @DisplayName("Given blank tweet text, throws IllegalArgumentException")
    void givenBlankTweet_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.publish(new PublishToXRequest("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no puede estar vacío");
    }

    @Test
    @DisplayName("Given null request, throws IllegalArgumentException")
    void givenNullRequest_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.publish(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no puede estar vacío");
    }

    @Test
    @DisplayName("Given tweet text exceeding 280 characters, throws IllegalArgumentException")
    void givenTweetOver280Chars_throwsIllegalArgumentException() {
        String longText = "A".repeat(281);

        assertThatThrownBy(() -> service.publish(new PublishToXRequest(longText)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excede el límite de 280 caracteres");
    }
}
