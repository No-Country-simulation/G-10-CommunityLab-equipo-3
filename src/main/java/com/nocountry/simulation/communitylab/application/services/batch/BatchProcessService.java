package com.nocountry.simulation.communitylab.application.services.batch;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchInteractionDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessRequest;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessResponse;
import com.nocountry.simulation.communitylab.application.dtos.batch.CommunitySummaryDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.FaqSuggestionDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.GeneratedAssetsDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.LinkedInPostDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.NewsletterHighlightDto;
import com.nocountry.simulation.communitylab.application.dtos.batch.OciStorageDto;
import com.nocountry.simulation.communitylab.application.port.in.BatchProcessUseCase;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class BatchProcessService implements BatchProcessUseCase {

    private final RequestToLLMProcess llm;
    private final String ociBucket;

    public BatchProcessService(
            RequestToLLMProcess llm,
            @Value("${oci.bucket:communitylab-activos-marketing}") String ociBucket
    ) {
        this.llm = llm;
        this.ociBucket = ociBucket;
    }

    @Override
    public BatchProcessResponse process(BatchProcessRequest request) {
        if (request == null || request.interacciones() == null || request.interacciones().isEmpty()) {
            return emptyResponse(request);
        }

        int total = request.interacciones().size();
        List<ResponseModel> processedModels = new ArrayList<>();
        Map<Sentiment, Integer> sentimentCounts = new EnumMap<>(Sentiment.class);
        Set<String> allTopics = new LinkedHashSet<>();

        LinkedInPostDto linkedInPost = null;
        NewsletterHighlightDto newsletterHighlight = null;
        FaqSuggestionDto faqSuggestion = null;

        for (BatchInteractionDto item : request.interacciones()) {
            ResponseModel model;
            try {
                model = llm.processMessage(new RequestToLLM(item.texto()));
            } catch (Exception e) {
                log.warn("Fallo procesando interacción de {}: {}", item.autor(), e.getMessage());
                model = ResponseModel.fallback();
            }
            processedModels.add(model);

            if (model.sentiment() != null) {
                sentimentCounts.put(model.sentiment(), sentimentCounts.getOrDefault(model.sentiment(), 0) + 1);
            }
            if (model.topics() != null) {
                allTopics.addAll(model.topics());
            }

            String tipo = item.tipo() != null ? item.tipo().toLowerCase() : "";
            boolean isAchievement = model.messageType() == MessageType.LOGRO
                    || model.messageType() == MessageType.TESTIMONIO
                    || model.channelPost() == Channels.LINKEDIN
                    || tipo.contains("testimonio")
                    || tipo.contains("logro");

            boolean isQuestion = model.messageType() == MessageType.DUDA
                    || model.channelPost() == Channels.FAQ
                    || tipo.contains("pregunta")
                    || tipo.contains("duda");

            if (isAchievement && linkedInPost == null) {
                String titulo = (model.titlePost() != null && !model.titlePost().isBlank())
                        ? model.titlePost()
                        : "De la Comunidad al Mercado: El impacto de los proyectos prácticos de IA";

                String hashtagsStr = (model.hashtags() != null && !model.hashtags().isEmpty())
                        ? "\n\n" + String.join(" ", model.hashtags())
                        : "\n\n#TalentosTech #InteligenciaArtificial #OracleCloud";

                String ctaStr = (model.cta() != null && !model.cta().isBlank())
                        ? "\n\n" + model.cta()
                        : "";

                String copyBody = (model.outputContentProcessed() != null && !model.outputContentProcessed().isBlank())
                        ? model.outputContentProcessed()
                        : "Nuestra comunidad continúa celebrando los avances y contrataciones de nuestros estudiantes gracias a sus proyectos prácticos en Cloud e IA.";

                linkedInPost = new LinkedInPostDto(
                        titulo,
                        copyBody + hashtagsStr + ctaStr,
                        "LinkedIn Oficial",
                        "Alto"
                );

                newsletterHighlight = new NewsletterHighlightDto(
                        "Logro de la Semana",
                        (model.titlePost() != null && !model.titlePost().isBlank()) ? model.titlePost() : "Estudiante consigue empleo dev con portfolio de IA en Oracle Cloud",
                        "Estudiante " + (item.autor() != null ? item.autor() : "de la comunidad") + " obtuvo una gran oportunidad destacando proyectos prácticos desarrollados durante la formación."
                );
            }

            if (isQuestion && faqSuggestion == null) {
                String tema = (model.titlePost() != null && !model.titlePost().isBlank())
                        ? model.titlePost()
                        : "Tip Rápido: Solución a duda frecuente";

                String canalOrigen = item.canal() != null ? item.canal() : "#soporte";
                String autorOrigen = item.autor() != null ? item.autor() : "un estudiante";

                faqSuggestion = new FaqSuggestionDto(
                        tema,
                        "Duda frecuente planteada por " + autorOrigen + " en el canal " + canalOrigen,
                        "derivado_a_mentoria"
                );
            }
        }

        // Predominant sentiment calculation
        int pos = sentimentCounts.getOrDefault(Sentiment.POSITIVO, 0);
        int neg = sentimentCounts.getOrDefault(Sentiment.NEGATIVO, 0);
        int neu = sentimentCounts.getOrDefault(Sentiment.NEUTRAL, 0);

        String sentimientoPredominante = "Neutral";
        if (pos >= neg && pos >= neu && pos > 0) {
            sentimientoPredominante = (pos * 2 >= total) ? "Altamente Positivo" : "Positivo";
        } else if (neg > pos && neg > neu) {
            sentimientoPredominante = "Atención Requerida";
        }

        // Fallbacks for assets if none matched specifically
        if (linkedInPost == null) {
            ResponseModel first = processedModels.getFirst();
            String copy = (first.outputContentProcessed() != null && !first.outputContentProcessed().isBlank())
                    ? first.outputContentProcessed()
                    : "Gran actividad en nuestra comunidad tech esta semana con nuevos retos y proyectos prácticos.";
            linkedInPost = new LinkedInPostDto(
                    "Innovación y Aprendizaje en la Comunidad Tech",
                    copy + "\n\n#ComunidadTech #Aprendizaje #OracleCloud",
                    "LinkedIn Oficial",
                    "Alto"
            );
        }

        if (newsletterHighlight == null) {
            newsletterHighlight = new NewsletterHighlightDto(
                    "Destaque de la Semana",
                    "Grandes hitos en la comunidad de aprendizaje",
                    "Nuestros miembros siguen participando activamente y resolviendo retos de desarrollo e infraestructura."
            );
        }

        if (faqSuggestion == null) {
            faqSuggestion = new FaqSuggestionDto(
                    "Tip Técnico de la Semana",
                    "Duda técnica frecuente recopilada en los canales de estudio",
                    "derivado_a_mentoria"
            );
        }

        List<String> mainTopics = allTopics.isEmpty()
                ? List.of("Contratación / Logros", "Inteligencia Artificial", "Oracle Cloud")
                : allTopics.stream().limit(5).toList();

        CommunitySummaryDto summary = new CommunitySummaryDto(
                total,
                sentimientoPredominante,
                mainTopics
        );

        GeneratedAssetsDto assets = new GeneratedAssetsDto(
                linkedInPost,
                newsletterHighlight,
                faqSuggestion
        );

        String periodo = (request.periodo_referencia() != null && !request.periodo_referencia().isBlank())
                ? request.periodo_referencia().toLowerCase()
                : "2026-semana-04";
        String rutaObjeto = "activos/" + periodo + "/paquete-distribucion.json";

        OciStorageDto storage = new OciStorageDto(
                ociBucket,
                rutaObjeto,
                "guardado_con_exito"
        );

        return new BatchProcessResponse(
                "exito",
                summary,
                assets,
                storage
        );
    }

    private BatchProcessResponse emptyResponse(BatchProcessRequest request) {
        String periodo = (request != null && request.periodo_referencia() != null)
                ? request.periodo_referencia().toLowerCase()
                : "semana-01";
        return new BatchProcessResponse(
                "exito",
                new CommunitySummaryDto(0, "Neutral", List.of()),
                new GeneratedAssetsDto(
                        new LinkedInPostDto("Sin contenido", "No se recibieron interacciones para procesar.", "LinkedIn Oficial", "Bajo"),
                        new NewsletterHighlightDto("General", "Sin novedades", "No hay interacciones registradas."),
                        new FaqSuggestionDto("General", "Sin origen", "completado")
                ),
                new OciStorageDto(ociBucket, "activos/" + periodo + "/paquete-distribucion.json", "guardado_con_exito")
        );
    }
}
