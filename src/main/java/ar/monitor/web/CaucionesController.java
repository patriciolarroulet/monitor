package ar.monitor.web;

import ar.monitor.service.pricing.CaucionesProvider;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cauciones")
public class CaucionesController {

  private final CaucionesProvider provider;

  public CaucionesController(CaucionesProvider provider) {
    this.provider = provider;
  }

  @GetMapping
  public Map<String, Object> list() {
    var res = provider.read();

    List<Map<String, Object>> rows = new ArrayList<>();
    for (var q : res.quotes()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("plazo", q.getPlazoDias() + " días");
      m.put("tna", q.getTna().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
      rows.add(m);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("asOf", res.asOf());
    out.put("data", rows);
    return out;
  }
}
