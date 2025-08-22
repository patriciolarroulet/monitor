package ar.monitor.web;

import ar.monitor.model.CaucionDto;
import ar.monitor.model.DolaresDto;
import ar.monitor.model.FuturoDto;
import ar.monitor.service.CaucionesService;
import ar.monitor.service.DolaresService;
import ar.monitor.service.FuturosService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/externals")
@CrossOrigin
public class ExternalsController {

    private final CaucionesService caucionesService;
    private final FuturosService futurosService;
    private final DolaresService dolaresService;

    public ExternalsController(
            CaucionesService caucionesService,
            FuturosService futurosService,
            DolaresService dolaresService
    ) {
        this.caucionesService = caucionesService;
        this.futurosService   = futurosService;
        this.dolaresService   = dolaresService;
    }

    @GetMapping("/cauciones")
    public ResponseEntity<List<CaucionDto>> cauciones() {
        try {
            return ResponseEntity.ok(caucionesService.fetchCauciones());
        } catch (IOException e) {
            return ResponseEntity.status(502).body(Collections.emptyList());
        }
    }

    @GetMapping("/futuros")
    public ResponseEntity<List<FuturoDto>> futuros() {
        return ResponseEntity.ok(futurosService.fetchFuturos());
    }

    @GetMapping("/dolares")
    public ResponseEntity<DolaresDto> dolares() {
        try {
            return ResponseEntity.ok(dolaresService.fetch());
        } catch (Exception e) {
            // devolvemos fallback 200 para que el front no “parpadee”
            return ResponseEntity.ok(dolaresService.fallback());
        }
    }
}
