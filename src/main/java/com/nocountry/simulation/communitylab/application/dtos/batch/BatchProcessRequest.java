package com.nocountry.simulation.communitylab.application.dtos.batch;

import java.util.List;

public record BatchProcessRequest(
        String origen_comunidad,
        String periodo_referencia,
        List<BatchInteractionDto> interacciones
) {
    public BatchProcessRequest {
        if (interacciones == null) {
            interacciones = List.of();
        }
    }
}
