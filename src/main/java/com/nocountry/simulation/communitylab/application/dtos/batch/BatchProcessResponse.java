package com.nocountry.simulation.communitylab.application.dtos.batch;

public record BatchProcessResponse(
        String status,
        CommunitySummaryDto resumen_comunidad,
        GeneratedAssetsDto activos_distribucion_generados,
        OciStorageDto almacenamiento_oci
) {}
