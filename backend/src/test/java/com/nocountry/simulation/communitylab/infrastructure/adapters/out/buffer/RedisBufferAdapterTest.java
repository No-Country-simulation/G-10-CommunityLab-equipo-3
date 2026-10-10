package com.nocountry.simulation.communitylab.infrastructure.adapters.out.buffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redis outbound adapter: one batch per source, atomic sealing and pending retry.
 * No Spring context and no live Redis: {@link RedisTemplate} is mocked at the
 * boundary; the {@link ObjectMapper} is real so the JSON round-trip is exercised.
 *
 * <p>Derivado de: spec 004 RF buffer (SADD dedup, RPUSH, INCRBY bytes) + spec 006
 * RF-01 (cierre por tamaño) + regla nueva lote por fuente + plan 006 §3 (sellado
 * MULTI/RENAME sin Lua, pendientes reintentables).
 */
@DisplayName("RedisBufferAdapter")
class RedisBufferAdapterTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ListOperations<String, String> listOps;
    private SetOperations<String, String> setOps;
    private ObjectMapper objectMapper;
    private RedisBufferAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        listOps = mock(ListOperations.class);
        setOps = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        objectMapper = JsonMapper.builder().build();
        adapter = new RedisBufferAdapter(redisTemplate, objectMapper);
    }

    // ---------- current batch id ----------

    @Test
    @DisplayName("Dado un lote DISCORD abierto, cuando se pide su id, entonces se lee de la key de DISCORD")
    void currentBatchIdReadsSourceKey() {
        // Dado un uuid ya guardado para DISCORD
        when(valueOps.get("buffer:DISCORD:current:id")).thenReturn("open-d");

        // Cuando se pide / Entonces se devuelve sin crear otro
        assertThat(adapter.getCurrentBatchId(Source.DISCORD)).isEqualTo("open-d");
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("Dado ningún lote TELEGRAM, cuando se pide su id, entonces se crea y guarda en la key de TELEGRAM")
    void currentBatchIdCreatedPerSource() {
        // Dado que TELEGRAM aún no tiene lote
        when(valueOps.get("buffer:TELEGRAM:current:id")).thenReturn(null);

        // Cuando se pide
        String id = adapter.getCurrentBatchId(Source.TELEGRAM);

        // Entonces se persiste en su propia key, nunca en la de DISCORD
        assertThat(id).isNotBlank();
        verify(valueOps).set("buffer:TELEGRAM:current:id", id);
        verify(valueOps, never()).set(eq("buffer:DISCORD:current:id"), anyString());
    }

    @Test
    @DisplayName("Dado Redis caído, cuando se pide el id del lote, entonces devuelve un uuid sin lanzar")
    void currentBatchIdDegradesWhenRedisDown() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.getCurrentBatchId(Source.DISCORD)).isNotBlank();
    }

    // ---------- append ----------

    @Test
    @DisplayName("Dado un post DISCORD nuevo, cuando se bufferiza, entonces va a las keys DISCORD y devuelve el total de bytes")
    void appendWritesSourceKeysAndReturnsTotalBytes() throws Exception {
        // Dado un post nuevo (SADD = 1) y un contador que queda en 5000
        EnrichedComment post = post("m-1", Source.DISCORD);
        long jsonBytes = objectMapper.writeValueAsString(post).getBytes(StandardCharsets.UTF_8).length;
        when(setOps.add("buffer:DISCORD:current:ids", "m-1")).thenReturn(1L);
        when(valueOps.increment("buffer:DISCORD:current:bytes", jsonBytes)).thenReturn(5_000L);

        // Cuando se bufferiza
        long total = adapter.appendToBatch(post);

        // Entonces dedup -> RPUSH -> INCRBY con el tamaño real del json, y el total del lote vuelve
        InOrder order = inOrder(setOps, listOps, valueOps);
        order.verify(setOps).add("buffer:DISCORD:current:ids", "m-1");
        order.verify(listOps).rightPush(eq("buffer:DISCORD:current:list"), anyString());
        order.verify(valueOps).increment("buffer:DISCORD:current:bytes", jsonBytes);
        assertThat(total).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("Dado un post TELEGRAM, cuando se bufferiza, entonces nunca toca las keys de DISCORD")
    void appendTelegramIsolatedFromDiscord() {
        // Dado un post TELEGRAM nuevo
        when(setOps.add("buffer:TELEGRAM:current:ids", "m-1")).thenReturn(1L);
        when(valueOps.increment(eq("buffer:TELEGRAM:current:bytes"), anyLong())).thenReturn(10L);

        // Cuando se bufferiza
        adapter.appendToBatch(post("m-1", Source.TELEGRAM));

        // Entonces solo keys TELEGRAM
        verify(listOps).rightPush(eq("buffer:TELEGRAM:current:list"), anyString());
        verify(setOps, never()).add(eq("buffer:DISCORD:current:ids"), any(String[].class));
        verify(listOps, never()).rightPush(eq("buffer:DISCORD:current:list"), anyString());
    }

    @Test
    @DisplayName("Dado un messageId repetido en el lote, cuando se bufferiza, entonces no se duplica y devuelve 0")
    void appendDedupReturnsZero() {
        // Dado SADD = 0 (ya estaba en el lote)
        when(setOps.add("buffer:DISCORD:current:ids", "m-1")).thenReturn(0L);

        // Cuando se bufferiza / Entonces 0 = "nada nuevo, nada que evaluar"
        assertThat(adapter.appendToBatch(post("m-1", Source.DISCORD))).isZero();
        verify(listOps, never()).rightPush(anyString(), anyString());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    @DisplayName("Dado un post nulo, cuando se bufferiza, entonces devuelve 0 sin tocar Redis")
    void appendNullReturnsZero() {
        assertThat(adapter.appendToBatch(null)).isZero();
        verifyNoInteractions(setOps, listOps, valueOps);
    }

    @Test
    @DisplayName("Dado Redis caído, cuando se bufferiza, entonces devuelve 0 sin lanzar")
    void appendRedisDownReturnsZero() {
        when(setOps.add(anyString(), any(String[].class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.appendToBatch(post("m-1", Source.DISCORD))).isZero();
    }

    // ---------- count ----------

    @Test
    @DisplayName("Dado un lote con 5 mensajes, cuando se cuentan, entonces es el LLEN de la lista de su fuente")
    void countMessagesIsListLengthPerSource() {
        when(listOps.size("buffer:DISCORD:current:list")).thenReturn(5L);
        when(listOps.size("buffer:TELEGRAM:current:list")).thenReturn(2L);

        assertThat(adapter.countMessages(Source.DISCORD)).isEqualTo(5L);
        assertThat(adapter.countMessages(Source.TELEGRAM)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Dado Redis caído, cuando se cuentan mensajes, entonces devuelve 0 sin lanzar")
    void countMessagesRedisDownReturnsZero() {
        when(listOps.size(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.countMessages(Source.DISCORD)).isZero();
    }

    // ---------- seal ----------

    @Test
    @DisplayName("Dado un lote vacío, cuando se sella, entonces no hay transacción y devuelve vacío")
    void sealEmptyBatchDoesNothing() {
        when(listOps.size("buffer:DISCORD:current:list")).thenReturn(0L);

        assertThat(adapter.sealCurrentBatch(Source.DISCORD)).isEmpty();
        verify(redisTemplate, never()).execute(any(SessionCallback.class));
    }

    @Test
    @DisplayName("Dado un lote con mensajes, cuando se sella, entonces en un MULTI renombra, reinicia, abre uuid nuevo y lo marca pendiente")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sealRenamesAndRotatesAtomically() {
        // Dado un lote DISCORD abierto con mensajes
        when(listOps.size("buffer:DISCORD:current:list")).thenReturn(3L);
        when(valueOps.get("buffer:DISCORD:current:id")).thenReturn("b-1");

        // Cuando se sella
        Optional<String> sealed = adapter.sealCurrentBatch(Source.DISCORD);

        // Entonces devuelve el id del lote que se selló
        assertThat(sealed).contains("b-1");

        // Y la transacción, ejecutada sobre una conexión simulada, hace exactamente esto
        ArgumentCaptor<SessionCallback> callback = ArgumentCaptor.forClass(SessionCallback.class);
        verify(redisTemplate).execute(callback.capture());
        RedisOperations<String, String> ops = mock(RedisOperations.class);
        ValueOperations<String, String> txValue = mock(ValueOperations.class);
        SetOperations<String, String> txSet = mock(SetOperations.class);
        when(ops.opsForValue()).thenReturn(txValue);
        when(ops.opsForSet()).thenReturn(txSet);
        callback.getValue().execute(ops);

        ArgumentCaptor<String> newId = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(ops, txValue, txSet);
        order.verify(ops).multi();
        order.verify(ops).rename("buffer:DISCORD:current:list", "buffer:DISCORD:sealed:b-1");
        order.verify(ops).delete(List.of("buffer:DISCORD:current:ids", "buffer:DISCORD:current:bytes"));
        order.verify(txValue).set(eq("buffer:DISCORD:current:id"), newId.capture());
        order.verify(txSet).add("buffer:DISCORD:pending", "b-1");
        order.verify(ops).exec();
        // el lote nuevo nunca reutiliza el id sellado (RENAME pisaría un sellado pendiente)
        assertThat(newId.getValue()).isNotBlank().isNotEqualTo("b-1");
    }

    @Test
    @DisplayName("Dado Redis caído, cuando se sella, entonces devuelve vacío sin lanzar")
    void sealRedisDownReturnsEmpty() {
        when(listOps.size(anyString())).thenReturn(3L);
        when(valueOps.get(anyString())).thenReturn("b-1");
        when(redisTemplate.execute(any(SessionCallback.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.sealCurrentBatch(Source.DISCORD)).isEmpty();
    }

    // ---------- pending / read / discard ----------

    @Test
    @DisplayName("Dado lotes sellados sin subir, cuando se piden pendientes, entonces vienen del SET de su fuente")
    void pendingBatchesReadsSourceSet() {
        when(setOps.members("buffer:TELEGRAM:pending")).thenReturn(Set.of("b-1", "b-2"));

        assertThat(adapter.pendingBatches(Source.TELEGRAM)).containsExactlyInAnyOrder("b-1", "b-2");
    }

    @Test
    @DisplayName("Dado Redis sin respuesta, cuando se piden pendientes, entonces devuelve vacío")
    void pendingBatchesNullIsEmpty() {
        when(setOps.members(anyString())).thenReturn(null);

        assertThat(adapter.pendingBatches(Source.DISCORD)).isEmpty();
    }

    @Test
    @DisplayName("Dado un lote sellado con un registro corrupto, cuando se lee, entonces se salta solo ese registro")
    void readSealedSkipsCorruptRecord() throws Exception {
        // Dado 2 registros válidos y 1 json roto en el lote sellado
        EnrichedComment first = post("m-1", Source.DISCORD);
        EnrichedComment second = post("m-2", Source.DISCORD);
        when(listOps.range("buffer:DISCORD:sealed:b-1", 0, -1)).thenReturn(List.of(
                objectMapper.writeValueAsString(first),
                "{not-json",
                objectMapper.writeValueAsString(second)));

        // Cuando se lee
        List<EnrichedComment> records = adapter.readSealed(Source.DISCORD, "b-1");

        // Entonces vuelven los 2 válidos, en orden de llegada, idénticos a lo bufferizado
        assertThat(records).containsExactly(first, second);
    }

    @Test
    @DisplayName("Dado un registro sellado antes de spec 007 (sin approved/versionMessage), cuando se lee, entonces no se descarta y queda false/1")
    void readSealedKeepsRecordWrittenBeforeReviewAttributes() {
        // Dado el JSON de un registro bufferizado antes del despliegue de 007
        EnrichedComment current = post("m-1", Source.DISCORD);
        ObjectNode legacy = (ObjectNode) objectMapper.valueToTree(current);
        legacy.remove("approved");
        legacy.remove("versionMessage");
        when(listOps.range("buffer:DISCORD:sealed:b-1", 0, -1)).thenReturn(List.of(legacy.toString()));

        // Cuando se lee el lote sellado
        List<EnrichedComment> records = adapter.readSealed(Source.DISCORD, "b-1");

        // Entonces el mensaje sigue en el lote (no "sealed record skipped") con los valores por defecto
        assertThat(records).containsExactly(current);
        assertThat(records.getFirst().approved()).isFalse();
        assertThat(records.getFirst().versionMessage()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dado Redis caído, cuando se lee un lote sellado, entonces devuelve vacío sin lanzar")
    void readSealedRedisDownReturnsEmpty() {
        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.readSealed(Source.DISCORD, "b-1")).isEmpty();
    }

    @Test
    @DisplayName("Dado un lote subido, cuando se descarta, entonces se borra su lista y sale de pendientes")
    void discardSealedDeletesListAndPendingMark() {
        adapter.discardSealed(Source.TELEGRAM, "b-1");

        verify(redisTemplate).delete("buffer:TELEGRAM:sealed:b-1");
        verify(setOps).remove("buffer:TELEGRAM:pending", "b-1");
    }

    private static EnrichedComment post(String messageId, Source source) {
        return new EnrichedComment(
                "ingest-batch", messageId, "channel-1", "author-1", "tester",
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, null, Instant.parse("2026-10-06T10:00:00Z"), source,
                Channels.FAQ, "Primer empleo dev",
                "Consegui mi primer empleo como dev Java gracias a la comunidad",
                List.of("#EmpleoTech"), null, false, 1);
    }
}
