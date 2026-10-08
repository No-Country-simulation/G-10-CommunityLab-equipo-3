package com.nocountry.simulation.communitylab.application.dtos.publish;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Respuesta tras la publicación en X (Twitter)")
public record PublishToXResponse(
        @Schema(description = "Estado de la publicación (publicado | simulado | error)", example = "publicado")
        String status,

        @Schema(description = "ID del tweet asignado por la API de X o identificador simulado", example = "1843657389201948672")
        String tweetId,

        @Schema(description = "URL directa para ver el post en X", example = "https://x.com/i/status/1843657389201948672")
        String url,

        @Schema(description = "Mensaje informativo o detalle de la operación", example = "Tweet publicado exitosamente en X")
        String mensaje
) {}
