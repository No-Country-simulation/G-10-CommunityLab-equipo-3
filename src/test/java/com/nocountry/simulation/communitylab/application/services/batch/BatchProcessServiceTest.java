package com.nocountry.simulation.communitylab.application.services.batch;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchInteractionDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessRequest;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessResponse;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BatchProcessServiceTest")
class BatchProcessServiceTest {

    @Mock
    private RequestToLLMProcess llm;

    private BatchProcessService service;

    @BeforeEach
    void setUp() {
        service = new BatchProcessService(llm, "communitylab-activos-marketing");
    }

    @Test
    @DisplayName("Given batch with achievement and question, when processed, returns structured response conforming to hackathon specification")
    void givenBatch_whenProcessed_returnsStructuredResponse() throws Exception {
        // Given: Mariana (logro) and Lucas (duda)
        BatchInteractionDto mariana = new BatchInteractionDto(
                "Mariana Souza",
                "#logros-y-empleos",
                "testimonio",
                "Comunidad, quede seleccionada para el puesto de Desarrolladora Junior de IA!"
        );

        BatchInteractionDto lucas = new BatchInteractionDto(
                "Lucas Albuquerque",
                "#dudas-langgraph",
                "pregunta_tecnica",
                "Tengo dudas sobre como estructurar los nodos condicionales en LangGraph."
        );

        BatchProcessRequest request = new BatchProcessRequest(
                "Discord_Grupo_ONE_G10",
                "Semana_04",
                List.of(mariana, lucas)
        );

        ResponseModel marianaModel = new ResponseModel(
                mariana.texto(),
                Language.ES,
                Sentiment.POSITIVO,
                MessageType.LOGRO,
                List.of("empleo", "ia", "contratacion"),
                95,
                Channels.LINKEDIN,
                "De la Comunidad al Mercado: El impacto de los proyectos practicos de IA",
                "Celebramos a Mariana por su nuevo empleo como Desarrolladora Junior de IA!",
                List.of("#TalentosTech", "#OracleCloud"),
                "Comparte tu historia en #logros"
        );

        ResponseModel lucasModel = new ResponseModel(
                lucas.texto(),
                Language.ES,
                Sentiment.NEUTRAL,
                MessageType.DUDA,
                List.of("langgraph", "nodos"),
                70,
                Channels.FAQ,
                "Tip Rapido: Como crear nodos de reintento en LangGraph",
                "Como estructurar los nodos condicionales en LangGraph cuando el LLM necesita reintento?",
                List.of("#LangGraph", "#FAQ"),
                null
        );

        when(llm.processMessage(any(RequestToLLM.class)))
                .thenReturn(marianaModel)
                .thenReturn(lucasModel);

        // When
        BatchProcessResponse response = service.process(request);

        // Then
        assertThat(response.status()).isEqualTo("exito");
        assertThat(response.resumen_comunidad().total_interacciones_procesadas()).isEqualTo(2);
        assertThat(response.resumen_comunidad().sentimiento_predominante()).isEqualTo("Altamente Positivo");
        assertThat(response.resumen_comunidad().temas_principales()).contains("empleo", "ia", "langgraph");

        // Validate assets
        assertThat(response.activos_distribucion_generados().post_linkedin()).isNotNull();
        assertThat(response.activos_distribucion_generados().post_linkedin().titulo()).contains("De la Comunidad al Mercado");
        assertThat(response.activos_distribucion_generados().post_x()).isNotNull();
        assertThat(response.activos_distribucion_generados().post_x().caracteres()).isLessThanOrEqualTo(280);
        assertThat(response.activos_distribucion_generados().post_x().copy()).isNotBlank();
        assertThat(response.activos_distribucion_generados().post_x().canal_recomendado()).isEqualTo("X (Twitter)");
        assertThat(response.activos_distribucion_generados().destaque_newsletter_semanal()).isNotNull();
        assertThat(response.activos_distribucion_generados().sugerencia_contenido_faq()).isNotNull();
        assertThat(response.activos_distribucion_generados().sugerencia_contenido_faq().tema()).contains("LangGraph");

        // Validate OCI
        assertThat(response.almacenamiento_oci().bucket()).isEqualTo("communitylab-activos-marketing");
        assertThat(response.almacenamiento_oci().ruta_objeto()).isEqualTo("activos/semana_04/paquete-distribucion.json");
        assertThat(response.almacenamiento_oci().status()).isEqualTo("guardado_con_exito");
    }

    @Test
    @DisplayName("Given empty request, when processed, returns safe fallback response")
    void givenEmptyRequest_returnsFallbackResponse() {
        BatchProcessRequest emptyRequest = new BatchProcessRequest("Discord", "Semana_01", List.of());

        BatchProcessResponse response = service.process(emptyRequest);

        assertThat(response.status()).isEqualTo("exito");
        assertThat(response.resumen_comunidad().total_interacciones_procesadas()).isEqualTo(0);
        assertThat(response.activos_distribucion_generados().post_linkedin()).isNotNull();
        assertThat(response.almacenamiento_oci().status()).isEqualTo("guardado_con_exito");
    }

    @Test
    @DisplayName("Given LLM failure, when processed, gracefully falls back without throwing")
    void givenLlmFailure_gracefullyRecovers() throws Exception {
        BatchInteractionDto item = new BatchInteractionDto("Autor", "#general", "testimonio", "Texto");
        BatchProcessRequest request = new BatchProcessRequest("Discord", "Semana_01", List.of(item));

        when(llm.processMessage(any(RequestToLLM.class))).thenThrow(new RuntimeException("LLM timeout"));

        BatchProcessResponse response = service.process(request);

        assertThat(response.status()).isEqualTo("exito");
        assertThat(response.resumen_comunidad().total_interacciones_procesadas()).isEqualTo(1);
    }
}
