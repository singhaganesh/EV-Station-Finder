package com.ganesh.finder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Shared RestTemplate for all outbound calls (OCM, Nominatim, OSRM) with
 * explicit connect/read timeouts so a slow external API can never tie up
 * request/worker threads indefinitely.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate externalRestTemplate(
            @Value("${app.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.http.read-timeout-ms:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
