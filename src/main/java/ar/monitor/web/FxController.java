package ar.monitor.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ar.monitor.service.pricing.FxRates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Lee el archivo fx.json generado por el script de Python y lo expone vía REST.
 * - GET /api/fx      -> objeto tipado (FxRates)
 * - GET /api/fx/raw  -> JSON crudo (para debug)
 */
@RestController
@RequestMapping("/api/fx")
@CrossOrigin // habilita CORS; si necesitás restringir, usá origins={"http://localhost:3000"} etc.
public class FxController {

    private static final Logger log = LoggerFactory.getLogger(FxController.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${monitor.fx.json-path:output/fx.json}")
    private String fxJsonPath;

    /** Endpoint crudo para diagnóstico: devuelve el contenido literal de fx.json (validado). */
    @GetMapping(path = "/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> raw() {
        try {
            File f = new File(fxJsonPath);
            if (!f.exists()) {
                log.warn("fx.json no existe en ruta: {}", f.getAbsolutePath());
                return ResponseEntity.notFound().build();
            }
            String raw = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(raw); // valida que sea JSON
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).mustRevalidate())
                    .body(node);
        } catch (Exception e) {
            log.error("Error leyendo fx.json (raw): {}", e.toString(), e);
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /** Endpoint tipado para la app/front. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getFx() {
        try {
            FxRates r = tryReadFxWithRetry(3, 120);
            if (r == null) {
                return ResponseEntity.status(503)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"fx.json no disponible\"}");
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).mustRevalidate())
                    .body(r);
        } catch (Exception e) {
            log.error("Error en /api/fx: {}", e.toString(), e);
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Reintenta leer el archivo para evitar fallas durante el replace atómico. */
    private FxRates tryReadFxWithRetry(int attempts, long sleepMs) {
        File f = new File(fxJsonPath);
        if (!f.exists() || f.length() == 0) {
            log.warn("fx.json no existe o está vacío: {}", f.getAbsolutePath());
            return null;
        }
        for (int i = 0; i < attempts; i++) {
            try {
                return mapper.readValue(f, FxRates.class);
            } catch (Exception e) {
                log.warn("Intento {}/{} leyendo fx.json falló: {}",
                        i + 1, attempts, e.getClass().getSimpleName());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }
}
