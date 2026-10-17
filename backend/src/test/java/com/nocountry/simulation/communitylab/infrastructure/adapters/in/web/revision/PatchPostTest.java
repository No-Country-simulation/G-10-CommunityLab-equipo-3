package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.nocountry.simulation.communitylab.application.command.RevisePostCommand;
import com.nocountry.simulation.communitylab.application.dtos.RevisionResult;
import com.nocountry.simulation.communitylab.application.exception.PackageTooLargeException;
import com.nocountry.simulation.communitylab.application.exception.StorageFailureException;
import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;
import com.nocountry.simulation.communitylab.application.port.in.RevisePostUseCase;
import com.nocountry.simulation.communitylab.domain.entity.PostChange;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotFoundException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotRevisableException;
import com.nocountry.simulation.communitylab.domain.exception.PostVersionConflictException;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

/**
 * Revision endpoint: validates the body, delegates to the use case and maps its outcome to HTTP
 * (200 with the saved version, or one status per failure, never echoing content or provider details).
 * Full Boot context with the real security chain (CORS + PATCH rule); only the inbound port is mocked.
 *
 * <p>Derivado de: spec 007 RF-02 (E1 validación; E2 {@code 200 {batchId, messageId, versionMessage, approved}},
 * {@code 404}), RF-03 ({@code 400} reglas de canal, {@code 422} {@code LLM_FALLBACK}), RF-04 (no-op = {@code 200}
 * misma versión), RF-06 ({@code 409}), enmienda 2026-10-16 (estado {@code approved}, {@code reject} no editable,
 * {@code publishedAt} no futuro), RF-09 ({@code 502}/{@code 503 OCI_NOT_CONFIGURED}), RNF-01 ({@code 413}),
 * RNF-03, RNF-04 + plan 007 §1.1, §1.2 (web), §2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("PatchPost")
class PatchPostTest {

    private static final String DISCORD_URL = "/api/v1/discord/packages/b-1/messages/m-1";
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    // Why: the real JDA bean must never hit the Discord gateway in tests.
    @MockitoBean
    private JDA jda;

    // Why: the Telegram long-polling starter registers the bot against the real
    // API on context startup; mock the initializer so tests stay offline.
    @MockitoBean
    private TelegramBotInitializer telegramBotInitializer;

    // Why: TelegramBotProperties binds a long at startup; the Binder does not
    // resolve YAML placeholders, so tests must supply a numeric value.
    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot.listen-group-id", () -> "555");
    }

    @MockitoBean
    private RevisePostUseCase revisePostUseCase;

    @Autowired
    private MockMvc mockMvc;

    private ResultActions patchJson(String url, String body) throws Exception {
        return mockMvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // Why stub: the controller maps the use case result; an unstubbed mock would return null.
    // Every result the use case returns comes from a reviewed post, so revised is true.
    private void revisionReturns(int versionMessage, boolean approved, boolean changed) {
        when(revisePostUseCase.revise(any()))
                .thenReturn(new RevisionResult("b-1", "m-1", versionMessage,
                        approved ? ApprovalStatus.APPROVED : ApprovalStatus.IN_REVIEW, true, changed));
    }

    private RevisePostCommand capturedCommand() {
        ArgumentCaptor<RevisePostCommand> captor = ArgumentCaptor.forClass(RevisePostCommand.class);
        verify(revisePostUseCase).revise(captor.capture());
        return captor.getValue();
    }

    // ---------- 200: saved ----------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"discord, DISCORD", "telegram, TELEGRAM"})
    @DisplayName("Dado una aprobación válida, cuando se envía a la ruta de su fuente, entonces 200 con la versión guardada y el caso de uso recibe esa fuente")
    void approvalReturnsTheSavedVersion(String pathSource, Source expected) throws Exception {
        // Dado el caso de uso guardando v2 aprobada
        revisionReturns(2, true, true);

        // Cuando se aprueba un post de la fuente de la ruta
        patchJson("/api/v1/" + pathSource + "/packages/b-1/messages/m-1",
                "{\"expectedVersion\":1,\"approved\":\"APPROVED\"}")
                // Entonces 200 con lo que el frontend necesita para su siguiente expectedVersion
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("b-1"))
                .andExpect(jsonPath("$.messageId").value("m-1"))
                .andExpect(jsonPath("$.versionMessage").value(2))
                .andExpect(jsonPath("$.approved").value("APPROVED"))
                .andExpect(jsonPath("$.revised").value(true));

        RevisePostCommand command = capturedCommand();
        assertThat(command.source()).isEqualTo(expected);
        assertThat(command.batchId()).isEqualTo("b-1");
        assertThat(command.messageId()).isEqualTo("m-1");
        assertThat(command.expectedVersion()).isEqualTo(1);
        assertThat(command.change().approved()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("Dado una edición de todos los campos editables, cuando se envía, entonces llegan intactos y lo no enviado queda en null")
    void editableFieldsReachTheUseCase() throws Exception {
        // Dado una edición sin tocar approved
        String body = """
                {"expectedVersion":2,
                 "topics":["java","empleo"],
                 "channelPost":"X",
                 "titlePost":"Primer empleo",
                 "outputContentProcessed":"Texto corregido por el usuario",
                 "hashtags":["#Java"],
                 "cta":"Cuéntanos tu caso"}""";

        revisionReturns(3, false, true);

        // Cuando se envía
        patchJson(DISCORD_URL, body).andExpect(status().isOk());

        // Entonces el cambio llega completo y approved no se envió (no cambia)
        PostChange change = capturedCommand().change();
        assertThat(change.approved()).isNull();
        assertThat(change.topics()).containsExactly("java", "empleo");
        assertThat(change.channelPost()).isEqualTo(Channels.X);
        assertThat(change.titlePost()).isEqualTo("Primer empleo");
        assertThat(change.outputContentProcessed()).isEqualTo("Texto corregido por el usuario");
        assertThat(change.hashtags()).containsExactly("#Java");
        assertThat(change.cta()).isEqualTo("Cuéntanos tu caso");
    }

    @Test
    @DisplayName("Dado una petición que no cambia nada, cuando se envía, entonces 200 con la misma versión y sin exponer 'changed'")
    void noOpReturnsTheSameVersion() throws Exception {
        // Dado un post ya aprobado en v2 (el caso de uso no escribe)
        revisionReturns(2, true, false);

        // Cuando se vuelve a aprobar / Entonces 200 igual que un cambio: para el frontend el estado es el mismo
        patchJson(DISCORD_URL, "{\"expectedVersion\":2,\"approved\":\"APPROVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionMessage").value(2))
                .andExpect(jsonPath("$.approved").value("APPROVED"))
                .andExpect(jsonPath("$.changed").doesNotExist());
    }

    // ---------- E2: one status per failure (ProblemDetail, no internals) ----------

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new PostNotFoundException("post not found in package"), 404),
                Arguments.of(new PostVersionConflictException("post was revised meanwhile"), 409),
                Arguments.of(new PackageTooLargeException("package over the 1MB budget"), 413),
                Arguments.of(new PostNotRevisableException("LLM_FALLBACK record has no post to revise"), 422),
                Arguments.of(new InvalidAssetException("x outputContentProcessed must be <=280 chars"), 400),
                Arguments.of(new StorageFailureException("package rewrite failed"), 502),
                Arguments.of(new StorageNotConfiguredException("OCI_NOT_CONFIGURED"), 503));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("failures")
    @DisplayName("Dado un fallo del caso de uso, cuando se envía el PATCH, entonces su código HTTP como ProblemDetail")
    void eachFailureHasItsStatus(RuntimeException failure, int expectedStatus) throws Exception {
        // Dado el caso de uso fallando por esa causa
        when(revisePostUseCase.revise(any())).thenThrow(failure);

        // Cuando se envía una revisión válida / Entonces el frontend distingue la causa por el status
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"approved\":\"APPROVED\"}")
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @Test
    @DisplayName("Dado una edición que rompe las reglas del canal, cuando se envía, entonces 400 que dice qué regla falla")
    void channelRuleViolationExplainsTheRule() throws Exception {
        // Dado el dominio rechazando el post final (mensaje fijo, sin valores del body)
        when(revisePostUseCase.revise(any()))
                .thenThrow(new InvalidAssetException("x outputContentProcessed must be <=280 chars"));

        // Cuando se envía / Entonces el usuario sabe qué corregir
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"outputContentProcessed\":\"texto\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("x outputContentProcessed must be <=280 chars")));
    }

    @Test
    @DisplayName("Dado OCI sin configurar, cuando se envía, entonces 503 OCI_NOT_CONFIGURED")
    void notConfiguredStorageSaysSo() throws Exception {
        // Dado el modo degradado (OCI_AUTH_MODE=none)
        when(revisePostUseCase.revise(any())).thenThrow(new StorageNotConfiguredException("OCI_NOT_CONFIGURED"));

        // Cuando se envía / Entonces el frontend sabe que no es culpa del post (RF-09)
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"approved\":\"APPROVED\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("OCI_NOT_CONFIGURED")));
    }

    @Test
    @DisplayName("Dado un fallo de OCI, cuando se envía, entonces 502 sin exponer detalles del proveedor")
    void storageFailureHidesProviderDetails() throws Exception {
        // Dado una excepción cuyo mensaje lleva detalles internos reconocibles
        when(revisePostUseCase.revise(any()))
                .thenThrow(new StorageFailureException("SECRET-OCI-DETAIL opc-request-id=xyz"));

        // Cuando se envía / Entonces 502 con un texto fijo (RF-09)
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"approved\":\"APPROVED\"}")
                .andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("SECRET-OCI-DETAIL"))));
    }

    // ---------- 400: body validation ----------

    @Test
    @DisplayName("Dado un body sin expectedVersion, cuando se envía, entonces 400 que nombra el campo y no se llama al caso de uso")
    void missingExpectedVersionIsRejected() throws Exception {
        patchJson(DISCORD_URL, "{\"approved\":\"APPROVED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("expectedVersion:"))));

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado expectedVersion menor que 1, cuando se envía, entonces 400")
    void nonPositiveExpectedVersionIsRejected() throws Exception {
        patchJson(DISCORD_URL, "{\"expectedVersion\":0,\"approved\":\"APPROVED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("expectedVersion:"))));

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado un body sin ningún cambio, cuando se envía, entonces 400 'al menos un cambio'")
    void bodyWithoutChangesIsRejected() throws Exception {
        patchJson(DISCORD_URL, "{\"expectedVersion\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("anyChange:"))));

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado un post revisado sin cambios, cuando se envía solo revised=true, entonces 200 y el caso de uso recibe la marca")
    void markingReviewedAloneIsAValidChange() throws Exception {
        // Dado el caso de uso guardando v2 revisada
        revisionReturns(2, false, true);

        // Cuando el usuario solo marca el post como revisado
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"revised\":true}")
                // Entonces cuenta como cambio y la respuesta trae la marca
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionMessage").value(2))
                .andExpect(jsonPath("$.revised").value(true));

        PostChange change = capturedCommand().change();
        assertThat(change.revised()).isTrue();
        assertThat(change.approved()).isNull();
    }

    @Test
    @DisplayName("Dado revised=false, cuando se envía, entonces 400 (una revisión no se deshace)")
    void unmarkingReviewedIsRejected() throws Exception {
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"revised\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("revisedOnlyTrue:"))));

        verify(revisePostUseCase, never()).revise(any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "\"sentiment\":\"POSITIVO\"",
            "\"relevance\":90",
            "\"messageAuthor\":\"otro texto\"",
            "\"language\":\"ES\"",
            "\"messageType\":\"LOGRO\"",
            "\"messageId\":\"m-2\"",
            "\"authorName\":\"otra persona\""})
    @DisplayName("Dado un campo no editable en el body, cuando se envía, entonces 400 (no se ignora en silencio)")
    void nonEditableFieldIsRejected(String nonEditable) throws Exception {
        // Dado una aprobación válida que además intenta tocar un campo no editable
        String body = "{\"expectedVersion\":1,\"approved\":\"APPROVED\"," + nonEditable + "}";

        // Cuando se envía / Entonces se rechaza entera
        patchJson(DISCORD_URL, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("onlyEditableFields:"))));

        verify(revisePostUseCase, never()).revise(any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "\"topics\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]",
            "\"hashtags\":[\"#a\",\"#b\",\"#c\",\"#d\",\"#e\",\"#f\"]",
            "\"hashtags\":[\"#ok\",\" \"]"})
    @DisplayName("Dado listas fuera de límite o con elementos vacíos, cuando se envía, entonces 400")
    void invalidListsAreRejected(String list) throws Exception {
        patchJson(DISCORD_URL, "{\"expectedVersion\":1," + list + "}")
                .andExpect(status().isBadRequest());

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado un texto demasiado largo, cuando se rechaza, entonces la respuesta nombra el campo sin devolver su contenido")
    void oversizedTextIsRejectedWithoutEchoingIt() throws Exception {
        // Dado un outputContentProcessed de más de 4000 caracteres con contenido reconocible
        String secretText = "texto-del-post-".repeat(300);

        // Cuando se envía / Entonces 400 que no expone el texto (RNF-04)
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"outputContentProcessed\":\"" + secretText + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("outputContentProcessed:"))))
                .andExpect(content().string(not(containsString("texto-del-post-"))));
    }

    @Test
    @DisplayName("Dado un channelPost fuera de R8, cuando se envía, entonces 400 'malformed body' sin citar el valor")
    void unknownChannelIsRejectedWithoutEchoingIt() throws Exception {
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"channelPost\":\"TIKTOK\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed body"))
                .andExpect(content().string(not(containsString("TIKTOK"))));

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado un JSON roto, cuando se envía, entonces 400 'malformed body'")
    void brokenJsonIsRejected() throws Exception {
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed body"));

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado un body que no es JSON, cuando se envía, entonces 415 y no se llama al caso de uso")
    void nonJsonContentTypeIsRejected() throws Exception {
        mockMvc.perform(patch(DISCORD_URL).contentType(MediaType.TEXT_PLAIN).content("approved"))
                .andExpect(status().isUnsupportedMediaType());

        verify(revisePostUseCase, never()).revise(any());
    }

    // ---------- security ----------

    @Test
    @DisplayName("Dado una fuente fuera de R8, cuando se envía el PATCH, entonces 403 antes de llegar al controller")
    void unknownSourceIsDenied() throws Exception {
        patchJson("/api/v1/slack/packages/b-1/messages/m-1", "{\"expectedVersion\":1,\"approved\":\"APPROVED\"}")
                .andExpect(status().isForbidden());

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado la ruta de revisión, cuando se usa otro método HTTP, entonces 403 (solo PATCH está permitido)")
    void otherMethodOnRevisionRouteIsDenied() throws Exception {
        mockMvc.perform(get(DISCORD_URL)).andExpect(status().isForbidden());
    }

    // Header names are case-insensitive and Spring echoes the requested casing (content-type).
    @Test
    @DisplayName("Dado el origen permitido, cuando el navegador hace el preflight del PATCH JSON, entonces se autoriza PATCH y Content-Type")
    void corsPreflightFromAllowedOriginPasses() throws Exception {
        mockMvc.perform(options(DISCORD_URL)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("PATCH")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsStringIgnoringCase("Content-Type")));
    }

    @Test
    @DisplayName("Dado un origen fuera de la allowlist, cuando hace el preflight del PATCH, entonces se rechaza")
    void corsPreflightFromOtherOriginIsRejected() throws Exception {
        mockMvc.perform(options(DISCORD_URL)
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("Dado el origen permitido, cuando el preflight pide un header no autorizado, entonces se rechaza")
    void corsPreflightWithUnlistedHeaderIsRejected() throws Exception {
        mockMvc.perform(options(DISCORD_URL)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Dado una lista editable válida, cuando se envía, entonces no queda ligada al JSON de entrada")
    void editedListsAreDefensiveCopies() throws Exception {
        revisionReturns(2, false, true);

        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"hashtags\":[\"#Java\"]}")
                .andExpect(status().isOk());

        List<String> hashtags = capturedCommand().change().hashtags();
        assertThatThrownBy(() -> hashtags.add("#otro"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------- enmienda 2026-10-16: estado approved, reject y publishedAt ----------

    @ParameterizedTest(name = "approved={0}")
    @EnumSource(ApprovalStatus.class)
    @DisplayName("Dado cualquier estado de aprobación, cuando se envía, entonces 200 y el caso de uso recibe ese estado")
    void everyApprovalStateReachesTheUseCase(ApprovalStatus state) throws Exception {
        // Dado el caso de uso guardando la revisión
        revisionReturns(2, state == ApprovalStatus.APPROVED, true);

        // Cuando se envía el estado por su nombre
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"approved\":\"" + state + "\"}")
                .andExpect(status().isOk());

        // Entonces llega tal cual al dominio
        assertThat(capturedCommand().change().approved()).isEqualTo(state);
    }

    @ParameterizedTest(name = "approved={0}")
    @ValueSource(strings = {"true", "false", "\"PENDING\""})
    @DisplayName("Dado un approved booleano o un estado desconocido, cuando se envía, entonces 400 'malformed body'")
    void booleanOrUnknownApprovalIsRejected(String approved) throws Exception {
        // Dado el contrato anterior (boolean) o un valor fuera del enum
        // Cuando se envía / Entonces el PATCH es estricto: solo los registros guardados aceptan el boolean viejo
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"approved\":" + approved + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed body"));

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado reject en el body, cuando se envía, entonces 400 (lo deriva el backend del estado)")
    void rejectIsNotEditable() throws Exception {
        // Dado un rechazo enviado como booleano además del estado
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"approved\":\"REJECTED\",\"reject\":true}")
                // Entonces se rechaza entero, como cualquier campo no editable
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("onlyEditableFields:"))));

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado solo publishedAt en el pasado, cuando se envía, entonces cuenta como cambio y llega al caso de uso")
    void publishedAtAloneIsAValidChange() throws Exception {
        // Dado el caso de uso guardando la publicación
        revisionReturns(3, true, true);

        // Cuando el community manager registra cuándo publicó el post
        patchJson(DISCORD_URL, "{\"expectedVersion\":2,\"publishedAt\":\"2026-10-16T15:00:00Z\"}")
                .andExpect(status().isOk());

        // Entonces la fecha llega como instante y approved no se envió (no cambia)
        PostChange change = capturedCommand().change();
        assertThat(change.publishedAt()).isEqualTo(Instant.parse("2026-10-16T15:00:00Z"));
        assertThat(change.approved()).isNull();
    }

    @Test
    @DisplayName("Dado un publishedAt futuro, cuando se envía, entonces 400 sin llamar al caso de uso")
    void futurePublishedAtIsRejected() throws Exception {
        // Dado una fecha de publicación que todavía no ocurrió
        String tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();

        // Cuando se envía / Entonces se rechaza: registra una publicación que ya ocurrió
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"approved\":\"APPROVED\",\"publishedAt\":\"" + tomorrow + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("publishedAt:"))));

        verify(revisePostUseCase, never()).revise(any());
    }
}
