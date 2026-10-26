package com.nocountry.simulation.communitylab.infrastructure.adapters.out.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis index of announced packages, read by late subscribers (snapshot).
 * No Spring context and no live Redis: {@link RedisTemplate} is mocked at the
 * boundary; the {@link ObjectMapper} is real so the JSON round-trip is exercised.
 *
 * <p>Derivado de: spec 008 RF-03 (una entrada por lote, re-flush sustituye, vencidas fuera y borradas,
 * sobrevive al reinicio: vive en Redis), RF-06 (aislamiento por fuente), RF-07 (Redis caído no rompe
 * flush ni stream), RNF-04 (la PAR nunca en logs) + plan 008 §1.1, §2, §3 (HASH {@code packages:{SOURCE}},
 * limpieza al leer, orden por {@code expiresAt}).
 */
@DisplayName("RedisPackageIndexAdapter")
@ExtendWith(OutputCaptureExtension.class)
class RedisPackageIndexAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    // Distinctive token so a leak into the logs cannot be a coincidence.
    private static final String PAR_URL =
            "https://objectstorage.sa-saopaulo-1.oraclecloud.com/p/SECRET-PAR-TOKEN/n/ns/b/MessagesUsers/o/paquetes/telegram/paquete-b-1.json";

    private HashOperations<String, String, String> hashOps;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private RedisPackageIndexAdapter adapter;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        adapter = new RedisPackageIndexAdapter(redisTemplate, objectMapper);
    }

    // ---------- record ----------

    @Test
    @DisplayName("Dado un paquete anunciado, cuando se registra, entonces queda en el HASH de su fuente con el batchId como campo y su PAR legible")
    void recordStoresThePackageUnderItsSourceAndBatchId() {
        // Cuando se registra un paquete Telegram
        adapter.record(pkg("b-1", Source.TELEGRAM, NOW.plusSeconds(3600)));

        // Entonces el campo es el batchId (un re-flush del mismo lote sustituye en vez de duplicar)
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq("packages:TELEGRAM"), eq("b-1"), json.capture());

        // Y el valor guarda lo que el snapshot reenviará como package.completed
        JsonNode stored = objectMapper.readTree(json.getValue());
        assertThat(stored.get("batchId").asString()).isEqualTo("b-1");
        assertThat(stored.get("assetsCount").asInt()).isEqualTo(3);
        assertThat(stored.get("parUrlBase").asString()).isEqualTo(PAR_URL);
        assertThat(stored.get("expiresAt").asString()).isEqualTo("2026-10-14T13:00:00Z");
    }

    @Test
    @DisplayName("Dado Redis caído, cuando se registra, entonces no lanza y el log lleva ids pero nunca la PAR")
    void recordSwallowsRedisFailuresWithoutLeakingThePar(CapturedOutput output) {
        // Dado Redis que no responde
        doThrow(new RedisConnectionFailureException("down")).when(hashOps).put(anyString(), anyString(), anyString());

        // Cuando se registra / Entonces el flush que lo llama no se entera (RF-07)
        assertThatCode(() -> adapter.record(pkg("b-1", Source.TELEGRAM, NOW.plusSeconds(3600))))
                .doesNotThrowAnyException();

        // Y el aviso se puede rastrear sin filtrar el secreto (RNF-04)
        assertThat(output.getOut()).contains("batchId=b-1", "source=TELEGRAM");
        assertThat(output.getOut()).doesNotContain("SECRET-PAR-TOKEN");
    }

    // ---------- active ----------

    @Test
    @DisplayName("Dado varios paquetes vigentes y uno vencido, cuando se leen, entonces vuelven todos los vigentes del más antiguo al más reciente")
    void activeReturnsEveryValidPackageOldestFirst() {
        // Dado el HASH en un orden cualquiera, con tres vigentes y uno vencido
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("b-3", json(pkg("b-3", Source.TELEGRAM, NOW.plusSeconds(300))));
        entries.put("b-1", json(pkg("b-1", Source.TELEGRAM, NOW.plusSeconds(100))));
        entries.put("old", json(pkg("old", Source.TELEGRAM, NOW.minusSeconds(1))));
        entries.put("b-2", json(pkg("b-2", Source.TELEGRAM, NOW.plusSeconds(200))));
        when(hashOps.entries("packages:TELEGRAM")).thenReturn(entries);

        // Cuando se leen los vigentes
        List<PackageCompleted> active = adapter.active(Source.TELEGRAM, NOW);

        // Entonces todos los vigentes (no solo el primero) ordenados por expiresAt, sin el vencido
        assertThat(active).extracting(PackageCompleted::batchId).containsExactly("b-1", "b-2", "b-3");
        assertThat(active.getFirst().parUrlBase()).isEqualTo(PAR_URL);
    }

    @Test
    @DisplayName("Dado un paquete vencido, cuando se leen los vigentes, entonces se borra del HASH y los vigentes se conservan")
    void activeDeletesOnlyExpiredEntries() {
        // Dado un vigente y uno vencido
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("b-1", json(pkg("b-1", Source.TELEGRAM, NOW.plusSeconds(100))));
        entries.put("old", json(pkg("old", Source.TELEGRAM, NOW.minusSeconds(1))));
        when(hashOps.entries("packages:TELEGRAM")).thenReturn(entries);

        // Cuando se leen
        adapter.active(Source.TELEGRAM, NOW);

        // Entonces el índice se limpia solo al leer, sin tocar el vigente
        verify(hashOps).delete("packages:TELEGRAM", "old");
        verify(hashOps, never()).delete("packages:TELEGRAM", "b-1");
    }

    @Test
    @DisplayName("Dado varios paquetes vencidos, cuando se leen los vigentes, entonces se borran todos con un solo HDEL")
    void expiredEntriesAreDeletedInOneCall() {
        // Dado dos vencidos y un vigente
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("old-1", json(pkg("old-1", Source.DISCORD, NOW.minusSeconds(10))));
        entries.put("b-1", json(pkg("b-1", Source.DISCORD, NOW.plusSeconds(100))));
        entries.put("old-2", json(pkg("old-2", Source.DISCORD, NOW.minusSeconds(1))));
        when(hashOps.entries("packages:DISCORD")).thenReturn(entries);

        // Cuando se leen (cada reconexión SSE, spec 010 RF-05)
        adapter.active(Source.DISCORD, NOW);

        // Entonces un único viaje a Redis limpia ambos, sin tocar el vigente
        verify(hashOps, times(1)).delete(eq("packages:DISCORD"), any(Object[].class));
        verify(hashOps).delete("packages:DISCORD", "old-1", "old-2");
    }

    @Test
    @DisplayName("Dado que falla el borrado de vencidos, cuando se leen los vigentes, entonces los vigentes ya leídos se devuelven igual")
    void cleanupFailureKeepsTheActivePackages() {
        // Dado un vigente y un vencido, y Redis fallando solo en el HDEL de limpieza
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("b-1", json(pkg("b-1", Source.TELEGRAM, NOW.plusSeconds(100))));
        entries.put("old", json(pkg("old", Source.TELEGRAM, NOW.minusSeconds(1))));
        when(hashOps.entries("packages:TELEGRAM")).thenReturn(entries);
        when(hashOps.delete(eq("packages:TELEGRAM"), any(Object[].class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

        // Cuando se leen / Entonces el historial SSE sigue mostrando el paquete y su PAR
        assertThat(adapter.active(Source.TELEGRAM, NOW)).extracting(PackageCompleted::batchId).containsExactly("b-1");
    }

    @Test
    @DisplayName("Dado un paquete que vence justo ahora, cuando se leen los vigentes, entonces ya no sale (vigente = expiresAt posterior a ahora)")
    void packageExpiringExactlyNowIsNoLongerValid() {
        // Dado un paquete cuya PAR vence en este instante
        when(hashOps.entries("packages:TELEGRAM"))
                .thenReturn(Map.of("b-1", json(pkg("b-1", Source.TELEGRAM, NOW))));

        // Cuando se leen / Entonces no se envía una PAR que OCI ya rechazaría
        assertThat(adapter.active(Source.TELEGRAM, NOW)).isEmpty();
    }

    @Test
    @DisplayName("Dado una entrada corrupta, cuando se leen los vigentes, entonces se salta sin borrarla y el resto sale igual")
    void corruptEntryIsSkippedNotDeleted(CapturedOutput output) {
        // Dado una entrada ilegible junto a una vigente
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("broken", "{not json");
        entries.put("b-1", json(pkg("b-1", Source.TELEGRAM, NOW.plusSeconds(100))));
        when(hashOps.entries("packages:TELEGRAM")).thenReturn(entries);

        // Cuando se leen
        List<PackageCompleted> active = adapter.active(Source.TELEGRAM, NOW);

        // Entonces la vigente sale, la corrupta no se destruye y queda rastro con su batchId
        assertThat(active).extracting(PackageCompleted::batchId).containsExactly("b-1");
        verify(hashOps, never()).delete(eq("packages:TELEGRAM"), any());
        assertThat(output.getOut()).contains("batchId=broken", "source=TELEGRAM");
    }

    @Test
    @DisplayName("Dado el índice de una fuente, cuando se leen los vigentes de otra, entonces solo se consulta la key de esa otra")
    void activeReadsOnlyTheKeyOfItsSource() {
        // Dado nada en Discord
        when(hashOps.entries("packages:DISCORD")).thenReturn(Map.of());

        // Cuando se leen los vigentes de Discord
        assertThat(adapter.active(Source.DISCORD, NOW)).isEmpty();

        // Entonces nunca se toca el HASH de Telegram (dual-get)
        verify(hashOps, never()).entries("packages:TELEGRAM");
    }

    @Test
    @DisplayName("Dado Redis caído, cuando se leen los vigentes, entonces devuelve vacío sin lanzar")
    void activeIsEmptyWhenRedisIsDown() {
        // Dado Redis que no responde
        when(hashOps.entries("packages:TELEGRAM")).thenThrow(new RedisConnectionFailureException("down"));

        // Cuando se leen / Entonces el stream se abre igual con historial vacío (RF-07)
        assertThat(adapter.active(Source.TELEGRAM, NOW)).isEmpty();
    }

    @Test
    @DisplayName("Dado un timeout de Redis (no es un fallo de conexión), cuando se leen los vigentes, entonces tampoco escapa")
    void activeIsEmptyOnARedisTimeout() {
        // Dado Redis lento
        when(hashOps.entries("packages:TELEGRAM")).thenThrow(new QueryTimeoutException("slow"));

        // Cuando se leen / Entonces DataAccessException cubre también el timeout
        assertThat(adapter.active(Source.TELEGRAM, NOW)).isEmpty();
    }

    // ---------- fixtures ----------

    private static PackageCompleted pkg(String batchId, Source source, Instant expiresAt) {
        return new PackageCompleted(batchId, source, 3, PAR_URL, expiresAt);
    }

    private String json(PackageCompleted completed) {
        return objectMapper.writeValueAsString(completed);
    }
}
