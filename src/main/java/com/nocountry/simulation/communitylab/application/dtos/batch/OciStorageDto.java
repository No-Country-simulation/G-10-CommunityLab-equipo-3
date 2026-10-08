package com.nocountry.simulation.communitylab.application.dtos.batch;

public record OciStorageDto(
        String bucket,
        String ruta_objeto,
        String status
) {}
