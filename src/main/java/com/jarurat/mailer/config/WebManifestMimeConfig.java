package com.jarurat.mailer.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tomcat has no mapping for {@code .webmanifest}, so it falls back to
 * {@code application/octet-stream}. Chrome tolerates that today; the spec asks
 * for {@code application/manifest+json} and Safari has historically been the
 * stricter of the two about it. One line here is cheaper than an install button
 * that silently never appears on somebody's phone.
 */
@Configuration
public class WebManifestMimeConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> webManifestMimeType() {
        return factory -> {
            MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("webmanifest", "application/manifest+json");
            factory.setMimeMappings(mappings);
        };
    }
}
