package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.storage;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nocountry.simulation.communitylab.application.dtos.StorageStats;
import com.nocountry.simulation.communitylab.application.exception.StorageMetricsUnavailableException;
import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;
import com.nocountry.simulation.communitylab.application.port.in.GetStorageStatsUseCase;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

/**
 * Storage stats endpoint: public GET that returns the snapshot as JSON with the browser cache aligned to the
 * backend TTL, and 503 when there is nothing to serve. Full Boot context with the real security chain (CORS +
 * GET rule); only the inbound port is mocked.
 *
 * <p>Derivado de: spec 009 RF-01 (contrato JSON), RF-04 (caché), RF-05 ({@code 503} sin foto o sin OCI;
 * {@code stale}), RNF-05 (sin namespace/OCID/bucket, CORS allowlist) + plan 009 §1.1, §2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GetStorageStats")
class GetStorageStatsTest {

    private static final String URL = "/api/v1/storage/stats";
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    // Why: the real JDA bean must never hit the Discord gateway in tests.
    @MockitoBean
    private JDA jda;

    // Why: the Telegram long-polling starter registers the bot against the real API on startup.
    @MockitoBean
    private TelegramBotInitializer telegramBotInitializer;

    // Why: TelegramBotProperties binds a long at startup and the test context has no value for it.
    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot.listen-group-id", () -> "555");
    }

    @MockitoBean
    private GetStorageStatsUseCase getStorageStatsUseCase;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Dado una foto, cuando se pide sin autenticación, entonces 200 con el contrato JSON del plan")
    void snapshotIsServedAsTheJsonContract() throws Exception {
        // Dado la foto del caso de uso
        when(getStorageStatsUseCase.get()).thenReturn(snapshot(false));

        // Cuando el dashboard la pide / Entonces usado + límite de cada cosa, fechas ISO-8601 y sin campos derivados
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.storage.tier").value("STANDARD"))
                .andExpect(jsonPath("$.storage.usedBytes").value(2_684_354_560L))
                .andExpect(jsonPath("$.storage.limitBytes").value(10_737_418_240L))
                .andExpect(jsonPath("$.storage.objectCount").value(312))
                .andExpect(jsonPath("$.storage.measuredAt").value("2026-10-14T15:00:00Z"))
                .andExpect(jsonPath("$.requests.periodStart").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.requests.limit").value(50_000))
                .andExpect(jsonPath("$.requests.total").value(1_840))
                .andExpect(jsonPath("$.requests.get").value(1_710))
                .andExpect(jsonPath("$.requests.clientErrors").value(30))
                .andExpect(jsonPath("$.generatedAt").value("2026-10-14T15:42:07Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.storage.availableBytes").doesNotExist())
                .andExpect(jsonPath("$.storage.usedPercent").doesNotExist());
    }

    @Test
    @DisplayName("Dado una foto, cuando se responde, entonces Cache-Control público con el TTL del backend (60 s)")
    void browserCacheFollowsTheBackendTtl() throws Exception {
        // Dado la foto
        when(getStorageStatsUseCase.get()).thenReturn(snapshot(false));

        // Cuando se pide / Entonces el navegador no pregunta más a menudo de lo que el backend refresca
        mockMvc.perform(get(URL))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=60, public"));
    }

    @Test
    @DisplayName("Dado una foto stale, cuando se pide, entonces 200 con stale=true")
    void staleSnapshotIsStillOk() throws Exception {
        // Dado OCI caído con foto previa
        when(getStorageStatsUseCase.get()).thenReturn(snapshot(true));

        // Cuando se pide / Entonces el dashboard muestra los datos anteriores y avisa (RF-05)
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true));
    }

    @Test
    @DisplayName("Dado OCI caído sin foto previa, cuando se pide, entonces 503 'storage metrics unavailable' sin detalles de OCI")
    void unavailableWithoutSnapshotIs503() throws Exception {
        // Dado el primer intento tras arrancar con Monitoring fallando
        when(getStorageStatsUseCase.get()).thenThrow(new StorageMetricsUnavailableException(
                "OCI Monitoring unavailable", new IllegalStateException("ocid1.compartment.oc1..SECRET")));

        // Cuando se pide / Entonces se puede reintentar más tarde, sin el mensaje del proveedor
        mockMvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("storage metrics unavailable"))
                .andExpect(content().string(not(containsString("SECRET"))));
    }

    @Test
    @DisplayName("Dado OCI sin configurar, cuando se pide, entonces 503 OCI_NOT_CONFIGURED")
    void notConfiguredIs503() throws Exception {
        // Dado auth-mode=none
        when(getStorageStatsUseCase.get()).thenThrow(new StorageNotConfiguredException("OCI_NOT_CONFIGURED"));

        // Cuando se pide / Entonces el mismo 503 que la revisión sin OCI (spec 007 RF-09)
        mockMvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("OCI_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("Dado la respuesta, cuando se inspecciona, entonces no lleva namespace, OCIDs, región ni nombre del bucket")
    void responseNeverExposesInfrastructureIds() throws Exception {
        // Dado la foto
        when(getStorageStatsUseCase.get()).thenReturn(snapshot(false));

        // Cuando se pide / Entonces solo volumen y llamadas (RNF-05)
        mockMvc.perform(get(URL))
                .andExpect(content().string(not(containsString("ocid1"))))
                .andExpect(content().string(not(containsString("namespace"))))
                .andExpect(content().string(not(containsString("MessagesUsers"))))
                .andExpect(content().string(not(containsString("sa-saopaulo-1"))));
    }

    @Test
    @DisplayName("Dado un origen permitido, cuando pide la foto desde el navegador, entonces CORS lo deja pasar")
    void allowedOriginGetsCorsHeaders() throws Exception {
        // Dado el frontend en un origen de la allowlist
        when(getStorageStatsUseCase.get()).thenReturn(snapshot(false));

        // Cuando hace fetch / Entonces el navegador puede leer la respuesta
        mockMvc.perform(get(URL).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("Dado un origen fuera de la allowlist, cuando pide la foto, entonces CORS la bloquea")
    void otherOriginIsRejected() throws Exception {
        // Dado un sitio cualquiera
        when(getStorageStatsUseCase.get()).thenReturn(snapshot(false));

        // Cuando hace fetch / Entonces 403 de CORS
        mockMvc.perform(get(URL).header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Dado la ruta de métricas, cuando se usa otro método que GET, entonces se rechaza")
    void onlyGetIsOpen() throws Exception {
        // Cuando alguien intenta escribir / Entonces la cadena de seguridad lo deniega (solo lectura)
        mockMvc.perform(post(URL)).andExpect(status().isForbidden());
    }

    private static StorageStats snapshot(boolean stale) {
        return new StorageStats(
                new StorageStats.Storage("STANDARD", 2_684_354_560L, 10_737_418_240L, 312L,
                        Instant.parse("2026-10-14T15:00:00Z")),
                new StorageStats.Requests(Instant.parse("2026-10-01T00:00:00Z"), 50_000L,
                        1_840L, 1_710L, 96L, 0L, 4L, 0L, 30L),
                Instant.parse("2026-10-14T15:42:07Z"),
                stale);
    }
}
