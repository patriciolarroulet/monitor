package ar.monitor.web;

import java.math.BigDecimal;

public record CaucionQuotePost(Integer plazoDias, BigDecimal tna) {
  // tna VIENE EN FRACCIÓN (0.425 = 42.5%)
}
