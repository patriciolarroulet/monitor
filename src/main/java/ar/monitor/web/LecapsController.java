// src/main/java/ar/monitor/web/LecapsController.java
package ar.monitor.web;

import ar.monitor.model.InstrumentoDto;
import ar.monitor.service.ExcelLecapsReader;
import ar.monitor.service.pricing.PriceService;
import ar.monitor.service.pricing.PriceQuote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/lecaps")
@CrossOrigin
public class LecapsController {

    private static final Logger log = LoggerFactory.getLogger(LecapsController.class);

    private static final String XLS_PATH  = "classpath:data/API LECAPS_con_comisiones.xlsx";
    private static final String XLS_SHEET = "Detalle";

    private static final ZoneId AR = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HHMMSS   = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int BASIS_DAYS = 365;

    // Ventana de mercado (ajustá si cambia)
    private static final LocalTime OPEN_TIME  = LocalTime.of(11, 0);
    private static final LocalTime CLOSE_TIME = LocalTime.of(17, 5);

    // Snapshot congelado post-mercado
    private volatile List<InstrumentoDto> lastSnapshot = Collections.emptyList();
    private volatile LocalDate lastSnapshotDate = null;

    private final ExcelLecapsReader reader;
    private final PriceService priceService;

    public LecapsController(ExcelLecapsReader reader, PriceService priceService) {
        this.reader = reader;
        this.priceService = priceService;
    }

    /** Lista completa para el monitor: fechas, días, merge con precios y tasas. */
    @GetMapping
    public ResponseEntity<List<InstrumentoDto>> getAll() {
        // Hora de referencia (si hay push, usamos su timestamp para la hora; si no, reloj)
        ZonedDateTime nowAr = ZonedDateTime.now(AR);
        if (priceService.hasLastUpdate()) {
            nowAr = priceService.getLastUpdateInstant().atZone(AR);
        }

        final LocalDate hoy = nowAr.toLocalDate();
        final LocalTime hora = nowAr.toLocalTime();
        final boolean marketOpen = isMarketOpen(hora);

        // 🔒 Si el mercado está cerrado y ya tenemos snapshot, devolvemos el congelado
        if (!marketOpen && lastSnapshot != null && !lastSnapshot.isEmpty()) {
            log.info("Mercado cerrado -> devolviendo snapshot congelado (fecha snapshot: {})", lastSnapshotDate);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(lastSnapshot);
        }

        // Si está abierto (o no hay snapshot todavía), generamos valores actuales
        LocalDate fechaLiq = nextBusinessDay(hoy);

        String fechaStr    = hoy.format(DDMMYYYY);
        String horaStr     = hora.truncatedTo(ChronoUnit.SECONDS).format(HHMMSS);
        String fechaLiqStr = fechaLiq.format(DDMMYYYY);

        // Excel: SOLO A..G (ticker→valor_final)
        List<InstrumentoDto> items = reader.load(XLS_PATH, XLS_SHEET);

        // Completar campos, mergear precio/volumen/%var y calcular tasas
        for (InstrumentoDto it : items) {
            // Fechas visibles
            it.setFecha(fechaStr);
            it.setHoraInput(horaStr);
            it.setFechaLiquidacion(fechaLiqStr);

            // Días al vto desde fecha de liquidación
            LocalDate vto = parseDateFlexible(it.getVencimiento());
            Integer dias = null;
            if (vto != null) {
                int d = (int) ChronoUnit.DAYS.between(fechaLiq, vto);
                dias = Math.max(d, 0);
            }
            it.setDiasAlVto(dias);

            // Merge desde PriceService (push)
            PriceQuote q = priceService.getQuote(it.getTicker());
            if (q != null) {
                // precio
                Double price = toDouble(q.getPrice());
                if (price != null) it.setPrecio(price);

                // % var diaria (viene del push) – si el mercado está abierto se actualiza;
                // si está cerrado, nunca llegamos acá porque devolvimos snapshot arriba.
                Double pct = toDouble(q.getPctChange());
                if (pct != null) it.setPctChange(pct);

                // volumen (si tu DTO lo expone como Double)
                try {
                    Double vol = toDouble(q.getVolume());
                    if (vol != null) {
                        var m = InstrumentoDto.class.getMethod("setVolumen", Double.class);
                        m.invoke(it, vol);
                    }
                } catch (NoSuchMethodException ignored) {
                    // tu DTO no expone volumen
                } catch (Exception ignored) { /* noop */ }

                // Log focalizado (ejemplo S29G5)
                if ("S29G5".equals(it.getTicker())) {
                    log.info("QUOTE IN S29G5 -> price={}, pctChange={}, volume={}",
                            q.getPrice(), q.getPctChange(), q.getVolume());
                }
            }

            // Tasas (si tenemos precio + VF + días)
            if (it.getPrecio() != null && it.getValorFinal() != null && dias != null && dias > 0) {
                double precio = it.getPrecio();
                double vf     = it.getValorFinal();
                double rp     = vf / precio - 1.0; // rendimiento total al vencimiento

                it.setTemBruta(Math.pow(1.0 + rp, 30.0 / dias) - 1.0);                 // 30 días
                it.setTnaSimpleBruta(rp * (BASIS_DAYS / (double) dias));                // base 365
                it.setTeaBruta(Math.pow(1.0 + rp, BASIS_DAYS / (double) dias) - 1.0);  // efectiva anual

                // Por ahora netas = brutas
                it.setTemNeta(it.getTemBruta());
                it.setTnaSimpleNeta(it.getTnaSimpleBruta());
                it.setTeaNeta(it.getTeaBruta());
            }
        }

        // Log de salida (comparación)
        items.stream()
             .filter(x -> "S29G5".equals(x.getTicker()))
             .findFirst()
             .ifPresent(x -> log.info("DTO OUT S29G5 -> precio={}, pct_change={}",
                     x.getPrecio(), x.getPctChange()));

        // 🧊 Si el mercado está abierto, actualizamos snapshot para congelar al cierre
        if (marketOpen) {
            lastSnapshot = Collections.unmodifiableList(new ArrayList<>(items)); // copia inmutable
            lastSnapshotDate = hoy;
            log.info("Snapshot actualizado ({})", lastSnapshotDate);
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(items);
    }

    /** Lista de tickers que consume el proceso de Python. */
    @GetMapping("/tickers")
    public ResponseEntity<List<String>> tickers() {
        try {
            List<String> tks = reader
                    .load(XLS_PATH, XLS_SHEET)
                    .stream()
                    .map(InstrumentoDto::getTicker)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .sorted()
                    .toList();

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(tks);
        } catch (Exception ex) {
            // Evita 500; el proceso Python seguirá sin filtro
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(Collections.emptyList());
        }
    }

    // ---------- helpers ----------
    private static boolean isMarketOpen(LocalTime t) {
        return !t.isBefore(OPEN_TIME) && t.isBefore(CLOSE_TIME);
    }

    private static LocalDate nextBusinessDay(LocalDate base) {
        LocalDate d = base.plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
        return d;
    }

    private static LocalDate parseDateFlexible(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/yyyy")); } catch (Exception ignored) {}
        try { return LocalDate.parse(s); } catch (Exception ignored) {}
        if (s.length() >= 10) { try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception ignored) {} }
        return null;
    }

    /** Convierte cualquier Number (BigDecimal/Double/Integer/Long) a Double; null-safe. */
    private static Double toDouble(Number n) {
        return n == null ? null : n.doubleValue();
    }
}
