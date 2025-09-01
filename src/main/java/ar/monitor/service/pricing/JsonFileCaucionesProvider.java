package ar.monitor.service.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ar.monitor.model.CaucionQuote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

@Component   // 👈 ESTE ANOTA Spring lo autodetecta
public class JsonFileCaucionesProvider implements CaucionesProvider {

  private final Path jsonPath;
  private final ObjectMapper om = new ObjectMapper();

  public JsonFileCaucionesProvider(@Value("${cauciones.json.path}") String jsonPath) {
    this.jsonPath = Path.of(jsonPath);
  }

  @Override
  public Result read() {
    try {
      JsonNode root = om.readTree(new File(jsonPath.toString()));
      String asOf    = root.path("asOf").asText(null);
      String mercado = root.path("mercado").asText("BYMA");
      String moneda  = root.path("moneda").asText("ARS");

      List<CaucionQuote> list = new ArrayList<>();
      for (JsonNode q : root.path("quotes")) {
        Integer d = q.path("plazoDias").isNumber() ? q.get("plazoDias").asInt() : null;
        BigDecimal tna = q.path("tna").isNumber() ? new BigDecimal(q.get("tna").asText()) : null;
        if (d != null && tna != null) {
          list.add(new CaucionQuote(d, tna, null, mercado, moneda, asOf));
        }
      }
      list.sort(Comparator.comparingInt(CaucionQuote::getPlazoDias));
      return new Result(asOf, mercado, moneda, list);
    } catch (Exception e) {
      throw new RuntimeException("No se pudo leer cauciones JSON: " + jsonPath, e);
    }
  }
}
