package ar.monitor.web;

import ar.monitor.service.pricing.CaucionesProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/cauciones")
public class CaucionesController {

  private final CaucionesProvider provider;
  private final AtomicReference<Map<String, Object>> last = new AtomicReference<>();

  public CaucionesController(CaucionesProvider provider) {
    this.provider = provider;
  }

  // ===== GET (para el FRONT) =====
  @GetMapping
  public Map<String, Object> list() {
    // 1) si hay snapshot ingerido por el worker → devolverlo
    Map<String, Object> cached = last.get();
    if (cached != null) return cached;

    // 2) fallback seguro (NO reventar si no hay archivo local)
    return buildFromProviderSafe();
  }

  private Map<String, Object> buildFromProviderSafe() {
    try {
      var res = provider.read();
      if (res == null || res.quotes() == null) return emptyPayload();
      List<Map<String, Object>> rows = new ArrayList<>();
      res.quotes().forEach(q -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plazo", q.getPlazoDias() + " días");
        m.put("tna", q.getTna()
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP));
        rows.add(m);
      });
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("asOf", res.asOf());
      out.put("data", rows);
      // cachear para próximas lecturas
      last.set(out);
      return out;
    } catch (Exception e) {
      // sin archivo o cualquier error → 200 con payload vacío
      return emptyPayload();
    }
  }

  private Map<String, Object> emptyPayload() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("asOf", Instant.now());
    out.put("data", List.of());
    return out;
  }

  // ===== POST (desde el WORKER) =====
  @PostMapping("/ingest")
  public ResponseEntity<Void> ingest(@RequestBody CaucionesPayload payload) {
    if (payload == null || payload.data == null) return ResponseEntity.badRequest().build();

    List<Map<String, Object>> rows = new ArrayList<>();
    for (CaucionRow r : payload.data) {
      if (r == null) continue;
      BigDecimal tna = r.tna;
      if (tna != null) {
        // si viene fracción (0.4015) → pasar a %
        if (tna.compareTo(BigDecimal.ONE) <= 0) {
          tna = tna.multiply(BigDecimal.valueOf(100));
        }
        tna = tna.setScale(2, RoundingMode.HALF_UP);
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("plazo", (r.plazo == null ? "" : r.plazo.trim()));
      m.put("tna", tna);
      rows.add(m);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("asOf", payload.asOf == null ? Instant.now() : payload.asOf);
    out.put("data", rows);

    // ✅ queda en memoria para el GET
    last.set(out);
    return ResponseEntity.ok().build();
  }

  // ===== DTOs del POST =====
  public static class CaucionesPayload {
    public Instant asOf;
    public List<CaucionRow> data;
  }
  public static class CaucionRow {
    public String plazo;     // ej: "7 días"
    public BigDecimal tna;   // ej: 39.80  ó 0.3980
  }
}
