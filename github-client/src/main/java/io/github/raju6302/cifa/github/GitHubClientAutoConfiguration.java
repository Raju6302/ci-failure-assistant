package io.github.raju6302.cifa.github;

import io.github.raju6302.cifa.github.auth.AppInstallationTokenProvider;
import io.github.raju6302.cifa.github.auth.ConfiguredTokenProvider;
import io.github.raju6302.cifa.github.auth.GitHubTokenProvider;
import io.github.raju6302.cifa.github.auth.PrivateKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    GitHubTokenProvider gitHubTokenProvider(GitHubProperties properties) {
        GitHubTokenProvider app = null;
        if (properties.app().isConfigured()) {
            app = new AppInstallationTokenProvider(
                    properties.app().id(), privateKey(properties.app()), gitHubRestClient(properties), Clock.systemUTC());
        }
        return new ConfiguredTokenProvider(app, properties.token());
    }

    @Bean
    @ConditionalOnMissingBean
    GitHubClient gitHubClient(GitHubProperties properties, GitHubTokenProvider tokens) {
        return new GitHubClient(gitHubRestClient(properties), tokens, (int) properties.maxLogSize().toBytes());
    }

    static RestClient gitHubRestClient(GitHubProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .baseUrl(properties.apiUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "ci-failure-assistant")
                .configureMessageConverters(converters ->
                        converters.withJsonConverter(new JacksonJsonHttpMessageConverter(GitHubJson.mapper())))
                .build();
    }

    private static PrivateKey privateKey(GitHubProperties.App app) {
        if (app.privateKey() != null && !app.privateKey().isBlank()) {
            return PrivateKeys.fromPem(app.privateKey());
        }
        try {
            return PrivateKeys.fromPem(Files.readString(app.privateKeyPath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read GitHub App private key at " + app.privateKeyPath(), e);
        }
    }
}
