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

  // cache en memoria del último payload ingerido
  private final AtomicReference<Map<String, Object>> last = new AtomicReference<>();

  public CaucionesController(CaucionesProvider provider) {
    this.provider = provider;
  }

  // ======================= GET (para el FRONT) =======================
  @GetMapping
  public Map<String, Object> list() {
    // 1) Si hay algo en memoria (ingestado por el worker), devolverlo
    Map<String, Object> cached = last.get();
    if (cached != null) return cached;

    // 2) Fallback a tu provider actual (lee archivo local del backend)
    var res = provider.read();

    List<Map<String, Object>> rows = new ArrayList<>();
    for (var q : res.quotes()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("plazo", q.getPlazoDias() + " días");
      // devolvemos % con dos decimales (ej 40.15)
      m.put("tna", q.getTna().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
      rows.add(m);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("asOf", res.asOf());
    out.put("data", rows);

    // guardo por conveniencia en cache
    last.set(out);
    return out;
  }

  // ======================= POST (desde el WORKER) =======================
  @PostMapping("/ingest")
  public ResponseEntity<Void> ingest(@RequestBody CaucionesPayload payload) {
    if (payload == null || payload.data == null) return ResponseEntity.badRequest().build();

    // normalizo a la misma estructura que usa el GET
    List<Map<String, Object>> rows = new ArrayList<>();
    for (CaucionRow r : payload.data) {
      if (r == null) continue;

      String plazo = (r.plazo == null ? "" : r.plazo.trim());
      // Si llega tna en fracción (0.4015) lo paso a %; si ya viene en % (40.15) lo dejo
      BigDecimal tna = r.tna == null ? null : r.tna;
      if (tna != null) {
        if (tna.compareTo(BigDecimal.ONE) <= 0) {
          tna = tna.multiply(BigDecimal.valueOf(100));
        }
        tna = tna.setScale(2, RoundingMode.HALF_UP);
      }

      Map<String, Object> m = new LinkedHashMap<>();
      m.put("plazo", plazo);
      m.put("tna", tna);
      rows.add(m);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("asOf", payload.asOf == null ? Instant.now() : payload.asOf);
    out.put("data", rows);

    last.set(out); // ✅ queda listo para el GET
    return ResponseEntity.ok().build();
  }

  // ======= DTOs simples para deserializar el POST =======
  public static class CaucionesPayload {
    public Instant asOf;
    public List<CaucionRow> data;
  }
  public static class CaucionRow {
    public String plazo;       // ej: "7 días", "30 días"
    public BigDecimal tna;     // puede venir 40.15  (porcentaje) ó 0.4015 (fracción)
  }
}
