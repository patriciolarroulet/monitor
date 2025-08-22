package ar.monitor.service;

import ar.monitor.model.CaucionDto;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CaucionesService {

    private static final String SOURCE_URL = "https://dalfie.ar/ccl/";

    // Match ejemplos:
    // "1 día 27,50 %", "7 días 41,5 %", "28 días 43 %", admite saltos/espacios
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "(?<dias>\\b\\d{1,3})\\s*d[ií]a(?:s)?\\b[^\\d%]*(?<tna>\\d{1,3}(?:[\\.,]\\d{1,3})?)\\s*%?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final List<Integer> TARGET_DAYS = Arrays.asList(1, 7, 14, 21, 28, 35, 42);

    public List<CaucionDto> fetchCauciones() throws IOException {
        Map<Integer, BigDecimal> map = new HashMap<>();

        // 1) Intento estándar: DOM
        try {
            Document doc = baseConn().get();
            parseFromDocument(doc, map);
        } catch (IOException ignored) {
            // seguimos con fallback crudo
        }

        // 2) Fallback: body crudo (por si se arma con JS o sin <table>)
        if (map.isEmpty()) {
            String body = baseConn()
                    .ignoreContentType(true)
                    .maxBodySize(0)
                    .execute()
                    .body();
            parseFromText(body, map);
        }

        // 3) Salida ordenada
        List<CaucionDto> out = new ArrayList<>();
        for (Integer d : TARGET_DAYS) {
            BigDecimal tna = map.get(d);
            if (tna != null) out.add(new CaucionDto(d + " días", tna));
        }
        return out;
    }

    private Connection baseConn() {
        return Jsoup.connect(SOURCE_URL)
                .followRedirects(true)
                .timeout(15000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .referrer("https://www.google.com")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache");
    }

    private void parseFromDocument(Document doc, Map<Integer, BigDecimal> map) {
        if (doc == null) return;

        // 1) Tablas
        Elements tables = doc.select("table");
        for (Element t : tables) {
            String all = normalizeSpaces(t.text()).toLowerCase();
            if (all.contains("caucion") || all.contains("cauciones") || all.contains("tna")) {
                for (Element tr : t.select("tr")) {
                    parseLineIntoMap(normalizeSpaces(tr.text()), map);
                }
            }
        }

        // 2) Texto general como respaldo
        if (map.isEmpty()) {
            parseFromText(normalizeSpaces(doc.text()), map);
        }
    }

    private void parseFromText(String text, Map<Integer, BigDecimal> map) {
        if (text == null) return;
        text = normalizeSpaces(text);
        Matcher m = LINE_PATTERN.matcher(text);
        while (m.find()) {
            int dias = safeInt(m.group("dias"));
            if (!TARGET_DAYS.contains(dias)) continue;
            BigDecimal tna = parseArgBig(m.group("tna"));
            if (tna != null) map.put(dias, tna);
        }
    }

    private void parseLineIntoMap(String line, Map<Integer, BigDecimal> map) {
        if (line == null || line.isEmpty()) return;
        line = normalizeSpaces(line);
        Matcher m = LINE_PATTERN.matcher(line);
        while (m.find()) {
            int dias = safeInt(m.group("dias"));
            if (!TARGET_DAYS.contains(dias)) continue;
            BigDecimal tna = parseArgBig(m.group("tna"));
            if (tna != null) map.put(dias, tna);
        }
    }

    private String normalizeSpaces(String s) {
        if (s == null) return null;
        // reemplaza NBSP y espacios raros por espacio normal
        return s.replace('\u00A0', ' ').replaceAll("[\\s\\u2007\\u202F]+", " ").trim();
    }

    private int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private BigDecimal parseArgBig(String s) {
        if (s == null) return null;
        s = s.trim();

        // Normalizar miles/decimales:
        // "1.234,56" -> "1234.56", "43,8" -> "43.8", "43" -> "43"
        if (s.contains(",") && s.contains(".")) {
            s = s.replace(".", "").replace(',', '.');
        } else if (s.contains(",")) {
            s = s.replace(',', '.');
        }
        try {
            return new BigDecimal(s);
        } catch (Exception ignore) {
            // Fallback con DecimalFormat (coma decimal)
            DecimalFormatSymbols sym = new DecimalFormatSymbols();
            sym.setDecimalSeparator(',');
            DecimalFormat df = new DecimalFormat("#,##0.###", sym);
            try {
                return new BigDecimal(df.parse(s).toString());
            } catch (Exception e) {
                return null;
            }
        }
    }

    /* ===== Utilidad de testing local: valores fijos ===== */
    public List<CaucionDto> sample() {
        return Arrays.asList(
                new CaucionDto("1 días",  new BigDecimal("27.50")),
                new CaucionDto("7 días",  new BigDecimal("41.50")),
                new CaucionDto("14 días", new BigDecimal("42.50")),
                new CaucionDto("21 días", new BigDecimal("38.60")),
                new CaucionDto("28 días", new BigDecimal("43.88")),
                new CaucionDto("35 días", new BigDecimal("35.00")),
                new CaucionDto("42 días", new BigDecimal("32.83"))
        );
    }
}
