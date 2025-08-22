package ar.monitor.web;

import ar.monitor.service.pricing.PriceQuote;
import ar.monitor.service.pricing.PriceService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
@CrossOrigin // si lo vas a llamar desde otra máquina/origen
public class IngestController {

    private final PriceService priceService;

    public IngestController(PriceService priceService) {
        this.priceService = priceService;
    }

    // DTO que esperamos recibir desde Python
    public static class PriceIngestItem {
        @JsonProperty("ticker") public String ticker;

        // Podés mandar uno de estos dos enfoques:
        // (A) price + previous_price (server calcula pctChange)
        @JsonProperty("price") public BigDecimal price;
        @JsonProperty("previous_price") public BigDecimal previousPrice;

        // (B) price + pct_change explícito (si ya lo calculás en Python)
        @JsonProperty("pct_change") public BigDecimal pctChange;

        // Campos opcionales del monitor:
        @JsonProperty("q_op") public Long qOp;           // cantidad
        @JsonProperty("v")    public BigDecimal volume;  // volumen
        @JsonProperty("fuente") public String fuente;    // "PY", "API", etc.
        @JsonProperty("hora_input") public String horaInput; // "HH:mm:ss"
        @JsonProperty("fecha_input") public String fechaInput; // "dd/MM/yyyy" (o "yyyy-MM-dd" si te resulta más cómodo)

    }

    @PostMapping("/prices")
    public ResponseEntity<?> ingest(@RequestBody List<PriceIngestItem> payload) {
        if (payload == null || payload.isEmpty()) {
            return ResponseEntity.badRequest().body("payload vacío");
        }

        Map<String, PriceQuote> snap = new HashMap<>();

        for (PriceIngestItem it : payload) {
            if (it == null || it.ticker == null || it.price == null) continue;

            PriceQuote q;
            if (it.pctChange != null) {
                // Constructor B: con pctChange explícito
                q = new PriceQuote(
                        it.price,
                        it.qOp == null ? 0L : it.qOp,
                        it.volume,
                        it.pctChange,
                        it.fuente == null ? "PY" : it.fuente,
                        it.horaInput
                );
            } else {
                // Constructor A: con previous_price (server calcula variación)
                q = new PriceQuote(
                        it.price,
                        it.previousPrice,
                        it.qOp == null ? 0L : it.qOp,
                        it.volume,
                        it.fuente == null ? "PY" : it.fuente,
                        it.horaInput
                );
            }

            snap.put(it.ticker.trim().toUpperCase(), q);
        }

        if (snap.isEmpty()) {
            return ResponseEntity.badRequest().body("ningún item válido");
        }

        priceService.overwriteSnapshot(snap);
        return ResponseEntity.ok(Map.of("ok", true, "count", snap.size()));
    }
    @GetMapping("/ping")
    public Map<String, Object> ping() {
      return Map.of("ok", true, "path", "/api/ingest/ping");
    }   
}
