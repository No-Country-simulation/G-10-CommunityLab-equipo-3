package com.nocountry.simulation.communitylab.application.services.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.nocountry.simulation.communitylab.application.command.RevisePostCommand;
import com.nocountry.simulation.communitylab.application.dtos.MessageRevised;
import com.nocountry.simulation.communitylab.application.dtos.ReplaceResult;
import com.nocountry.simulation.communitylab.application.dtos.RevisionResult;
import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.application.exception.StorageFailureException;
import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.application.port.out.EventPublishPost;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.PostChange;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotFoundException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotRevisableException;
import com.nocountry.simulation.communitylab.domain.exception.PostVersionConflictException;

/**
 * Revision use case: load the stored package, check the version the user edited, revise one post,
 * rewrite with the etag of that same read, retry on a concurrent write and notify only real writes.
 * No Spring context; Mockito only at the {@link ArtifactStore} and {@link EventPublishPost} ports.
 *
 * <p>Derivado de: spec 007 RF-02 ({@code 200} con la versión nueva; {@code 404} sin escribir) + RF-04
 * (toda actualización guardada sube la versión; no-op = {@code 200} sin {@code PutObject}) + RF-06
 * ({@code expectedVersion} desfasada → {@code 409} sin escribir; {@code 412} → reintento de
 * lectura+revisión+escritura hasta 2 veces, luego {@code 409}) + RF-08 ({@code message.revised} solo si
 * se escribió) + RF-09 (sin OCI / fallo OCI sin evento) + plan 007 §1.2 (application), §3.
 */
@DisplayName("RevisePostService")
class RevisePostServiceTest {

    private static final String BATCH_ID = "batch-1";
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private ArtifactStore store;
    private EventPublishPost events;
    private RevisePostService service;

    @BeforeEach
    void setUp() {
        store = mock(ArtifactStore.class);
        events = mock(EventPublishPost.class);
        service = new RevisePostService(store, events);
    }

    // ---------- fixtures ----------

    private static EnrichedComment post(String messageId, boolean approved, int versionMessage) {
        return post(messageId, "Nuevo empleo en Java", approved, versionMessage);
    }

    private static EnrichedComment post(String messageId, String copy, boolean approved, int versionMessage) {
        return new EnrichedComment(
                BATCH_ID, messageId, "channel-1", "author-1", "author-name",
                "Conseguí empleo como dev Java", Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 75, null, EnrichedComment.PROMPT_VERSION,
                NOW, Source.DISCORD,
                // An approved post was reviewed, so revised follows approved in these fixtures.
                Channels.X, null, copy, List.of("#Java"), null, approved, approved, versionMessage);
    }

    private static EnrichedComment fallback(String messageId) {
        return new EnrichedComment(
                BATCH_ID, messageId, "channel-1", "author-1", "author-name",
                "content", Sentiment.NEUTRAL, Language.ES, MessageType.OTRO,
                List.of(), 0, EnrichedComment.LLM_FALLBACK, EnrichedComment.PROMPT_VERSION,
                NOW, Source.DISCORD,
                null, null, null, List.of(), null, false, false, 1);
    }

    private static AssetPackage pkg(EnrichedComment... records) {
        return AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(records), NOW);
    }

    private static Optional<StoredPackage> stored(AssetPackage pkg, String etag) {
        return Optional.of(new StoredPackage(pkg, etag));
    }

    private static RevisePostCommand command(String messageId, int expectedVersion, PostChange change) {
        return new RevisePostCommand(Source.DISCORD, BATCH_ID, messageId, expectedVersion, change);
    }

    private static PostChange approve() {
        return new PostChange(null, true, null, null, null, null, null, null);
    }

    private static PostChange editCopy(String copy) {
        return new PostChange(null, null, null, null, null, copy, null, null);
    }

    private static EnrichedComment record(AssetPackage pkg, String messageId) {
        return pkg.enriched().stream().filter(e -> e.messageId().equals(messageId)).findFirst().orElseThrow();
    }

    // ---------- happy path (RF-02, RF-04, RF-08) ----------

    @Test
    @DisplayName("Dado un post v1 sin aprobar, cuando se aprueba con expectedVersion=1, entonces responde v2 aprobado y cambiado")
    void approvingReturnsTheNewVersion() {
        // Dado el paquete guardado en OCI
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(pkg(post("m-1", false, 1)), "etag-1"));
        when(store.replace(any(), anyString())).thenReturn(ReplaceResult.REPLACED);

        // Cuando se aprueba
        RevisionResult result = service.revise(command("m-1", 1, approve()));

        // Entonces el frontend recibe la versión nueva (→ 200)
        assertThat(result).isEqualTo(new RevisionResult(BATCH_ID, "m-1", 2, true, true, true));
    }

    @Test
    @DisplayName("Dado una aprobación, cuando se reescribe, entonces se guarda el paquete revisado con el etag de la lectura")
    void rewritesTheRevisedPackageWithTheEtagOfTheRead() {
        // Dado un paquete con dos posts
        AssetPackage original = pkg(post("m-1", false, 1), post("m-2", false, 1));
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(original, "etag-1"));
        when(store.replace(any(), anyString())).thenReturn(ReplaceResult.REPLACED);

        // Cuando se aprueba m-1
        service.revise(command("m-1", 1, approve()));

        // Entonces 1 PutObject If-Match: etag-1 con m-1 revisado y m-2 intacto
        ArgumentCaptor<AssetPackage> written = ArgumentCaptor.forClass(AssetPackage.class);
        verify(store).replace(written.capture(), eq("etag-1"));
        assertThat(record(written.getValue(), "m-1").approved()).isTrue();
        assertThat(record(written.getValue(), "m-1").versionMessage()).isEqualTo(2);
        assertThat(record(written.getValue(), "m-2")).isEqualTo(record(original, "m-2"));
    }

    @Test
    @DisplayName("Dado una revisión guardada, cuando termina, entonces publica message.revised sin contenido después de escribir")
    void publishesTheRevisionAfterTheWrite() {
        // Dado un paquete guardado
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(pkg(post("m-1", false, 1)), "etag-1"));
        when(store.replace(any(), anyString())).thenReturn(ReplaceResult.REPLACED);

        // Cuando se aprueba
        service.revise(command("m-1", 1, approve()));

        // Entonces primero se escribe y luego se avisa, por el stream de su fuente y solo con ids + versión
        InOrder order = inOrder(store, events);
        order.verify(store).replace(any(), eq("etag-1"));
        order.verify(events).publishRevision(new MessageRevised(Source.DISCORD, BATCH_ID, "m-1", 2, true, true));
    }

    @Test
    @DisplayName("Dado un comando de TELEGRAM, cuando se revisa, entonces lee el paquete de esa fuente")
    void loadsFromTheSourceOfTheCommand() {
        // Dado un paquete de Telegram
        AssetPackage telegram = AssetPackage.of(BATCH_ID, Source.TELEGRAM, List.of(post("m-1", false, 1)), NOW);
        when(store.load(Source.TELEGRAM, BATCH_ID)).thenReturn(stored(telegram, "etag-1"));
        when(store.replace(any(), anyString())).thenReturn(ReplaceResult.REPLACED);

        // Cuando se revisa por la ruta de Telegram
        service.revise(new RevisePostCommand(Source.TELEGRAM, BATCH_ID, "m-1", 1, approve()));

        // Entonces nunca toca la carpeta de Discord y avisa por el stream de Telegram
        verify(store).load(Source.TELEGRAM, BATCH_ID);
        verify(store, never()).load(eq(Source.DISCORD), anyString());
        verify(events).publishRevision(new MessageRevised(Source.TELEGRAM, BATCH_ID, "m-1", 2, true, true));
    }

    // ---------- no-op (RF-04) ----------

    @Test
    @DisplayName("Dado un post ya aprobado, cuando se vuelve a aprobar, entonces responde la misma versión sin escribir ni avisar")
    void noOpNeitherWritesNorPublishes() {
        // Dado un post aprobado en v2
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(pkg(post("m-1", true, 2)), "etag-1"));

        // Cuando se aprueba otra vez
        RevisionResult result = service.revise(command("m-1", 2, approve()));

        // Entonces 200 con la misma versión, 0 PutObject y 0 eventos
        assertThat(result).isEqualTo(new RevisionResult(BATCH_ID, "m-1", 2, true, true, false));
        verify(store, never()).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }

    // ---------- not found (RF-02 → 404) ----------

    @Test
    @DisplayName("Dado un batchId sin paquete en OCI, cuando se revisa, entonces no encontrado sin escribir")
    void missingPackageIsNotFound() {
        // Dado que OCI no tiene el objeto
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(Optional.empty());

        // Cuando se revisa / Entonces 404
        assertThatThrownBy(() -> service.revise(command("m-1", 1, approve())))
                .isInstanceOf(PostNotFoundException.class);
        verify(store, never()).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }

    @Test
    @DisplayName("Dado un paquete sin ese messageId, cuando se revisa, entonces no encontrado sin escribir")
    void missingPostIsNotFound() {
        // Dado un paquete sin m-9
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(pkg(post("m-1", false, 1)), "etag-1"));

        // Cuando se revisa m-9 / Entonces 404 (antes de mirar la versión)
        assertThatThrownBy(() -> service.revise(command("m-9", 1, approve())))
                .isInstanceOf(PostNotFoundException.class);
        verify(store, never()).replace(any(), anyString());
    }

    // ---------- version the user edited (RF-06 a → 409) ----------

    @Test
    @DisplayName("Dado un post en v2, cuando llega expectedVersion=1, entonces conflicto sin escribir")
    void staleExpectedVersionIsAConflict() {
        // Dado un post que otra pestaña ya llevó a v2
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(pkg(post("m-1", true, 2)), "etag-1"));

        // Cuando se edita sobre la copia vieja / Entonces 409 y la edición ajena no se pisa
        assertThatThrownBy(() -> service.revise(command("m-1", 1, editCopy("Texto editado"))))
                .isInstanceOf(PostVersionConflictException.class);
        verify(store, never()).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }

    // ---------- concurrent write on the same object (RF-06 b → retry, then 409) ----------

    @Test
    @DisplayName("Dado un 412 y luego éxito, cuando se revisa, entonces relee, reescribe con el etag nuevo y avisa una vez")
    void retriesWithAFreshReadAfterAConflict() {
        // Dado que entre la lectura y la escritura otro cambio movió el objeto
        AssetPackage pkg = pkg(post("m-1", false, 1));
        when(store.load(Source.DISCORD, BATCH_ID))
                .thenReturn(stored(pkg, "etag-1"))
                .thenReturn(stored(pkg, "etag-2"));
        when(store.replace(any(), eq("etag-1"))).thenReturn(ReplaceResult.CONFLICT);
        when(store.replace(any(), eq("etag-2"))).thenReturn(ReplaceResult.REPLACED);

        // Cuando se aprueba
        RevisionResult result = service.revise(command("m-1", 1, approve()));

        // Entonces el reintento parte de una lectura nueva (nunca reusa el etag viejo)
        assertThat(result.versionMessage()).isEqualTo(2);
        verify(store, times(2)).load(Source.DISCORD, BATCH_ID);
        verify(store).replace(any(), eq("etag-1"));
        verify(store).replace(any(), eq("etag-2"));
        verify(events, times(1)).publishRevision(any());
    }

    @Test
    @DisplayName("Dado 412 en los 3 intentos, cuando se revisa, entonces conflicto tras 2 reintentos y sin evento")
    void givesUpAfterTwoRetries() {
        // Dado un objeto que cambia en cada intento
        when(store.load(Source.DISCORD, BATCH_ID))
                .thenReturn(stored(pkg(post("m-1", false, 1)), "etag-1"))
                .thenReturn(stored(pkg(post("m-1", false, 1)), "etag-2"))
                .thenReturn(stored(pkg(post("m-1", false, 1)), "etag-3"));
        when(store.replace(any(), anyString())).thenReturn(ReplaceResult.CONFLICT);

        // Cuando se aprueba / Entonces 409: 1 intento + 2 reintentos
        assertThatThrownBy(() -> service.revise(command("m-1", 1, approve())))
                .isInstanceOf(PostVersionConflictException.class);
        verify(store, times(3)).load(Source.DISCORD, BATCH_ID);
        verify(store, times(3)).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }

    @Test
    @DisplayName("Dado dos pestañas sobre el mismo post v1, cuando la otra guardó primero, entonces el reintento da conflicto sin escribir")
    void retryDetectsThatTheSamePostWasEditedMeanwhile() {
        // Dado que la primera escritura choca porque la otra pestaña ya llevó m-1 a v2
        when(store.load(Source.DISCORD, BATCH_ID))
                .thenReturn(stored(pkg(post("m-1", false, 1)), "etag-1"))
                .thenReturn(stored(pkg(post("m-1", "Texto de la otra pestaña", false, 2)), "etag-2"));
        when(store.replace(any(), eq("etag-1"))).thenReturn(ReplaceResult.CONFLICT);

        // Cuando se reintenta / Entonces 409: la edición ajena se conserva y no hay segunda escritura
        assertThatThrownBy(() -> service.revise(command("m-1", 1, editCopy("Texto de esta pestaña"))))
                .isInstanceOf(PostVersionConflictException.class);
        verify(store, times(1)).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }

    @Test
    @DisplayName("Dado ediciones simultáneas a posts distintos del lote, cuando el reintento reescribe, entonces conserva las dos")
    void retryKeepsTheOtherPostChange() {
        // Dado que mientras se aprobaba m-1, otra pestaña editó m-2 y guardó primero
        AssetPackage otherTabSaved = pkg(post("m-1", false, 1), post("m-2", "Texto editado por otra pestaña", false, 2));
        when(store.load(Source.DISCORD, BATCH_ID))
                .thenReturn(stored(pkg(post("m-1", false, 1), post("m-2", false, 1)), "etag-1"))
                .thenReturn(stored(otherTabSaved, "etag-2"));
        when(store.replace(any(), eq("etag-1"))).thenReturn(ReplaceResult.CONFLICT);
        when(store.replace(any(), eq("etag-2"))).thenReturn(ReplaceResult.REPLACED);

        // Cuando se aprueba m-1
        service.revise(command("m-1", 1, approve()));

        // Entonces la última versión del objeto contiene los dos cambios
        ArgumentCaptor<AssetPackage> written = ArgumentCaptor.forClass(AssetPackage.class);
        verify(store).replace(written.capture(), eq("etag-2"));
        assertThat(record(written.getValue(), "m-1").approved()).isTrue();
        assertThat(record(written.getValue(), "m-2").outputContentProcessed()).isEqualTo("Texto editado por otra pestaña");
        assertThat(record(written.getValue(), "m-2").versionMessage()).isEqualTo(2);
    }

    // ---------- storage failures (RF-09 → 502 / 503) ----------

    @Test
    @DisplayName("Dado un fallo de OCI al reescribir, cuando se revisa, entonces error de almacenamiento sin reintento ni evento")
    void failedWriteIsAStorageErrorWithoutEvent() {
        // Dado un fallo distinto de 412
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(pkg(post("m-1", false, 1)), "etag-1"));
        when(store.replace(any(), anyString())).thenReturn(ReplaceResult.FAILED);

        // Cuando se aprueba / Entonces 502: reintentar solo tiene sentido ante 412
        assertThatThrownBy(() -> service.revise(command("m-1", 1, approve())))
                .isInstanceOf(StorageFailureException.class);
        verify(store, times(1)).load(Source.DISCORD, BATCH_ID);
        verify(store, times(1)).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }

    @Test
    @DisplayName("Dado OCI sin configurar, cuando se revisa, entonces la causa llega intacta y no hay evento")
    void notConfiguredStoragePropagates() {
        // Dado el modo degradado (OCI_AUTH_MODE=none)
        when(store.load(Source.DISCORD, BATCH_ID)).thenThrow(new StorageNotConfiguredException("OCI_NOT_CONFIGURED"));

        // Cuando se revisa / Entonces el servicio no la traga ni la convierte (→ 503 en el handler)
        assertThatThrownBy(() -> service.revise(command("m-1", 1, approve())))
                .isInstanceOf(StorageNotConfiguredException.class);
        verify(store, never()).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }

    // ---------- domain rules surface unchanged (RF-03) ----------

    @Test
    @DisplayName("Dado una edición que rompe las reglas del canal, cuando se revisa, entonces se rechaza sin escribir")
    void invalidRevisionIsRejectedWithoutWriting() {
        // Dado un post X
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(pkg(post("m-1", false, 1)), "etag-1"));

        // Cuando el texto supera los 280 de X / Entonces 400 y OCI no cambia
        assertThatThrownBy(() -> service.revise(command("m-1", 1, editCopy("a".repeat(300)))))
                .isInstanceOf(InvalidAssetException.class);
        verify(store, never()).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }

    @Test
    @DisplayName("Dado un registro LLM_FALLBACK, cuando se revisa, entonces no revisable sin escribir")
    void fallbackIsNotRevisable() {
        // Dado una traza de fallo del LLM en el paquete
        when(store.load(Source.DISCORD, BATCH_ID)).thenReturn(stored(pkg(post("m-1", false, 1), fallback("m-2")), "etag-1"));

        // Cuando se aprueba / Entonces 422 y OCI no cambia
        assertThatThrownBy(() -> service.revise(command("m-2", 1, approve())))
                .isInstanceOf(PostNotRevisableException.class);
        verify(store, never()).replace(any(), anyString());
        verify(events, never()).publishRevision(any());
    }
}
