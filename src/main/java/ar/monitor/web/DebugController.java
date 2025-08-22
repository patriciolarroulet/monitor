package ar.monitor.web;

import ar.monitor.service.pricing.PriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
public class DebugController {
  private final PriceService priceService;
  public DebugController(PriceService priceService) { this.priceService = priceService; }

  @GetMapping("/api/_live")
  public Map<String, BigDecimal> live() {
    return priceService.snapshot();
  }
}
