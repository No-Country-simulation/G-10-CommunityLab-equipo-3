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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.nocountry.simulation.communitylab.application.port.in.RevisePostUseCase;
import com.nocountry.simulation.communitylab.domain.entity.PostChange;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

/**
 * Revision endpoint, E1: receives and validates the revision, answers 202 without
 * storing it. Full Boot context with the real security chain (CORS + PATCH rule);
 * only the inbound port is mocked.
 *
 * <p>Derivado de: spec 007 RF-02, RNF-01, RNF-03, RNF-04 + plan 007 §1.1 (web, security), §2.
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

    private RevisePostCommand capturedCommand() {
        ArgumentCaptor<RevisePostCommand> captor = ArgumentCaptor.forClass(RevisePostCommand.class);
        verify(revisePostUseCase).revise(captor.capture());
        return captor.getValue();
    }

    // ---------- accepted ----------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"discord, DISCORD", "telegram, TELEGRAM"})
    @DisplayName("Dado una aprobación válida, cuando se envía a la ruta de su fuente, entonces 202 y el caso de uso recibe esa fuente")
    void approvalIsAcceptedWithSourceFromPath(String pathSource, Source expected) throws Exception {
        // Dado / Cuando se aprueba un post de la fuente de la ruta
        patchJson("/api/v1/" + pathSource + "/packages/b-1/messages/m-1",
                "{\"expectedVersion\":1,\"approved\":true}")
                // Entonces recibido, sin guardar todavía
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").value("b-1"))
                .andExpect(jsonPath("$.messageId").value("m-1"));

        RevisePostCommand command = capturedCommand();
        assertThat(command.source()).isEqualTo(expected);
        assertThat(command.batchId()).isEqualTo("b-1");
        assertThat(command.messageId()).isEqualTo("m-1");
        assertThat(command.expectedVersion()).isEqualTo(1);
        assertThat(command.change().approved()).isTrue();
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

        // Cuando se envía
        patchJson(DISCORD_URL, body).andExpect(status().isAccepted());

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

    // ---------- 400: body validation ----------

    @Test
    @DisplayName("Dado un body sin expectedVersion, cuando se envía, entonces 400 que nombra el campo y no se llama al caso de uso")
    void missingExpectedVersionIsRejected() throws Exception {
        patchJson(DISCORD_URL, "{\"approved\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("expectedVersion:"))));

        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado expectedVersion menor que 1, cuando se envía, entonces 400")
    void nonPositiveExpectedVersionIsRejected() throws Exception {
        patchJson(DISCORD_URL, "{\"expectedVersion\":0,\"approved\":true}")
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
        String body = "{\"expectedVersion\":1,\"approved\":true," + nonEditable + "}";

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
        patchJson("/api/v1/slack/packages/b-1/messages/m-1", "{\"expectedVersion\":1,\"approved\":true}")
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
        patchJson(DISCORD_URL, "{\"expectedVersion\":1,\"hashtags\":[\"#Java\"]}")
                .andExpect(status().isAccepted());

        List<String> hashtags = capturedCommand().change().hashtags();
        assertThatThrownBy(() -> hashtags.add("#otro"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
