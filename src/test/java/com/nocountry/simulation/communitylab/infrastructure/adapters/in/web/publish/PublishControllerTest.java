package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.publish;

import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXRequest;
import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXResponse;
import com.nocountry.simulation.communitylab.application.port.in.PublishToXUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublishControllerTest")
class PublishControllerTest {

    @Mock
    private PublishToXUseCase publishToXUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PublishController controller = new PublishController(publishToXUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Given valid tweet, when POST /api/v1/publish/x, returns 200 OK with published details")
    void givenValidTweet_whenPostPublishX_returns200() throws Exception {
        PublishToXResponse response = new PublishToXResponse(
                "publicado",
                "1843657389201948672",
                "https://x.com/i/status/1843657389201948672",
                "Tweet publicado exitosamente en X (Twitter)"
        );

        when(publishToXUseCase.publish(any(PublishToXRequest.class))).thenReturn(response);

        String jsonPayload = """
                {
                  "text": "Nuevo post de la comunidad #OracleCloud #ONE"
                }
                """;

        mockMvc.perform(post("/api/v1/publish/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("publicado"))
                .andExpect(jsonPath("$.tweetId").value("1843657389201948672"))
                .andExpect(jsonPath("$.url").value("https://x.com/i/status/1843657389201948672"));

        verify(publishToXUseCase).publish(any(PublishToXRequest.class));
    }

    @Test
    @DisplayName("Given empty text, when POST /api/v1/publish/x, returns 400 Bad Request")
    void givenEmptyText_whenPostPublishX_returns400() throws Exception {
        String jsonPayload = """
                {
                  "text": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/publish/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.mensaje").value("El texto del tweet no puede estar vacío"));

        verifyNoInteractions(publishToXUseCase);
    }

    @Test
    @DisplayName("Given text exceeding 280 chars, when POST /api/v1/publish/x, returns 400 Bad Request")
    void givenOver280Chars_whenPostPublishX_returns400() throws Exception {
        String longText = "A".repeat(285);
        String jsonPayload = "{\"text\":\"" + longText + "\"}";

        mockMvc.perform(post("/api/v1/publish/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.mensaje").value("El texto excede el límite de 280 caracteres (longitud: 285)"));

        verifyNoInteractions(publishToXUseCase);
    }

    @Test
    @DisplayName("Given service returns error status, when POST /api/v1/publish/x, returns 502 Bad Gateway")
    void givenServiceError_whenPostPublishX_returns502() throws Exception {
        PublishToXResponse errorResponse = new PublishToXResponse(
                "error",
                null,
                null,
                "Error de X API (HTTP 403): Duplicate content"
        );

        when(publishToXUseCase.publish(any(PublishToXRequest.class))).thenReturn(errorResponse);

        String jsonPayload = """
                {
                  "text": "Tweet duplicado"
                }
                """;

        mockMvc.perform(post("/api/v1/publish/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("Duplicate content")));
    }
}
