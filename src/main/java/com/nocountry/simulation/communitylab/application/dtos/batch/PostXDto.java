package com.nocountry.simulation.communitylab.application.dtos.batch;

import java.util.List;

public record PostXDto(
        String copy,
        List<String> hashtags,
        int caracteres,
        String canal_recomendado
) {}
