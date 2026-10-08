package com.nocountry.simulation.communitylab.application.dtos.publish;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Solicitud para publicar un tweet en X (Twitter)")
public record PublishToXRequest(
        @Schema(description = "Texto del tweet (máximo 280 caracteres)", example = "¡Orgullosos de nuestra comunidad de IA y Cloud! 🚀 #TalentosTech #OracleCloud")
        String text
) {}
