package com.nocountry.simulation.communitylab.application.services.history;

import com.nocountry.simulation.communitylab.application.dtos.History;
import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.application.port.in.LoadHistoryUseCase;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.application.port.out.PackageIndex;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoadHistoryService implements LoadHistoryUseCase {

    private final BufferPort bufferPort;
    private final PackageIndex packageIndex;

    @Override
    public History load(Source source) {
        try{
          List<EnrichedComment> open = bufferPort.readOpen(source);
          List<EnrichedComment> sealed = new ArrayList<>();
          for (String batchId : bufferPort.pendingBatches(source)){
              sealed.addAll(bufferPort.readSealed(source, batchId));
          }
            List<PackageCompleted> packages = packageIndex.active(source, Instant.now());

            List<ResponseClient> pending = new ArrayList<>();

            for (EnrichedComment comment : sealed) {
                pending.add(ResponseClient.from(comment));
            }

            for (EnrichedComment comment : open) {
                pending.add(ResponseClient.from(comment));
            }
            return new History(packages, pending);
        } catch (RuntimeException e){
            log.warn("history load failed: source={} cause={}", source, e.getClass().getSimpleName());
            return History.empty();
        }
    }
}
