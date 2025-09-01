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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.io.File;

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

    /* Normaliza la ruta:
       - "file:C:\...\fx.json" -> Path OK
       - "C:\...\fx.json"      -> Path OK
       - "output/fx.json"      -> ${user.dir}/output/fx.json
    */
private static Path toPath(String p) {
    if (p == null || p.isBlank()) return null;

    // Soporta "file:" a lo Windows (file:C:\...),
    // "file:///" estándar (file:///C:/...),
    // absoluta (C:\... o /...),
    // y relativa (output/fx.json).
    if (p.startsWith("file:")) {
        String s = p.substring(5);              // quita "file:"
        s = s.replace('\\', '/');               // normaliza separadores
        // si viene "///C:/..." o similar, limpia los prefijos
        if (s.startsWith("///")) s = s.substring(3);
        else if (s.startsWith("//")) s = s.substring(2);
        else if (s.startsWith("/")) s = s.substring(1);
        // ahora debería quedar "C:/Users/.../fx.json" o "/path/unix"
        // devolvemos un Path nativo
        return Paths.get(s.replace('/', File.separatorChar));
    }

    Path cand = Paths.get(p);
    if (cand.isAbsolute()) return cand;
    return Paths.get(System.getProperty("user.dir")).resolve(cand).normalize();
}

    private static boolean isEmptyFile(Path path) {
        try {
            return !Files.exists(path) || Files.size(path) == 0;
        } catch (Exception e) {
            return true;
        }
    }

    /** Endpoint crudo para diagnóstico: devuelve el contenido literal de fx.json (validado). */
    @GetMapping(path = "/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> raw() {
        try {
            Path path = toPath(fxJsonPath);
            if (path == null || isEmptyFile(path)) {
                log.warn("fx.json no existe o está vacío: {}", (path != null ? path.toString() : fxJsonPath));
                return ResponseEntity.notFound().build();
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
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
        Path path = toPath(fxJsonPath);
        if (path == null || isEmptyFile(path)) {
            log.warn("fx.json no existe o está vacío: {}", (path != null ? path.toString() : fxJsonPath));
            return null;
        }
        for (int i = 0; i < attempts; i++) {
            try {
                // leer como texto para evitar lock mientras Python reemplaza el archivo
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                return mapper.readValue(raw, FxRates.class);
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
