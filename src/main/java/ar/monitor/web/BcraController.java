package ar.monitor.web;

import ar.monitor.model.BcraResponseDto;
import ar.monitor.model.SerieCalcDto;
import ar.monitor.service.BcraSeriesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/bcra")
public class BcraController {

    private final BcraSeriesService bcra;

    public BcraController(BcraSeriesService bcra) {
        this.bcra = bcra;
    }

    // Payload compacto (último/previo por serie)
    @GetMapping
    public ResponseEntity<?> bcra() {
        BcraResponseDto dto = bcra.buildResponse();
        if (dto == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(dto);
    }

    // Valor puntual de CER (compat)
    @GetMapping("/cer")
    public Map<String,Object> cer(@RequestParam(required = false) String date) {
        LocalDate d = (date != null && !date.isBlank()) ? LocalDate.parse(date) : LocalDate.now();
        Double v = bcra.get("CER", d);
        return Map.of("series", "CER", "date", d.toString(), "value", v);
    }

    // Valor puntual de cualquier serie (compat)
    @GetMapping("/series/{name}")
    public Map<String,Object> series(@PathVariable String name,
                                     @RequestParam(required = false) String date) {
        LocalDate d = (date != null && !date.isBlank()) ? LocalDate.parse(date) : LocalDate.now();
        Double v = bcra.get(name, d);
        return Map.of("series", name, "date", d.toString(), "value", v);
    }

    // Último + previo de una serie puntual
    @GetMapping("/calc/{name}")
    public ResponseEntity<?> calc(@PathVariable String name) {
        SerieCalcDto sc = bcra.computeSerie(name);
        if (sc == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(sc);
    }

    // *** TODAS las series completas (listas ordenadas) ***
    // opcional: ?from=YYYY-MM-DD&to=YYYY-MM-DD
    @GetMapping("/series")
    public ResponseEntity<?> allSeries(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        LocalDate f = (from != null && !from.isBlank()) ? LocalDate.parse(from) : null;
        LocalDate t = (to   != null && !to.isBlank())   ? LocalDate.parse(to)   : null;
        var res = bcra.getAllSeriesAsList(f, t);
        if (res.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(res);
    }

    // *** Una serie completa como lista [{date,value}, ...] ***
    // opcional: ?from=YYYY-MM-DD&to=YYYY-MM-DD
    @GetMapping("/series/{name}/all")
    public ResponseEntity<?> seriesAll(
            @PathVariable String name,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        LocalDate f = (from != null && !from.isBlank()) ? LocalDate.parse(from) : null;
        LocalDate t = (to   != null && !to.isBlank())   ? LocalDate.parse(to)   : null;
        var res = bcra.getSeriesAsList(name, f, t);
        if (res.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(res);
    }
}
