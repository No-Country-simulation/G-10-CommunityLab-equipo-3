package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.publish;

import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXRequest;
import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXResponse;
import com.nocountry.simulation.communitylab.application.port.in.PublishToXUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Social Publishing", description = "Endpoints para la publicación directa de activos en redes sociales (X / Twitter).")
@RestController
@RequestMapping("/api/v1/publish")
@RequiredArgsConstructor
@Slf4j
public class PublishController {

    private final PublishToXUseCase publishToXUseCase;

    @Operation(
            summary = "Publicar un Tweet en X (Twitter)",
            description = """
                    Publica un tweet directamente en la cuenta oficial de X utilizando la API v2 oficial. \
                    Si las credenciales no están configuradas en el entorno, opera en modo simulador para permitir pruebas sin fallas.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Tweet publicado o simulado exitosamente",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PublishToXResponse.class),
                    examples = @ExampleObject(
                            name = "PublicacionExitosa",
                            value = """
                                    {
                                      "status": "publicado",
                                      "tweetId": "1843657389201948672",
                                      "url": "https://x.com/i/status/1843657389201948672",
                                      "mensaje": "Tweet publicado exitosamente en X (Twitter)"
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "Solicitud inválida (texto vacío o excede 280 caracteres)"
    )
    @ApiResponse(
            responseCode = "502",
            description = "Fallo devuelto por la API de X"
    )
    @PostMapping(value = "/x", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PublishToXResponse> publishToX(@RequestBody PublishToXRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(
                    new PublishToXResponse("error", null, null, "El texto del tweet no puede estar vacío")
            );
        }

        String trimmed = request.text().trim();
        if (trimmed.length() > 280) {
            return ResponseEntity.badRequest().body(
                    new PublishToXResponse("error", null, null, "El texto excede el límite de 280 caracteres (longitud: " + trimmed.length() + ")")
            );
        }

        PublishToXResponse response = publishToXUseCase.publish(new PublishToXRequest(trimmed));
        if ("error".equalsIgnoreCase(response.status())) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
