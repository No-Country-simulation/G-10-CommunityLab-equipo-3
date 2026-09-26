package com.nocountry.simulation.communitylab;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import net.dv8tion.jda.api.JDA;

/**
 * Public health endpoint (no Spring context slice: full Boot context with MockMvc).
 *
 * <p>Derivado de: spec 004 RF-03 (GET /actuator/health -&gt; {"status":"UP"} sin
 * auth, p95 &lt;100ms) + constitution Q3 (resto de actuadores cerrados).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Health")
class HealthTest {

    // Why: same as CommunityLabApplicationTests, the real JDA bean must never
    // hit the Discord gateway in tests.
    @MockitoBean
    private JDA jda;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Given started app, when GET /actuator/health, then 200 + UP without auth")
    void healthIsUpPublicly() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
