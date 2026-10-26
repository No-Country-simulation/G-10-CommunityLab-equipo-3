package com.nocountry.simulation.communitylab.infrastructure.adapters.out.event;

import org.springframework.web.servlet.mvc.method.annotation.StalledSseClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.nocountry.simulation.communitylab.application.dtos.History;
import com.nocountry.simulation.communitylab.application.dtos.MessageRevised;
import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.discordMessage.GetMessagesProcessedDiscord;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.telegramMessage.GetMessageProcessedTelegram;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routing by source of the SSE outbound adapter (spec 005 dual-get) and the
 * {@code package.completed} notice (spec 006).
 *
 * <p>Derivado de: spec 005 RF-07 + plan 005 §1 (SseEventPublisherAdapter "por
 * fuente, sin mezclar Discord/Telegram") + constitution v1.7-dual-get + spec 006
 * RF-06 ({@code package.completed {type, batchId, payload:{assetsCount, parUrlBase,
 * expiresAt}}} por {@code source}, {@code id=batchId}, replay con {@code Last-Event-ID})
 * + RNF-06 (la PAR nunca en logs) + spec 007 RF-08 ({@code message.revised {type, batchId, payload:{messageId,
 * versionMessage, approved}}} por {@code source}, {@code id={messageId}:v{versionMessage}}, sin contenido) + RNF-04
 * + plan 006 §3 (comentario {@code connected} al suscribirse + heartbeat cada 30 s: Cloudflare corta a los 100 s sin bytes)
 * + spec 008 RF-01/RF-02/RF-04/RF-05/RF-06/RNF-04 (historial al conectar: paquetes vigentes → pendientes →
 * {@code snapshot.completed} sin id; replay si el {@code Last-Event-ID} es conocido, historial si no) + plan 008 §1.1, §2.
 * No Spring context: real adapter + real controllers over standalone MockMvc,
 * asserting on the buffered servlet response (the observable send behavior).
 */
@DisplayName("SseEventPublisherAdapter")
@ExtendWith(OutputCaptureExtension.class)
class SseEventPublisherAdapterTest {

    // Distinctive token so a leak into the logs cannot be a coincidence.
    private static final String PAR_URL =
            "https://objectstorage.sa-saopaulo-1.oraclecloud.com/p/SECRET-PAR-TOKEN/n/ns/b/MessagesUsers/o/paquetes/telegram/paquete-b-1.json";
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-11T10:00:00Z");

    private final JsonMapper json = JsonMapper.builder().build();
    private SseEventPublisherAdapter adapter;
    private MockMvc mockMvc;
    // Fake history use case: what each source would load, and every load that actually happened.
    private final Map<Source, History> histories = new EnumMap<>(Source.class);
    private final List<Source> historyLoads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        adapter = new SseEventPublisherAdapter();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new GetMessagesProcessedDiscord(adapter, this::loadHistory),
                new GetMessageProcessedTelegram(adapter, this::loadHistory))
                .build();
    }

    private History loadHistory(Source source) {
        historyLoads.add(source);
        return histories.getOrDefault(source, History.empty());
    }

    @Test
    @DisplayName("Dado suscriptores en ambas fuentes, cuando se publica un asset TELEGRAM, entonces solo recibe el suscriptor Telegram")
    void publishesOnlyToSubscribersOfSameSource() throws Exception {
        // Dado un suscriptor por fuente
        MvcResult discord = mockMvc.perform(get("/api/v1/discord/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        MvcResult telegram = mockMvc.perform(get("/api/v1/telegram/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        // Cuando se publica un asset de Telegram
        adapter.publish(telegramPost("tg-1"));

        // Entonces solo el stream Telegram recibe el evento
        assertThat(telegram.getResponse().getContentAsString())
                .contains("tg-1")
                .contains("TELEGRAM");
        assertThat(discord.getResponse().getContentAsString())
                .doesNotContain("tg-1");
    }

    @Test
    @DisplayName("Dado eventos previos, cuando se reconecta con Last-Event-ID, entonces el replay sale solo de la cola de su fuente")
    void replayUsesOnlyTheSourceQueue() throws Exception {
        // Dado dos eventos Telegram ya publicados sin suscriptores
        adapter.publish(telegramPost("tg-1"));
        adapter.publish(telegramPost("tg-2"));

        // Cuando se reconecta Telegram desde tg-1, entonces se repite solo tg-2
        MvcResult telegram = mockMvc.perform(get("/api/v1/telegram/messages")
                        .header("Last-Event-ID", "tg-1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        assertThat(telegram.getResponse().getContentAsString())
                .contains("tg-2")
                .doesNotContain("tg-1");

        // Y cuando se reconecta Discord con un id Telegram, entonces no hay replay cruzado
        MvcResult discord = mockMvc.perform(get("/api/v1/discord/messages")
                        .header("Last-Event-ID", "tg-1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        assertThat(discord.getResponse().getContentAsString())
                .doesNotContain("tg-2");
    }

    @Test
    @DisplayName("Dado publish sin suscriptores, cuando se publica, entonces no lanza y queda en replay")
    void publishWithoutSubscribersDoesNotThrow() {
        // Cuando se publica sin ningun suscriptor entonces no propaga excepcion
        assertThatCode(() -> adapter.publish(telegramPost("tg-1")))
                .doesNotThrowAnyException();

        // Entonces el evento queda disponible para replay posterior
        // (cubierto por replayUsesOnlyTheSourceQueue)
    }

    // ---------- package.completed ----------

    @Test
    @DisplayName("Dado suscriptores en ambas fuentes, cuando se anuncia un paquete TELEGRAM, entonces solo Telegram recibe package.completed con id=batchId")
    void packageCompletedGoesOnlyToItsSourceWithBatchIdAsEventId() throws Exception {
        // Dado un suscriptor por fuente
        MvcResult discord = subscribe("/api/v1/discord/messages", null);
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);

        // Cuando se anuncia un paquete de Telegram
        adapter.publishPackage(completed("b-1", Source.TELEGRAM));

        // Entonces el stream Telegram lo recibe con su nombre y el batchId como id (sirve de Last-Event-ID)
        String block = eventBlock(telegram.getResponse().getContentAsString(), "b-1");
        assertThat(block).contains("event:package.completed");
        assertThat(discord.getResponse().getContentAsString()).doesNotContain("b-1");
    }

    @Test
    @DisplayName("Dado un paquete anunciado, cuando el frontend lee el data, entonces trae exactamente type, batchId y payload{assetsCount, parUrlBase, expiresAt ISO-8601}")
    void packageCompletedDataMatchesTheContract() throws Exception {
        // Dado un suscriptor Telegram
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);

        // Cuando se anuncia un paquete con 3 assets
        adapter.publishPackage(completed("b-1", Source.TELEGRAM));

        // Entonces el JSON es el de RF-06, sin source ni contenido del paquete
        JsonNode data = json.readTree(dataOf(eventBlock(telegram.getResponse().getContentAsString(), "b-1")));
        assertThat(data.propertyNames()).containsExactly("type", "batchId", "payload");
        assertThat(data.get("type").asString()).isEqualTo("package.completed");
        assertThat(data.get("batchId").asString()).isEqualTo("b-1");

        JsonNode payload = data.get("payload");
        assertThat(payload.propertyNames()).containsExactly("assetsCount", "parUrlBase", "expiresAt");
        assertThat(payload.get("assetsCount").asInt()).isEqualTo(3);
        assertThat(payload.get("parUrlBase").asString()).isEqualTo(PAR_URL);
        // ISO-8601 text (not epoch numbers) so the frontend can compare it directly
        assertThat(payload.get("expiresAt").asString()).isEqualTo("2026-10-11T10:00:00Z");
    }

    @Test
    @DisplayName("Dado asset y paquete mezclados en la cola, cuando se reconecta con Last-Event-ID, entonces cada evento se repite con su propio nombre")
    void replayResendsEachEventUnderItsOwnName() throws Exception {
        // Dado, sin suscriptores, un asset, un paquete y otro asset de Telegram
        adapter.publish(telegramPost("tg-1"));
        adapter.publishPackage(completed("b-1", Source.TELEGRAM));
        adapter.publish(telegramPost("tg-2"));

        // Cuando el frontend se reconecta desde tg-1
        String content = subscribe("/api/v1/telegram/messages", "tg-1").getResponse().getContentAsString();

        // Entonces el paquete vuelve como package.completed (no como asset.created) y el asset como asset.created
        assertThat(eventBlock(content, "b-1")).contains("event:package.completed");
        assertThat(eventBlock(content, "tg-2")).contains("event:asset.created");
        assertThat(content).doesNotContain("id:tg-1");
    }

    @Test
    @DisplayName("Dado un paquete anunciado, cuando se reconecta con Last-Event-ID=batchId, entonces solo se repite lo posterior")
    void replayResumesAfterAPackageId() throws Exception {
        // Dado un paquete y luego un asset de Telegram
        adapter.publishPackage(completed("b-1", Source.TELEGRAM));
        adapter.publish(telegramPost("tg-2"));

        // Cuando el frontend se reconecta desde el batchId (el último id que vio)
        String content = subscribe("/api/v1/telegram/messages", "b-1").getResponse().getContentAsString();

        // Entonces no recibe de nuevo el paquete, solo el asset posterior
        assertThat(content).contains("id:tg-2").doesNotContain("id:b-1");
    }

    @Test
    @DisplayName("Dado un paquete anunciado, cuando se escribe el log, entonces lleva batchId y source pero nunca la PAR")
    void packageCompletedLogCarriesIdsButNeverThePar(CapturedOutput output) {
        // Cuando se anuncia un paquete
        adapter.publishPackage(completed("b-1", Source.TELEGRAM));

        // Entonces el log sirve para rastrear el aviso sin filtrar el secreto (RNF-06)
        assertThat(output.getOut()).contains("batchId=b-1", "source=TELEGRAM");
        assertThat(output.getOut()).doesNotContain("SECRET-PAR-TOKEN");
    }

    @Test
    @DisplayName("Dado ningún suscriptor, cuando se anuncia un paquete, entonces no lanza (el flush nunca falla por el aviso)")
    void publishPackageWithoutSubscribersDoesNotThrow() {
        // Cuando se anuncia sin nadie conectado / Entonces no propaga excepción y queda en replay
        assertThatCode(() -> adapter.publishPackage(completed("b-1", Source.TELEGRAM)))
                .doesNotThrowAnyException();
    }

    // ---------- message.revised (spec 007 RF-08) ----------

    @Test
    @DisplayName("Dado suscriptores en ambas fuentes, cuando se revisa un post TELEGRAM, entonces solo Telegram recibe message.revised con id={messageId}:v{n}")
    void revisionGoesOnlyToItsSourceWithAVersionedId() throws Exception {
        // Dado un suscriptor por fuente
        MvcResult discord = subscribe("/api/v1/discord/messages", null);
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);

        // Cuando se guarda la revisión v2 de un post de Telegram
        adapter.publishRevision(revised("m-1", 2, Source.TELEGRAM));

        // Entonces llega por el stream de su fuente, con un id distinto por versión (sirve de Last-Event-ID)
        assertThat(eventBlock(telegram.getResponse().getContentAsString(), "m-1:v2")).contains("event:message.revised");
        assertThat(discord.getResponse().getContentAsString()).doesNotContain("m-1");
    }

    @Test
    @DisplayName("Dado una revisión publicada, cuando el frontend lee el data, entonces trae exactamente type, batchId y payload{messageId, versionMessage, approved, revised}")
    void revisionDataMatchesTheContractWithoutContent() throws Exception {
        // Dado un suscriptor Telegram
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);

        // Cuando se publica la revisión
        adapter.publishRevision(revised("m-1", 2, Source.TELEGRAM));

        // Entonces solo ids, versión, aprobación y revisión: el contenido se lee por la PAR (RF-07)
        JsonNode data = json.readTree(dataOf(eventBlock(telegram.getResponse().getContentAsString(), "m-1:v2")));
        assertThat(data.propertyNames()).containsExactly("type", "batchId", "payload");
        assertThat(data.get("type").asString()).isEqualTo("message.revised");
        assertThat(data.get("batchId").asString()).isEqualTo("b-1");

        JsonNode payload = data.get("payload");
        assertThat(payload.propertyNames()).containsExactly("messageId", "versionMessage", "approved", "revised");
        assertThat(payload.get("messageId").asString()).isEqualTo("m-1");
        assertThat(payload.get("versionMessage").asInt()).isEqualTo(2);
        assertThat(payload.get("approved").asString()).isEqualTo("APPROVED");
        assertThat(payload.get("revised").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Dado dos revisiones del mismo post, cuando se publican, entonces cada una tiene su propio id")
    void eachVersionHasItsOwnEventId() throws Exception {
        // Dado un suscriptor Telegram
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);

        // Cuando el post pasa a v2 y luego a v3
        adapter.publishRevision(revised("m-1", 2, Source.TELEGRAM));
        adapter.publishRevision(revised("m-1", 3, Source.TELEGRAM));

        // Entonces el replay no confunde una versión con otra
        String content = telegram.getResponse().getContentAsString();
        assertThat(eventBlock(content, "m-1:v2")).contains("event:message.revised");
        assertThat(eventBlock(content, "m-1:v3")).contains("event:message.revised");
    }

    @Test
    @DisplayName("Dado asset y revisión en la cola, cuando se reconecta con Last-Event-ID, entonces la revisión se repite con su nombre y se puede reanudar desde ella")
    void revisionTakesPartInTheReplay() throws Exception {
        // Dado, sin suscriptores, un asset, una revisión y otro asset de Telegram
        adapter.publish(telegramPost("tg-1"));
        adapter.publishRevision(revised("tg-1", 2, Source.TELEGRAM));
        adapter.publish(telegramPost("tg-2"));

        // Cuando se reconecta desde el primer asset / Entonces la revisión vuelve como message.revised
        String fromAsset = subscribe("/api/v1/telegram/messages", "tg-1").getResponse().getContentAsString();
        assertThat(eventBlock(fromAsset, "tg-1:v2")).contains("event:message.revised");

        // Y cuando se reconecta desde la revisión / Entonces solo lo posterior
        String fromRevision = subscribe("/api/v1/telegram/messages", "tg-1:v2").getResponse().getContentAsString();
        assertThat(fromRevision).contains("id:tg-2").doesNotContain("id:tg-1:v2");
    }

    @Test
    @DisplayName("Dado ningún suscriptor, cuando se publica una revisión, entonces no lanza (la revisión ya está guardada)")
    void publishRevisionWithoutSubscribersDoesNotThrow() {
        // Cuando se publica sin nadie conectado / Entonces no propaga: el PATCH no puede fallar por el aviso
        assertThatCode(() -> adapter.publishRevision(revised("m-1", 2, Source.TELEGRAM)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Dado una revisión publicada, cuando se escribe el log, entonces lleva source, batchId, messageId y versionMessage")
    void revisionLogCarriesIds(CapturedOutput output) {
        // Cuando se publica
        adapter.publishRevision(revised("m-1", 2, Source.TELEGRAM));

        // Entonces se puede rastrear el aviso (RNF-04: solo ids y versión)
        assertThat(output.getOut()).contains("source=TELEGRAM", "batchId=b-1", "messageId=m-1", "versionMessage=2");
    }

    // ---------- connection comment (Cloudflare 524) ----------

    @Test
    @DisplayName("Dado un dashboard sin eventos previos, cuando se suscribe, entonces el primer frame es el comentario connected sin id ni nombre")
    void subscribeOpensTheStreamWithAConnectedComment() throws Exception {
        // Cuando un dashboard se suscribe y todavía no se ha publicado nada
        String content = subscribe("/api/v1/telegram/messages", null).getResponse().getContentAsString();

        // Entonces recibe un byte enseguida (Cloudflare no espera 100 s en silencio) y es solo un comentario:
        // sin id: el navegador no lo toma como Last-Event-ID; sin event:/data: no dispara onmessage
        assertThat(content.split("\n\n")[0]).isEqualTo(":connected");
    }

    @Test
    @DisplayName("Dado eventos previos, cuando se reconecta con Last-Event-ID, entonces el comentario connected llega antes que el replay")
    void connectedCommentPrecedesTheReplay() throws Exception {
        // Dado dos eventos Telegram ya publicados sin suscriptores
        adapter.publish(telegramPost("tg-1"));
        adapter.publish(telegramPost("tg-2"));

        // Cuando el dashboard se reconecta desde tg-1
        String content = subscribe("/api/v1/telegram/messages", "tg-1").getResponse().getContentAsString();

        // Entonces primero el comentario y después el evento repetido
        assertThat(content).startsWith(":connected\n\n");
        assertThat(eventBlock(content, "tg-2")).contains("event:asset.created");
    }

    @Test
    @DisplayName("Dado un dashboard ya suscrito, cuando se suscribe otro, entonces el primero no recibe un segundo comentario connected")
    void connectedCommentGoesOnlyToTheNewSubscriber() throws Exception {
        // Dado un dashboard Telegram ya conectado
        MvcResult first = subscribe("/api/v1/telegram/messages", null);

        // Cuando se conecta un segundo dashboard a la misma fuente
        subscribe("/api/v1/telegram/messages", null);

        // Entonces el comentario es por conexión, no un broadcast
        assertThat(occurrences(first.getResponse().getContentAsString(), ":connected\n\n")).isEqualTo(1);
    }

    // ---------- heartbeat (Cloudflare 524) ----------

    @Test
    @DisplayName("Dado dashboards suscritos a ambas fuentes, cuando late el heartbeat, entonces los dos reciben el comentario heartbeat")
    void heartbeatReachesSubscribersOfEverySource() throws Exception {
        // Dado un dashboard por fuente
        MvcResult discord = subscribe("/api/v1/discord/messages", null);
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);

        // Cuando late el heartbeat
        adapter.heartbeat();

        // Entonces Cloudflare ve tráfico en los dos streams, no solo en el de una fuente
        for (MvcResult stream : List.of(discord, telegram)) {
            String content = stream.getResponse().getContentAsString();
            assertThat(content).endsWith("\n\n:heartbeat\n\n");
            assertThat(occurrences(content, ":heartbeat\n\n")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Dado un dashboard que dejó de leer, cuando late otro heartbeat mientras el anterior sigue bloqueado, entonces se salta en vez de apilarse")
    void overlappingHeartbeatIsSkipped() throws Exception {
        // Dado un suscriptor cuyo socket ya no acepta escrituras (buffer TCP lleno)
        SseEmitter emitter = adapter.subscribe(Source.TELEGRAM, null, History::empty);
        StalledSseClient stalled = StalledSseClient.attachTo(emitter);
        ExecutorService scheduler = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // Y un heartbeat ya bloqueado escribiéndole (con virtual threads, fixedRate no espera al anterior)
            Future<?> first = scheduler.submit(adapter::heartbeat);
            assertThat(stalled.awaitBlockedWrite(2, TimeUnit.SECONDS)).isTrue();

            // Cuando llega el siguiente tick
            Future<?> second = scheduler.submit(adapter::heartbeat);

            // Entonces termina enseguida sin volver a escribir: los hilos no se apilan sobre el socket bloqueado (spec 010)
            assertThatCode(() -> second.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThat(stalled.heartbeatWrites()).isEqualTo(1);

            stalled.release();
            first.get(2, TimeUnit.SECONDS);
        } finally {
            stalled.release();
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("Dado un heartbeat terminado, cuando late el siguiente, entonces vuelve a enviarse (el salto solo aplica mientras otro sigue en curso)")
    void heartbeatRunsAgainAfterThePreviousFinished() throws Exception {
        // Dado un dashboard
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);

        // Cuando laten dos heartbeats seguidos, sin solaparse
        adapter.heartbeat();
        adapter.heartbeat();

        // Entonces llegan los dos
        assertThat(occurrences(telegram.getResponse().getContentAsString(), ":heartbeat\n\n")).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un evento ya recibido, cuando late el heartbeat, entonces llega sin id y el último id del stream sigue siendo el del evento")
    void heartbeatDoesNotMoveTheLastEventId() throws Exception {
        // Dado un dashboard que ya recibió tg-1
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);
        adapter.publish(telegramPost("tg-1"));

        // Cuando late el heartbeat
        adapter.heartbeat();

        // Entonces es un bloque de solo comentario: el navegador reconectaría con Last-Event-ID=tg-1
        String content = telegram.getResponse().getContentAsString();
        assertThat(content).endsWith("\n\n:heartbeat\n\n");
        assertThat(content.lines().filter(line -> line.startsWith("id:")).toList()).containsExactly("id:tg-1");
    }

    @Test
    @DisplayName("Dado una conexión cerrada antes que una viva, cuando late el heartbeat, entonces no lanza y la viva lo recibe igual")
    void heartbeatSkipsADeadClientWithoutBreakingTheRest() throws Exception {
        // Dado un emitter ya cerrado (cliente que se fue) registrado antes que un dashboard vivo
        adapter.subscribe(Source.TELEGRAM, null, History::empty).complete();
        MvcResult alive = subscribe("/api/v1/telegram/messages", null);

        // Cuando late el heartbeat / Entonces el fallo del muerto no se propaga al scheduler
        assertThatCode(() -> adapter.heartbeat()).doesNotThrowAnyException();

        // Y el vivo recibe su latido aunque el muerto iba primero en la lista
        assertThat(alive.getResponse().getContentAsString()).endsWith(":heartbeat\n\n");
    }

    @Test
    @DisplayName("Dado ningún dashboard conectado, cuando late el heartbeat, entonces no lanza")
    void heartbeatWithoutSubscribersDoesNotThrow() {
        // Cuando late sin nadie conectado / Entonces el hilo del scheduler nunca recibe una excepción
        assertThatCode(() -> adapter.heartbeat()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Dado el heartbeat programado, cuando se lee su intervalo, entonces es fijo y bien por debajo de los 100 s de Cloudflare")
    void heartbeatIntervalStaysUnderTheCloudflareIdleCut() throws Exception {
        // Dado el método programado
        Scheduled scheduled = SseEventPublisherAdapter.class.getMethod("heartbeat").getAnnotation(Scheduled.class);

        // Cuando se lee la anotación / Entonces 30 s a ritmo fijo: un latido perdido sigue dejando margen
        assertThat(Duration.of(scheduled.fixedRate(), scheduled.timeUnit().toChronoUnit()))
                .isEqualTo(Duration.ofSeconds(30));
    }

    // ---------- history on connect (spec 008) ----------

    @Test
    @DisplayName("Dado paquetes vigentes y mensajes sin guardar, cuando se suscribe sin Last-Event-ID, entonces llegan :connected, paquetes, mensajes y snapshot.completed en ese orden")
    void initialBlockComesInTheSpecifiedOrder() throws Exception {
        // Dado el historial de Telegram con 2 paquetes y 2 mensajes pendientes
        histories.put(Source.TELEGRAM, new History(
                List.of(completed("b-1", Source.TELEGRAM), completed("b-2", Source.TELEGRAM)),
                List.of(telegramPost("m-1"), telegramPost("m-2"))));

        // Cuando un dashboard abre el stream por primera vez
        String content = subscribe("/api/v1/telegram/messages", null).getResponse().getContentAsString();

        // Entonces el bloque inicial sigue el orden de RF-01
        List<String> blocks = Arrays.asList(content.split("\n\n"));
        assertThat(blocks).hasSize(6);
        assertThat(blocks.get(0)).isEqualTo(":connected");
        assertThat(blocks.get(1)).contains("id:b-1", "event:package.completed");
        assertThat(blocks.get(2)).contains("id:b-2", "event:package.completed");
        assertThat(blocks.get(3)).contains("id:m-1", "event:asset.created");
        assertThat(blocks.get(4)).contains("id:m-2", "event:asset.created");
        assertThat(blocks.get(5)).contains("event:snapshot.completed");
    }

    @Test
    @DisplayName("Dado un historial, cuando termina el bloque inicial, entonces snapshot.completed no lleva id y trae type, packages y pending")
    void snapshotCompletedHasNoIdAndCarriesTheCounts() throws Exception {
        // Dado 1 paquete y 3 mensajes pendientes
        histories.put(Source.TELEGRAM, new History(
                List.of(completed("b-1", Source.TELEGRAM)),
                List.of(telegramPost("m-1"), telegramPost("m-2"), telegramPost("m-3"))));

        // Cuando se suscribe
        String block = namedBlock(subscribe("/api/v1/telegram/messages", null).getResponse().getContentAsString(),
                "snapshot.completed");

        // Entonces no mueve el Last-Event-ID del navegador y cuenta lo enviado
        assertThat(block.lines()).noneMatch(line -> line.startsWith("id:"));
        JsonNode data = json.readTree(dataOf(block));
        assertThat(data.propertyNames()).containsExactly("type", "packages", "pending");
        assertThat(data.get("type").asString()).isEqualTo("snapshot.completed");
        assertThat(data.get("packages").asInt()).isEqualTo(1);
        assertThat(data.get("pending").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("Dado un paquete en el historial, cuando se envía, entonces su package.completed es idéntico al que salió en vivo")
    void historyEventsAreTheSameAsTheLiveOnes() throws Exception {
        // Dado un dashboard que vio en vivo el paquete b-1
        MvcResult live = subscribe("/api/v1/telegram/messages", null);
        adapter.publishPackage(completed("b-1", Source.TELEGRAM));
        String liveData = dataOf(eventBlock(live.getResponse().getContentAsString(), "b-1"));

        // Cuando otro dashboard llega tarde y lo recibe por el historial
        histories.put(Source.TELEGRAM, new History(List.of(completed("b-1", Source.TELEGRAM)), List.of()));
        String late = subscribe("/api/v1/telegram/messages", null).getResponse().getContentAsString();

        // Entonces el mismo JSON, con la PAR que el frontend lee directo de OCI
        assertThat(dataOf(eventBlock(late, "b-1"))).isEqualTo(liveData);
        assertThat(json.readTree(liveData).get("payload").get("parUrlBase").asString()).isEqualTo(PAR_URL);
    }

    @Test
    @DisplayName("Dado un Last-Event-ID que sigue en memoria, cuando se reconecta, entonces repite lo posterior sin cargar el historial")
    void knownLastEventIdReplaysWithoutLoadingTheHistory() throws Exception {
        // Dado dos eventos en la cola de replay y un historial que no debería usarse
        adapter.publish(telegramPost("tg-1"));
        adapter.publish(telegramPost("tg-2"));
        histories.put(Source.TELEGRAM, new History(List.of(completed("b-old", Source.TELEGRAM)), List.of()));

        // Cuando se reconecta desde tg-1
        String content = subscribe("/api/v1/telegram/messages", "tg-1").getResponse().getContentAsString();

        // Entonces solo lo posterior, sin tocar Redis, y snapshot.completed cuenta el replay
        assertThat(historyLoads).isEmpty();
        assertThat(content).contains("id:tg-2").doesNotContain("id:tg-1\n").doesNotContain("b-old");
        JsonNode data = json.readTree(dataOf(namedBlock(content, "snapshot.completed")));
        assertThat(data.get("packages").asInt()).isZero();
        assertThat(data.get("pending").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dado un replay con una revisión, cuando se cuentan los eventos repetidos, entonces message.revised no suma a packages ni a pending")
    void replayCountsOnlyPackagesAndAssets() throws Exception {
        // Dado un asset, un paquete y una revisión tras el último id visto
        adapter.publish(telegramPost("tg-1"));
        adapter.publish(telegramPost("tg-2"));
        adapter.publishPackage(completed("b-1", Source.TELEGRAM));
        adapter.publishRevision(revised("tg-2", 2, Source.TELEGRAM));

        // Cuando se reconecta desde tg-1
        String content = subscribe("/api/v1/telegram/messages", "tg-1").getResponse().getContentAsString();

        // Entonces 1 asset y 1 paquete (la revisión se repite pero no se cuenta)
        assertThat(eventBlock(content, "tg-2:v2")).contains("event:message.revised");
        JsonNode data = json.readTree(dataOf(namedBlock(content, "snapshot.completed")));
        assertThat(data.get("packages").asInt()).isEqualTo(1);
        assertThat(data.get("pending").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dado un Last-Event-ID que ya no está en memoria (redeploy), cuando se reconecta, entonces recibe el historial completo")
    void unknownLastEventIdGetsTheFullHistory() throws Exception {
        // Dado un backend recién reiniciado (cola vacía) y paquetes vigentes en Redis
        histories.put(Source.TELEGRAM, new History(List.of(completed("b-1", Source.TELEGRAM)), List.of(telegramPost("m-1"))));

        // Cuando el navegador reconecta con su último id de antes del reinicio
        String content = subscribe("/api/v1/telegram/messages", "tg-before-restart").getResponse().getContentAsString();

        // Entonces no se queda sin historial
        assertThat(historyLoads).containsExactly(Source.TELEGRAM);
        assertThat(eventBlock(content, "b-1")).contains("event:package.completed");
        assertThat(eventBlock(content, "m-1")).contains("event:asset.created");
    }

    @Test
    @DisplayName("Dado ningún dato, cuando se suscribe, entonces recibe :connected y snapshot.completed con contadores a 0")
    void emptyHistoryStillEndsTheInitialBlock() throws Exception {
        // Cuando se suscribe sin historial
        String content = subscribe("/api/v1/telegram/messages", null).getResponse().getContentAsString();

        // Entonces el frontend sabe igual que ya cargó (quita el estado de carga)
        assertThat(content).startsWith(":connected\n\n");
        JsonNode data = json.readTree(dataOf(namedBlock(content, "snapshot.completed")));
        assertThat(data.get("packages").asInt()).isZero();
        assertThat(data.get("pending").asInt()).isZero();
    }

    @Test
    @DisplayName("Dado historial en ambas fuentes, cuando se suscribe a Discord, entonces solo se carga y se envía el de Discord")
    void historyIsIsolatedBySource() throws Exception {
        // Dado historial distinto por fuente
        histories.put(Source.DISCORD, new History(List.of(completed("b-d", Source.DISCORD)), List.of()));
        histories.put(Source.TELEGRAM, new History(List.of(completed("b-t", Source.TELEGRAM)), List.of()));

        // Cuando se suscribe a Discord
        String content = subscribe("/api/v1/discord/messages", null).getResponse().getContentAsString();

        // Entonces nada de Telegram
        assertThat(historyLoads).containsExactly(Source.DISCORD);
        assertThat(content).contains("id:b-d").doesNotContain("b-t");
    }

    @Test
    @DisplayName("Dado un historial con PAR, cuando se suscribe, entonces el log lleva fuente y contadores pero nunca la PAR")
    void subscriptionLogCarriesCountsButNeverThePar(CapturedOutput output) throws Exception {
        // Dado un paquete vigente
        histories.put(Source.TELEGRAM, new History(List.of(completed("b-1", Source.TELEGRAM)), List.of(telegramPost("m-1"))));

        // Cuando se suscribe
        subscribe("/api/v1/telegram/messages", null);

        // Entonces se puede rastrear sin filtrar el secreto (RNF-04)
        assertThat(output.getOut()).contains("sse subscribed: source=TELEGRAM packages=1 pending=1");
        assertThat(output.getOut()).doesNotContain("SECRET-PAR-TOKEN");
    }

    // ---------- fixtures ----------

    private static MessageRevised revised(String messageId, int versionMessage, Source source) {
        return new MessageRevised(source, "b-1", messageId, versionMessage, ApprovalStatus.APPROVED, true);
    }

    private MvcResult subscribe(String path, String lastEventId) throws Exception {
        var request = get(path).accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static PackageCompleted completed(String batchId, Source source) {
        return new PackageCompleted(batchId, source, 3, PAR_URL, EXPIRES_AT);
    }

    // One SSE event = lines ending in a blank line; pick the one whose id matches.
    private static String eventBlock(String stream, String id) {
        return Arrays.stream(stream.split("\n\n"))
                .filter(block -> block.lines().anyMatch(line -> line.equals("id:" + id)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SSE event with id " + id + " in:\n" + stream));
    }

    // Blocks without id (snapshot.completed) are found by event name.
    private static String namedBlock(String stream, String name) {
        return Arrays.stream(stream.split("\n\n"))
                .filter(block -> block.lines().anyMatch(line -> line.equals("event:" + name)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SSE event named " + name + " in:\n" + stream));
    }

    private static int occurrences(String text, String fragment) {
        return text.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    private static String dataOf(String block) {
        return block.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no data line in:\n" + block));
    }

    private ResponseClient telegramPost(String messageId) {
        return new ResponseClient(
                "author-name",
                "cos_dev",
                messageId,
                "batch-1",
                Sentiment.POSITIVO,
                Language.ES,
                MessageType.LOGRO,
                List.of("empleo", "java"),
                85,
                null,
                Instant.ofEpochSecond(1727000000),
                Source.TELEGRAM,
                Channels.LINKEDIN,
                null,
                "De la comunidad al primer empleo Java!",
                List.of("#ONE", "#Java"),
                "Comparte tu historia",
                false, ApprovalStatus.IN_REVIEW,
                1, false, null);
    }
}
