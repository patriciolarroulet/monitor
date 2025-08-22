package ar.monitor.service.pricing;

import ar.monitor.until.Tickers;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.HashMap;

/**
 * Servicio en memoria de últimas cotizaciones por ticker normalizado.
 * Usa PriceQuote top-level (ar.monitor.service.pricing.PriceQuote).
 */
@Service
public class PriceService {

    /** ticker normalizado -> última cotización */
    private final Map<String, PriceQuote> byTicker = new ConcurrentHashMap<>();

    /** timestamp del último update recibido (cualquier ticker) */
    private volatile Instant lastUpdateInstant;

    // =======================
    //   INGESTA / UPSERT
    // =======================

    /** Upsert preferido con BigDecimal. */
    public void upsertQuote(String ticker,
                            BigDecimal price,
                            Long qtyOperated,
                            BigDecimal volume,
                            String source,
                            String horaInput,
                            String fechaInput,
                            Instant ts) {
        if (ticker == null) return;
        String key = Tickers.normalize(ticker);

        // Construimos PriceQuote usando el ctor "B (nuevo)" (pctChange explícito = null)
        PriceQuote quote = new PriceQuote(
                price,
                qtyOperated == null ? 0L : qtyOperated,
                volume,
                /* pctChange */ null,
                source,
                horaInput,
                fechaInput
        );

        byTicker.put(key, quote);
        lastUpdateInstant = (ts != null) ? ts : Instant.now();
    }

    /** Overload práctico con doubles. */
    public void upsertQuote(String ticker,
                            double price,
                            long qtyOperated,
                            double volume,
                            String source,
                            String horaInput,
                            String fechaInput,
                            Instant ts) {
        upsertQuote(ticker,
                BigDecimal.valueOf(price),
                qtyOperated,
                BigDecimal.valueOf(volume),
                source,
                horaInput,
                fechaInput,
                ts);
    }

    /** Upsert mínimo (ticker + price). */
    public void upsertQuote(String ticker, double price) {
        upsertQuote(ticker, BigDecimal.valueOf(price), 0L, null, "PY", null, null, Instant.now());
    }

    // =======================
    //         LECTURA
    // =======================

    /** Última cotización para el ticker (normaliza internamente). */
    public PriceQuote getQuote(String ticker) {
        if (ticker == null) return null;
        return byTicker.get(Tickers.normalize(ticker));
    }

    /** Timestamp del último push recibido. */
    public Instant getLastUpdateInstant() {
        return lastUpdateInstant;
    }

    /** True si ya recibimos al menos un push. */
    public boolean hasLastUpdate() {
        return lastUpdateInstant != null;
    }

    // =======================
    //  COMPAT / DEBUG SUPPORT
    // =======================

    /**
     * Snapshot de PRECIOS (Map<String, BigDecimal>) para compat con DebugController.
     * Devuelve un nuevo mapa inmutable con el último "price" por ticker.
     */
    public Map<String, BigDecimal> snapshot() {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map.Entry<String, PriceQuote> e : byTicker.entrySet()) {
            BigDecimal p = (e.getValue() != null) ? e.getValue().getPrice() : null;
            if (p != null) out.put(e.getKey(), p);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Snapshot de QUOTES completos (si alguna parte del código necesita los PriceQuote).
     */
    public Map<String, PriceQuote> snapshotQuotes() {
        return Collections.unmodifiableMap(new HashMap<>(byTicker));
    }

    /**
     * Reemplaza el snapshot completo (compat con IngestController).
     * Normaliza claves y actualiza lastUpdateInstant.
     */
    public void overwriteSnapshot(Map<String, PriceQuote> newMap) {
        byTicker.clear();
        if (newMap != null) {
            for (Map.Entry<String, PriceQuote> e : newMap.entrySet()) {
                String key = Tickers.normalize(e.getKey());
                byTicker.put(key, e.getValue());
            }
        }
        lastUpdateInstant = Instant.now();
    }
}
