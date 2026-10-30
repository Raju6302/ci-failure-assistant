package io.github.raju6302.cifa.worker.diagnosis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the agent when {@code cifa.diagnosis.agent-enabled} is true (the default). Claude
 * credentials come from the environment: {@code ANTHROPIC_API_KEY} or another source the
 * SDK supports. Without any, diagnoses fall back to rules and say why.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cifa.diagnosis.agent-enabled", havingValue = "true", matchIfMissing = true)
class DiagnosisConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentTools.class)
    McpAgentTools agentTools(DiagnosisProperties properties) {
        return new McpAgentTools(properties.mcp());
    }

    @Bean
    DiagnosisAgent diagnosisAgent(AnthropicClient client, AgentTools tools, DiagnosisProperties properties) {
        return new DiagnosisAgent(client, tools, properties, Clock.systemUTC());
    }
}
