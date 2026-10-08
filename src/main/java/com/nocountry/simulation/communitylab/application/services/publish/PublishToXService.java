package com.nocountry.simulation.communitylab.application.services.publish;

import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXRequest;
import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXResponse;
import com.nocountry.simulation.communitylab.application.port.in.PublishToXUseCase;
import com.nocountry.simulation.communitylab.application.port.out.SocialPublisherPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PublishToXService implements PublishToXUseCase {

    private final SocialPublisherPort socialPublisherPort;

    @Override
    public PublishToXResponse publish(PublishToXRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("El texto del tweet no puede estar vacío");
        }

        String text = request.text().trim();
        if (text.length() > 280) {
            throw new IllegalArgumentException(
                    "El texto del tweet excede el límite de 280 caracteres de X (longitud actual: " + text.length() + ")"
            );
        }

        log.info("Iniciando publicación en X (longitud: {} caracteres)", text.length());
        return socialPublisherPort.publishToX(text);
    }
}
