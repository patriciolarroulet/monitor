// src/main/java/ar/monitor/CorsConfig.java
package ar.monitor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class CorsConfig {

  // Podés overridear por ENV: CORS_ALLOWED_ORIGINS="https://midominio.com,https://otro.com"
  @Value("${cors.allowed-origins:*}")
  private String allowedOriginsCsv;

  @Bean
  public WebMvcConfigurer corsConfigurer() {
    // si viene vacío, usamos "*"
    final String[] patterns = Arrays.stream(
        (allowedOriginsCsv == null || allowedOriginsCsv.isBlank())
            ? new String[]{"*"}
            : Arrays.stream(allowedOriginsCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new)
    ).toArray(String[]::new);

    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            // allowedOriginPatterns permite "*"; con allowedOrigins("*") + credentials = error
            .allowedOriginPatterns(patterns)
            .allowedMethods("*")
            .allowedHeaders("*")
            .allowCredentials(false)
            .maxAge(3600);
      }
    };
  }
}
