package com.nocountry.simulation.communitylab.infrastructure.adapters.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Package cache: a package + its OCI etag in Redis under {@code pkgcache:{SOURCE}:{batchId}} with a TTL; best effort,
 * so any Redis, JSON or domain-validation failure reads as a miss. RedisTemplate mocked at the boundary, real ObjectMapper.
 *
 * <p>Derivado de: spec 010 RF-08 (copia del paquete + etag, SET NX tras un GET y SET tras un PUT confirmado, TTL {@code PACKAGE_CACHE_TTL}, un fallo de Redis cae a OCI,
 * sin contenido en logs) + plan 010 §D.
 */
@DisplayName("RedisPackageCache")
@ExtendWith(OutputCaptureExtension.class)
class RedisPackageCacheTest {

    private static final String BATCH_ID = "batch-1";
    private static final String KEY = "pkgcache:DISCORD:batch-1";
    private static final String ETAG = "etag-1";
    private static final Duration TTL = Duration.ofHours(24);
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final String AUTHOR_NAME = "Autora-Privada";

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private RedisPackageCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache = new RedisPackageCache(redisTemplate, objectMapper, TTL);
    }

    @Test
    @DisplayName("Dado un paquete guardado, cuando se lee, entonces vuelve el mismo paquete con su etag")
    void putThenGetRoundTrips() {
        // Dado un paquete cacheado (lo que Redis guardó)
        AssetPackage pkg = discordPackage();
        cache.put(pkg, ETAG);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(KEY), json.capture(), eq(TTL));
        when(valueOps.get(KEY)).thenReturn(json.getValue());

        // Cuando se lee / Entonces paquete y etag intactos para el If-Match
        assertThat(cache.get(Source.DISCORD, BATCH_ID)).contains(new StoredPackage(pkg, ETAG));
    }

    @Test
    @DisplayName("Dado un paquete, cuando se guarda, entonces usa la key por fuente y lote con el TTL configurado")
    void putUsesSourceBatchKeyAndTtl() {
        cache.put(discordPackage(), ETAG);

        verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
    }

    @Test
    @DisplayName("Dado una copia tras un GET, cuando se guarda, entonces usa SET NX para no pisar una versión más nueva ya cacheada")
    void putIfAbsentNeverOverwrites() {
        // Cuando una lectura de OCI guarda su copia
        cache.putIfAbsent(discordPackage(), ETAG);

        // Entonces solo llena un hueco vacío (un replace concurrente pudo cachear ya la versión nueva)
        verify(valueOps).setIfAbsent(eq(KEY), anyString(), eq(TTL));
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Dado una copia tras un PUT If-Match confirmado, cuando se guarda, entonces sobrescribe porque es la versión más nueva")
    void putAfterReplaceOverwrites() {
        cache.put(discordPackage(), ETAG);

        verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Dado un etag vacío, cuando se guarda, entonces no se cachea porque nunca podría reescribirse con If-Match")
    void putWithoutEtagIsSkipped() {
        cache.put(discordPackage(), null);
        cache.put(discordPackage(), " ");
        cache.putIfAbsent(discordPackage(), null);

        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("Dado nada en Redis, cuando se lee, entonces es un miss")
    void missingKeyIsAMiss() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(cache.get(Source.DISCORD, BATCH_ID)).isEmpty();
    }

    @Test
    @DisplayName("Dado una entrada corrupta, cuando se lee, entonces es un miss y el log no lleva su contenido")
    void corruptEntryIsAMissWithoutLeaking(CapturedOutput output) {
        when(valueOps.get(KEY)).thenReturn("{\"etag\":\"x\",\"pkg\":" + AUTHOR_NAME);

        assertThat(cache.get(Source.DISCORD, BATCH_ID)).isEmpty();
        assertThat(output).contains("package cache read failed: batchId=batch-1 source=DISCORD");
        assertThat(output).doesNotContain(AUTHOR_NAME);
    }

    @Test
    @DisplayName("Dado una entrada que ya no pasa la validación del dominio, cuando se lee, entonces es un miss y la revisión cae a OCI")
    void entryRejectedByDomainValidationIsAMiss() throws Exception {
        // Dado una entrada escrita por un deploy anterior cuyas reglas cambiaron (la validación corre al deserializar)
        ObjectMapper strictMapper = mock(ObjectMapper.class);
        when(strictMapper.readValue(anyString(), eq(RedisPackageCache.CachedPackage.class)))
                .thenThrow(new InvalidAssetException("rule changed"));
        RedisPackageCache strictCache = new RedisPackageCache(redisTemplate, strictMapper, TTL);
        when(valueOps.get(KEY)).thenReturn("{\"etag\":\"e\",\"pkg\":{}}");

        // Cuando se lee / Entonces nunca un 500 durante 24 h: es como si no estuviera
        assertThat(strictCache.get(Source.DISCORD, BATCH_ID)).isEmpty();
    }

    @Test
    @DisplayName("Dado Redis caído o lento, cuando se lee, guarda o invalida, entonces nunca lanza y la lectura es un miss")
    void redisFailuresNeverEscape() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        org.mockito.Mockito.doThrow(new QueryTimeoutException("timeout"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("timeout"));
        when(redisTemplate.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get(Source.DISCORD, BATCH_ID)).isEqualTo(Optional.empty());
        assertThatCode(() -> cache.put(discordPackage(), ETAG)).doesNotThrowAnyException();
        assertThatCode(() -> cache.putIfAbsent(discordPackage(), ETAG)).doesNotThrowAnyException();
        assertThatCode(() -> cache.evict(Source.DISCORD, BATCH_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Dado un lote, cuando se invalida, entonces se borra solo su key")
    void evictDeletesOnlyThatKey() {
        cache.evict(Source.TELEGRAM, "b-9");

        verify(redisTemplate).delete("pkgcache:TELEGRAM:b-9");
        verify(redisTemplate, never()).delete(KEY);
    }

    @Test
    @DisplayName("Dado un TTL cero o negativo, cuando se construye, entonces falla al arrancar")
    void nonPositiveTtlFailsFast() {
        assertThatThrownBy(() -> new RedisPackageCache(redisTemplate, objectMapper, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PACKAGE_CACHE_TTL");
        assertThatThrownBy(() -> new RedisPackageCache(redisTemplate, objectMapper, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AssetPackage discordPackage() {
        EnrichedComment post = new EnrichedComment(
                BATCH_ID, "m-1", "channel-1", "author-1", AUTHOR_NAME, "Conseguí mi primer empleo, ¡gracias comunidad!",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, EnrichedComment.PROMPT_VERSION, NOW, Source.DISCORD,
                Channels.FAQ, "Primer empleo dev", "Primer empleo como dev Java", List.of("#EmpleoTech"), null, false,
                ApprovalStatus.IN_REVIEW, 1, null, null);
        return AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(post), NOW);
    }
}
