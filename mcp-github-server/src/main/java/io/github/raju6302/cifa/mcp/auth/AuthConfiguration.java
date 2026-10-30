package io.github.raju6302.cifa.mcp.auth;

import io.github.raju6302.cifa.mcp.McpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Service-to-service auth: the analysis worker and this server share a bearer token. The
 * tools only read data, but that data includes private code and CI logs.
 */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthConfiguration.class);

    @Bean
    FilterRegistrationBean<BearerTokenFilter> bearerTokenFilter(McpProperties properties) {
        if (!properties.hasAuthToken()) {
            log.warn("cifa.mcp.auth-token (MCP_AUTH_TOKEN) is not set: anyone who can reach this server can "
                    + "call its tools. Set it for anything but local development.");
        }
        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(
                new BearerTokenFilter(properties.hasAuthToken() ? properties.authToken() : ""));
        registration.setEnabled(properties.hasAuthToken());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
