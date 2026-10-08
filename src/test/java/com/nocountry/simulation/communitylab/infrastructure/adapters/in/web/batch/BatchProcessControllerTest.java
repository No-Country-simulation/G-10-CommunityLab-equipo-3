package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.batch;

import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessRequest;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessResponse;
import com.nocountry.simulation.communitylab.application.dtos.batch.CommunitySummaryDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.FaqSuggestionDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.GeneratedAssetsDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.LinkedInPostDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.NewsletterHighlightDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.OciStorageDto;
import com.nocountry.simulation.communitylab.application.port.in.BatchProcessUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("BatchProcessControllerTest")
class BatchProcessControllerTest {

    @Mock
    private BatchProcessUseCase batchProcessUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BatchProcessController controller = new BatchProcessController(batchProcessUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Given valid batch payload, when POST /api/v1/interactions/process, returns 200 OK with expected JSON")
    void givenBatchPayload_whenPostInteractionsProcess_returns200() throws Exception {
        BatchProcessResponse expectedResponse = new BatchProcessResponse(
                "exito",
                new CommunitySummaryDto(2, "Altamente Positivo", List.of("Logros", "LangGraph")),
                new GeneratedAssetsDto(
                        new LinkedInPostDto("Titulo", "Copy", "LinkedIn Oficial", "Alto"),
                        new com.nocountry.simulation.communitylab.application.dtos.batch.PostXDto("Post X", List.of("#ONE"), 6, "X (Twitter)"),
                        new NewsletterHighlightDto("Logro", "Titular", "Resumen"),
                        new FaqSuggestionDto("FAQ", "Origen", "status")
                ),
                new OciStorageDto("communitylab-activos-marketing", "activos/semana_04/paquete.json", "guardado_con_exito")
        );

        when(batchProcessUseCase.process(any(BatchProcessRequest.class))).thenReturn(expectedResponse);

        String jsonPayload = """
                {
                  "origen_comunidad": "Discord_Grupo_ONE_G10",
                  "periodo_referencia": "Semana_04",
                  "interacciones": [
                    {
                      "autor": "Mariana Souza",
                      "canal": "#logros-y-empleos",
                      "tipo": "testimonio",
                      "texto": "Comunidad, quede seleccionada para el puesto de Desarrolladora Junior de IA!"
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/interactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("exito"))
                .andExpect(jsonPath("$.resumen_comunidad.total_interacciones_procesadas").value(2))
                .andExpect(jsonPath("$.activos_distribucion_generados.post_linkedin.titulo").value("Titulo"))
                .andExpect(jsonPath("$.activos_distribucion_generados.post_x.copy").value("Post X"))
                .andExpect(jsonPath("$.activos_distribucion_generados.post_x.caracteres").value(6))
                .andExpect(jsonPath("$.almacenamiento_oci.bucket").value("communitylab-activos-marketing"))
                .andExpect(jsonPath("$.almacenamiento_oci.status").value("guardado_con_exito"));

        verify(batchProcessUseCase).process(any(BatchProcessRequest.class));
    }

    @Test
    @DisplayName("Given valid batch payload, when POST /api/v1/batch/process (alias), returns 200 OK")
    void givenBatchPayload_whenPostBatchProcessAlias_returns200() throws Exception {
        BatchProcessResponse expectedResponse = new BatchProcessResponse(
                "exito",
                new CommunitySummaryDto(1, "Positivo", List.of()),
                new GeneratedAssetsDto(null, null, null, null),
                new OciStorageDto("bucket", "path", "guardado_con_exito")
        );

        when(batchProcessUseCase.process(any(BatchProcessRequest.class))).thenReturn(expectedResponse);

        String jsonPayload = """
                {
                  "origen_comunidad": "Discord",
                  "periodo_referencia": "Semana_01",
                  "interacciones": []
                }
                """;

        mockMvc.perform(post("/api/v1/batch/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("exito"));
    }
}
