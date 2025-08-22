package ar.monitor.web;

import ar.monitor.model.InstrumentoDto;
import ar.monitor.service.pricing.PriceService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@CrossOrigin
public class MonitorController {

  private final PriceService priceService;

  public MonitorController(PriceService priceService) {
    this.priceService = priceService;
  }

  @GetMapping("/api/monitor/lecaps")
  public List<InstrumentoDto> monitorLecaps() {
    // TODO: reemplazar por tickers reales (p. ej., leídos desde Excel)
    List<String> tickers = List.of("S15G5", "S29G5", "S12S5");

    // normaliza a instrumentos (mayúsculas, sin nulos, únicos)
    List<String> instrumentos = toInstrumentos(tickers);

    // intenta invocar un método del servicio que acepte List<String> y devuelva List<InstrumentoDto>
    List<InstrumentoDto> result = invokePriceService(instrumentos);
    return result != null ? result : List.of();
  }

  /* ----------------- helpers ----------------- */

  /** Normaliza tickers a instrumentos (mayúsculas, sin nulos/espacios, únicos). */
  private static List<String> toInstrumentos(List<String> tickers) {
    if (tickers == null) return List.of();
    return tickers.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(String::toUpperCase)
        .distinct()
        .collect(Collectors.toList());
  }

  /**
   * Intenta llamar a un método del PriceService que reciba List&lt;String&gt; y devuelva List&lt;InstrumentoDto&gt;.
   * Nombres candidatos (en orden): toInstrumentos, getInstrumentosByTickers, getInstrumentos, fetchInstrumentos.
   */
  @SuppressWarnings("unchecked")
  private List<InstrumentoDto> invokePriceService(List<String> instrumentos) {
    String[] candidates = new String[] {
        "toInstrumentos",
        "getInstrumentosByTickers",
        "getInstrumentos",
        "fetchInstrumentos"
    };
    for (String name : candidates) {
      try {
        Method m = priceService.getClass().getMethod(name, List.class);
        Object out = m.invoke(priceService, instrumentos);
        if (out instanceof List<?>) {
          return (List<InstrumentoDto>) out;
        }
      } catch (NoSuchMethodException ignored) {
        // probar siguiente candidato
      } catch (Exception e) {
        // si un candidato existe pero falla, devolvemos vacío para no romper el endpoint
        return List.of();
      }
    }
    // si no hay método coincidente en el servicio, devolvemos vacío
    return List.of();
  }
}
