// src/main/java/ar/monitor/service/FuturosService.java
package ar.monitor.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import ar.monitor.model.FuturoDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import java.net.URL;
import java.io.InputStream;


@Service
public class FuturosService {

  private static final Logger log = LoggerFactory.getLogger(FuturosService.class);

  // Acepta NaN/Infinity en el JSON
  private final ObjectMapper mapper = JsonMapper.builder()
      .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
      .build();

  // Por defecto relativo al proyecto; se puede overridear por ENV o args.
  @Value("${futuros.json.path:output/futuros.json}")
  private String jsonPath;

  /** Normaliza la ruta: soporta 'file:', absoluta y relativa. */
  private static Path toPath(String p) {
    if (p == null || p.isBlank()) return null;
    if (p.startsWith("file:")) {
      String s = p.substring(5).replace('\\', '/');
      if (s.startsWith("///")) s = s.substring(3);
      else if (s.startsWith("//")) s = s.substring(2);
      else if (s.startsWith("/")) s = s.substring(1);
      return Paths.get(s.replace('/', java.io.File.separatorChar));
    }
    Path cand = Paths.get(p);
    if (cand.isAbsolute()) return cand;
    return Paths.get(System.getProperty("user.dir")).resolve(cand).normalize();
  }

  private static boolean isEmptyFile(Path path) {
    try { return !Files.exists(path) || Files.size(path) == 0; }
    catch (Exception e) { return true; }
  }

  /** Lee string desde http(s) o disco indistintamente. */
  private String readRawString(String p) throws Exception {
    if (p == null || p.isBlank()) return null;
    if (p.startsWith("http://") || p.startsWith("https://")) {
      try (InputStream in = new URL(p).openStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    Path path = toPath(p);
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  /** Lee el JSON y devuelve Map; si falla, devuelve null (NUNCA lanza excepción). */
  public Map<String, Object> readAsMap() {
    try {
      boolean isHttp = jsonPath != null && (jsonPath.startsWith("http://") || jsonPath.startsWith("https://"));
      Path path = isHttp ? null : toPath(jsonPath);
      if (!isHttp && (path == null || isEmptyFile(path))) {
        log.warn("[/api/futuros] futuros.json no existe o está vacío: {}", (path != null ? path.toString() : jsonPath));
        return null;
      }

      for (int i = 0; i < 3; i++) {
        try {
          String raw = readRawString(jsonPath);
          if (raw == null || raw.isBlank()) {
            log.warn("[/api/futuros] futuros.json vacío ({}).", jsonPath);
            return null;
          }
          return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception parse) {
          if (i == 2) {
            log.warn("[/api/futuros] No pude parsear futuros.json (intentos agotados): {}", parse.toString());
            return null;
          }
          try { Thread.sleep(150L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
      }
      return null;
    } catch (Exception e) {
      log.error("[/api/futuros] Error leyendo archivo: {}", e.toString(), e);
      return null; // sin excepciones hacia arriba
    }
  }

  /** Contrato usado por el Controller: parsea a List<FuturoDto>. */
  public List<FuturoDto> fetchFuturos() {
    try {
      boolean isHttp = jsonPath != null && (jsonPath.startsWith("http://") || jsonPath.startsWith("https://"));
      Path path = isHttp ? null : toPath(jsonPath);
      if (!isHttp && (path == null || isEmptyFile(path))) {
        log.warn("[/api/futuros] futuros.json no existe o está vacío: {}", (path != null ? path.toString() : jsonPath));
        return Collections.emptyList();
      }

      String raw = null;
      for (int i = 0; i < 3; i++) {
        try {
          raw = readRawString(jsonPath);
          break;
        } catch (Exception e) {
          if (i == 2) {
            log.warn("[/api/futuros] No pude leer futuros.json (intentos agotados): {}", e.toString());
            return Collections.emptyList();
          }
          try { Thread.sleep(150L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
      }
      if (raw == null || raw.isBlank()) return Collections.emptyList();

      // 1) Raíz como array: [ {...}, {...} ]
      try {
        return mapper.readValue(raw, new TypeReference<List<FuturoDto>>() {});
      } catch (Exception ignore) {
        // 2) Raíz como objeto con clave lista: { "futuros": [ ... ] } o { "data": [ ... ] }
        try {
          Map<String, Object> obj = mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
          Object items = Optional.ofNullable(obj.get("futuros")).orElse(obj.get("data"));
          if (items == null) {
            log.warn("[/api/futuros] JSON no contiene clave 'futuros' ni 'data'. Devolviendo lista vacía.");
            return Collections.emptyList();
          }
          return mapper.convertValue(items, new TypeReference<List<FuturoDto>>() {});
        } catch (Exception parse2) {
          log.warn("[/api/futuros] No pude parsear a List<FuturoDto]: {}", parse2.toString());
          return Collections.emptyList();
        }
      }
    } catch (Exception e) {
      log.error("[/api/futuros] Error general: {}", e.toString(), e);
      return Collections.emptyList();
    }
  }

  /** Si alguna vez querés el JSON crudo por código, usá este nombre distinto. */
  public Map<String, Object> fetchFuturosRaw() {
    return readAsMap();
  }
}
