package com.nocountry.simulation.communitylab.infrastructure.adapters.out.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.nocountry.simulation.communitylab.application.dtos.MessageRevised;
import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
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
 * versionMessage, approved}}} por {@code source}, {@code id={messageId}:v{versionMessage}}, sin contenido) + RNF-04.
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

    @BeforeEach
    void setUp() {
        adapter = new SseEventPublisherAdapter();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new GetMessagesProcessedDiscord(adapter),
                new GetMessageProcessedTelegram(adapter))
                .build();
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
    @DisplayName("Dado una revisión publicada, cuando el frontend lee el data, entonces trae exactamente type, batchId y payload{messageId, versionMessage, approved}")
    void revisionDataMatchesTheContractWithoutContent() throws Exception {
        // Dado un suscriptor Telegram
        MvcResult telegram = subscribe("/api/v1/telegram/messages", null);

        // Cuando se publica la revisión
        adapter.publishRevision(revised("m-1", 2, Source.TELEGRAM));

        // Entonces solo ids, versión y aprobación: el contenido se lee por la PAR (RF-07)
        JsonNode data = json.readTree(dataOf(eventBlock(telegram.getResponse().getContentAsString(), "m-1:v2")));
        assertThat(data.propertyNames()).containsExactly("type", "batchId", "payload");
        assertThat(data.get("type").asString()).isEqualTo("message.revised");
        assertThat(data.get("batchId").asString()).isEqualTo("b-1");

        JsonNode payload = data.get("payload");
        assertThat(payload.propertyNames()).containsExactly("messageId", "versionMessage", "approved");
        assertThat(payload.get("messageId").asString()).isEqualTo("m-1");
        assertThat(payload.get("versionMessage").asInt()).isEqualTo(2);
        assertThat(payload.get("approved").asBoolean()).isTrue();
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

    // ---------- fixtures ----------

    private static MessageRevised revised(String messageId, int versionMessage, Source source) {
        return new MessageRevised(source, "b-1", messageId, versionMessage, true);
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
                false,
                1);
    }
}
