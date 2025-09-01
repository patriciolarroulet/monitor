package ar.monitor.web;

import ar.monitor.service.FuturosService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/futuros")
@CrossOrigin
public class FuturosController {

  private final FuturosService service;

  public FuturosController(FuturosService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<?> getFuturos() {
    Map<String,Object> payload = service.readAsMap();
    if (payload == null) return ResponseEntity.noContent().build();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(10, TimeUnit.SECONDS))
        .body(payload);
  }
}
