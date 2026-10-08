package com.nocountry.simulation.communitylab.application.dtos.batch;

import java.util.List;

public record CommunitySummaryDto(
        int total_interacciones_procesadas,
        String sentimiento_predominante,
        List<String> temas_principales
) {
    public CommunitySummaryDto {
        if (temas_principales == null) {
            temas_principales = List.of();
        }
    }
}
