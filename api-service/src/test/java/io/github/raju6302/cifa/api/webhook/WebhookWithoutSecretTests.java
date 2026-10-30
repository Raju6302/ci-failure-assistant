package io.github.raju6302.cifa.api.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.raju6302.cifa.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Without a configured secret, nothing is trusted. */
@SpringBootTest(properties = "cifa.webhook.secret=")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WebhookWithoutSecretTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void refusesAllDeliveries() {
        assertThat(mvc.post().uri("/webhooks/github")
                .header("X-GitHub-Event", "ping")
                .header("X-GitHub-Delivery", "abc")
                .header("X-Hub-Signature-256", "sha256=00")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
