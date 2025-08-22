package ar.monitor.service;

import ar.monitor.model.DolaresDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class DolaresService {

    private static final URI API_MEP      = URI.create("https://dolarapi.com/v1/dolares/mep");
    private static final URI API_CCL      = URI.create("https://dolarapi.com/v1/dolares/ccl");
    private static final URI API_OFICIAL  = URI.create("https://dolarapi.com/v1/dolares/oficial");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ObjectMapper om = new ObjectMapper();

    public DolaresDto fetch() {
        BigDecimal mep     = fetchOne(API_MEP);
        BigDecimal ccl     = fetchOne(API_CCL);
        BigDecimal oficial = fetchOne(API_OFICIAL);

        if (mep == null || ccl == null || oficial == null) {
            DolaresDto fb = fallback();
            if (mep == null)     mep = fb.getMep();
            if (ccl == null)     ccl = fb.getCcl();
            if (oficial == null) oficial = fb.getOficial();
        }
        return new DolaresDto(mep, ccl, oficial);
    }

    public DolaresDto fallback() {
        return new DolaresDto(new BigDecimal("1500"), new BigDecimal("1650"), new BigDecimal("1250"));
    }

    private BigDecimal fetchOne(URI uri) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Monitor-Lecaps/1.0")
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) return null;

            JsonNode root = om.readTree(res.body());
            String[] keys = {"venta", "sell", "promedio", "value_avg", "value_average"};
            for (String k : keys) {
                JsonNode n = root.get(k);
                if (n != null && n.isNumber()) return n.decimalValue();
                if (n != null && n.isTextual()) {
                    try { return new BigDecimal(n.asText().replace(",", ".")); } catch (Exception ignored) {}
                }
            }
            return null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }
}