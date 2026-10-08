package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.batch;

import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessRequest;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessResponse;
import com.nocountry.simulation.communitylab.application.port.in.BatchProcessUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Batch Ingestion", description = "Endpoint oficial de ingesta por lote, análisis con IA y generación de activos para el Hackathon ONE.")
@RestController
@RequiredArgsConstructor
@Slf4j
public class BatchProcessController {

    private final BatchProcessUseCase batchProcessUseCase;

    @Operation(
            summary = "Procesamiento de Actividad y Generación de Activos (MVP Hackathon)",
            description = """
                    Recibe un conjunto de mensajes/interacciones de la comunidad (simulados o reales) y retorna \
                    el análisis consolidado con sentimiento y temas, acompañado de los activos de distribución listos \
                    para su uso (LinkedIn, Newsletter, FAQ) y el estado de persistencia en OCI Object Storage.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Lote procesado exitosamente conforme a la especificación oficial del Hackathon",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BatchProcessResponse.class),
                    examples = @ExampleObject(
                            name = "RespuestaOficialHackathon",
                            summary = "Ejemplo oficial de salida estructurada (Págs. 4 y 5 del PDF)",
                            value = """
                                    {
                                      "status": "exito",
                                      "resumen_comunidad": {
                                        "total_interacciones_procesadas": 2,
                                        "sentimiento_predominante": "Altamente Positivo",
                                        "temas_principales": [
                                          "Contratacion / Logros",
                                          "LangGraph / Nodos Condicionales"
                                        ]
                                      },
                                      "activos_distribucion_generados": {
                                        "post_linkedin": {
                                          "titulo": "De la Comunidad al Mercado: El impacto de los proyectos practicos de IA",
                                          "copy": "Nada nos da mas orgullo que ver a nuestros talentos conquistando el mercado de tecnologia! 🚀\\nNuestra estudiante Mariana Souza acaba de ser contratada como Desarrolladora Junior de IA tras destacar sus proyectos practicos desarrollados con LangChain y Oracle Cloud Infrastructure.\\n\\n#TalentosTech #InteligenciaArtificial #OracleCloud #CarreraDev",
                                          "canal_recomendado": "LinkedIn Oficial",
                                          "potencial_engagement": "Alto"
                                        },
                                        "destaque_newsletter_semanal": {
                                          "seccion": "Logro de la Semana",
                                          "titular": "Estudiante consigue empleo dev con portfolio de IA en Oracle Cloud",
                                          "resumen": "Mariana Souza obtuvo su primera oportunidad como Dev Jr de IA destacando proyectos desarrollados durante la formacion."
                                        },
                                        "sugerencia_contenido_faq": {
                                          "tema": "Tip Rapido: Como crear nodos de reintento en LangGraph",
                                          "origen": "Duda frecuente planteada por Lucas Albuquerque en el canal de soporte",
                                          "status": "derivado_a_mentoria"
                                        }
                                      },
                                      "almacenamiento_oci": {
                                        "bucket": "communitylab-activos-marketing",
                                        "ruta_objeto": "activos/2026-semana-04/paquete-distribucion.json",
                                        "status": "guardado_con_exito"
                                      }
                                    }
                                    """
                    )
            )
    )
    @PostMapping(
            value = {"/api/v1/interactions/process", "/api/v1/batch/process"},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<BatchProcessResponse> processBatch(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Lote de interacciones según contrato oficial de entrada (Pág. 4)",
                    required = true,
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "EntradaOficialHackathon",
                                    summary = "Ejemplo oficial de solicitud (Pág. 4 del PDF)",
                                    value = """
                                            {
                                              "origen_comunidad": "Discord_Grupo_ONE_G10",
                                              "periodo_referencia": "Semana_04",
                                              "interacciones": [
                                                {
                                                  "autor": "Mariana Souza",
                                                  "canal": "#logros-y-empleos",
                                                  "tipo": "testimonio",
                                                  "texto": "Comunidad, quede seleccionada para el puesto de Desarrolladora Junior de IA! El proyecto del curso de LangChain y OCI que construi en mi portfolio marco toda la diferencia en la entrevista tecnica. Muy agradecida con la comunidad por todo el apoyo!"
                                                },
                                                {
                                                  "autor": "Lucas Albuquerque",
                                                  "canal": "#dudas-langgraph",
                                                  "tipo": "pregunta_tecnica",
                                                  "texto": "Tengo dudas sobre como estructurar los nodos condicionales en LangGraph cuando la respuesta del LLM necesita reintento. Alguien tiene un ejemplo practico de router?"
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody BatchProcessRequest request
    ) {
        log.info("Batch request recibida: origen={}, periodo={}, cantidad={}",
                request.origen_comunidad(),
                request.periodo_referencia(),
                request.interacciones() != null ? request.interacciones().size() : 0);

        BatchProcessResponse response = batchProcessUseCase.process(request);
        return ResponseEntity.ok(response);
    }
}
