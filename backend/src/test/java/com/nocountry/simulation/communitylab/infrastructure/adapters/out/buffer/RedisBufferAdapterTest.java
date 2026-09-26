package com.nocountry.simulation.communitylab.infrastructure.adapters.out.buffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the Redis buffer adapter (no Spring context, no Docker).
 * Mocks {@code RedisTemplate} ops at the ports/adapters boundary.
 *
 * <p>Derivado de: spec 004 RF-02 (dedup SADD + RPUSH + INCRBY) + RNF-01
 * (900KB threshold base) + plan 004 §1/§2 + tasks 001-06 verificación.
 */
@DisplayName("RedisBufferAdapter")
class RedisBufferAdapterTest {

    private static final String ID_KEY = "buffer:current:id";
    private static final String IDS_KEY = "buffer:current:ids";
    private static final String LIST_KEY = "buffer:current:list";
    private static final String BYTES_KEY = "buffer:current:bytes";

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private ListOperations<String, String> listOps;
    private ObjectMapper objectMapper;

    private RedisBufferAdapter adapter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        listOps = mock(ListOperations.class);
        // Why: real mapper for happy paths asserts real JSON bytes (UTF-8);
        // mocked only in the Jackson-failure case.
        objectMapper = new ObjectMapper();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForList()).thenReturn(listOps);

        adapter = new RedisBufferAdapter(redisTemplate, objectMapper, 921600L);
    }

    private static ChannelMessage message(String messageId) {
        return new ChannelMessage(
                "batch-1",
                messageId,
                "listen-123",
                "author-1",
                "author-name",
                "hello",
                Instant.parse("2026-09-24T10:00:00Z"),
                false,
                Source.DISCORD);
    }

    @Test
    @DisplayName("Given existing batch id, when requested, then it returns it without SET")
    void returnsExistingBatchId() {
        // Given lote abierto en Redis
        when(valueOps.get(ID_KEY)).thenReturn("batch-1");

        // When solicitado
        String batchId = adapter.getCurrentBatchId();

        // Then mismo id sin crear otro
        assertThat(batchId).isEqualTo("batch-1");
        verify(valueOps).get(ID_KEY);
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("Given no batch id, when requested, then it creates UUID and SETs it")
    void createsBatchIdWhenMissing() {
        // Given sin lote abierto
        when(valueOps.get(ID_KEY)).thenReturn(null);

        // When solicitado
        String batchId = adapter.getCurrentBatchId();

        // Then uuid nuevo persistido y devuelto
        assertThat(batchId).isNotBlank();
        var setCaptor = forClass(String.class);
        verify(valueOps).set(eq(ID_KEY), setCaptor.capture());
        assertThat(setCaptor.getValue()).isEqualTo(batchId);
    }

    @Test
    @DisplayName("Given Redis down, when batch id requested, then it returns ephemeral UUID without throw")
    void returnsEphemeralBatchIdOnRedisFailure() {
        // Given Redis caído en GET
        when(valueOps.get(ID_KEY)).thenThrow(mock(RedisConnectionFailureException.class));

        // When solicitado then uuid efímero sin lanzar (degradado REDIS_NOT_CONFIGURED)
        String batchId = adapter.getCurrentBatchId();

        assertThat(batchId).isNotBlank();
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("Given new messageId, when appended, then it RPUSHes JSON and INCRBYs UTF-8 len")
    void appendsNewMessage() {
        // Given messageId no visto (SADD=1)
        ChannelMessage msg = message("msg-1");
        when(setOps.add(IDS_KEY, "msg-1")).thenReturn(1L);

        // When agregado al lote
        adapter.appendToBatch(msg);

        // Then dedup + RPUSH payload con messageId + INCRBY len UTF-8 del payload
        verify(setOps).add(IDS_KEY, "msg-1");
        var payloadCaptor = forClass(String.class);
        verify(listOps).rightPush(eq(LIST_KEY), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("msg-1");

        var bytesCaptor = forClass(Long.class);
        verify(valueOps).increment(eq(BYTES_KEY), bytesCaptor.capture());
        assertThat(bytesCaptor.getValue())
                .isEqualTo(payloadCaptor.getValue().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("Given duplicate messageId, when appended, then it skips RPUSH and INCRBY")
    void skipsDuplicateMessage() {
        // Given messageId ya visto (SADD=0)
        ChannelMessage msg = message("msg-1");
        when(setOps.add(IDS_KEY, "msg-1")).thenReturn(0L);

        // When re-llega
        adapter.appendToBatch(msg);

        // Then sin RPUSH ni INCRBY (RF-02 idempotencia)
        verify(setOps).add(IDS_KEY, "msg-1");
        verify(listOps, never()).rightPush(anyString(), anyString());
        verify(valueOps, never()).increment(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("Given null message, when appended, then it does nothing without Redis calls")
    void ignoresNullMessage() {
        // Given mensaje nulo

        // When agregado
        adapter.appendToBatch(null);

        // Then sin tocar Redis (ni serializar)
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Given null messageId, when appended, then it does nothing without Redis calls")
    void ignoresNullMessageId() {
        // Given mensaje sin messageId (no deduplicable)
        ChannelMessage msg = new ChannelMessage(
                "batch-1", null, "listen-123", "author-1", "author-name",
                "hello", Instant.parse("2026-09-24T10:00:00Z"), false, Source.DISCORD);

        // When agregado
        adapter.appendToBatch(msg);

        // Then sin tocar Redis
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Given serialization failure, when appended, then it skips Redis writes without throw")
    void skipsOnJacksonFailure() throws Exception {
        // Given mapper que falla (solo este caso mockea el mapper)
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(mock(JacksonException.class));
        RedisBufferAdapter failingAdapter =
                new RedisBufferAdapter(redisTemplate, failingMapper, 921600L);

        // When agregado then sin RPUSH/INCRBY y sin lanzar
        assertThatCode(() -> failingAdapter.appendToBatch(message("msg-9")))
                .doesNotThrowAnyException();
        verifyNoInteractions(setOps);
        verifyNoInteractions(listOps);
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("Given Redis down on SADD, when appended, then it does not throw")
    void doesNotThrowOnRedisFailure() {
        // Given Redis caído en dedup
        when(setOps.add(IDS_KEY, "msg-1")).thenThrow(mock(RedisConnectionFailureException.class));

        // When agregado then degradado sin throw (SSE vivo intacto en 004)
        assertThatCode(() -> adapter.appendToBatch(message("msg-1")))
                .doesNotThrowAnyException();
        verify(listOps, never()).rightPush(anyString(), anyString());
    }
}
