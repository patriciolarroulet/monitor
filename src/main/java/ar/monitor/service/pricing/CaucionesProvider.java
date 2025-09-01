package ar.monitor.service.pricing;

import ar.monitor.model.CaucionQuote;
import java.util.List;

public interface CaucionesProvider {
  record Result(String asOf, String mercado, String moneda, List<CaucionQuote> quotes) {}
  Result read();
}
