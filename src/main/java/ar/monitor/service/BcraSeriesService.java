package ar.monitor.service;

import ar.monitor.model.BcraResponseDto;
import ar.monitor.model.SerieCalcDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BcraSeriesService {

  private final ResourceLoader loader;
  private final ObjectMapper om = new ObjectMapper();
  private volatile long lastLoadTs = 0L;
  private static final long TTL_MS = 60_000;

  @Value("${bcra.json.path:file:output/bcra.json}")
  private String bcraJsonPath;

  private final Map<String, Map<String, Double>> series = new ConcurrentHashMap<>();

  public BcraSeriesService(ResourceLoader loader) { this.loader = loader; }

  private void ensureLoaded() {
  long now = System.currentTimeMillis();
  if (now - lastLoadTs < TTL_MS && !series.isEmpty()) return;

  try {
    Resource res = loader.getResource(bcraJsonPath);
    if (!res.exists() || !res.isReadable()) {
      System.err.println("[BCRA LOAD] No existe/ilegible: " + bcraJsonPath);
      return;
    }
    String raw;
    try (InputStream in = res.getInputStream()) {
      raw = new String(in.readAllBytes());
    }
    if (raw == null || raw.isBlank()) {
      System.err.println("[BCRA LOAD] Archivo vacío en: " + bcraJsonPath);
      return;
    }
    JsonNode root = om.readTree(raw);
    JsonNode s = root.path("series");
    if (s.isMissingNode() || !s.fields().hasNext()) {
      System.err.println("[BCRA LOAD] JSON sin 'series' o vacío: " + bcraJsonPath);
      return;
    }
    Map<String, Map<String, Double>> tmp = new ConcurrentHashMap<>();
    s.fields().forEachRemaining(e -> {
      Map<String, Double> m = new ConcurrentHashMap<>();
      e.getValue().fields().forEachRemaining(p ->
        m.put(p.getKey(), p.getValue().isNumber() ? p.getValue().doubleValue() : null)
      );
      tmp.put(e.getKey(), m);
      System.out.println("[BCRA LOAD] " + e.getKey() + " -> " + m.size() + " puntos");
    });
    series.clear();
    series.putAll(tmp);
    lastLoadTs = now;
    System.out.println("[BCRA LOAD] OK path=" + bcraJsonPath + " keys=" + series.keySet());
  } catch (Exception e) {
    System.err.println("[BCRA LOAD] Error leyendo " + bcraJsonPath + ": " + e);
    e.printStackTrace();
  }
}

  public Double get(String serie, LocalDate date){
    ensureLoaded();
    Map<String, Double> m = series.get(serie);
    if (m == null) return null;
    Double v = m.get(date.toString());
    if (v != null) return v;
    for (int i=1; i<=5; i++){
      v = m.get(date.minusDays(i).toString());
      if (v != null) return v;
    }
    return null;
  }

  /** CER de 10 días hábiles antes de la fecha de liquidación (sólo saltea sáb/dom). */
  public Double getCerForLiquidation(LocalDate liquidacion){
    if (liquidacion == null) return null;
    LocalDate ref = minusBusinessDaysSimple(liquidacion, 10);
    return get("CER", ref);
  }

  public static LocalDate minusBusinessDaysSimple(LocalDate date, int n) {
    LocalDate d = date;
    int left = n;
    while (left > 0){
      d = d.minusDays(1);
      switch (d.getDayOfWeek()){
        case SATURDAY, SUNDAY -> {}
        default -> left--;
      }
    }
    return d;
  }

  public Double getA3500(LocalDate date){ return get("A3500", date); }
  public Double getTAMAR(LocalDate date){ return get("TAMAR", date); }

  // --------- NUEVO: cálculo último/previo para endpoint /api/bcra ---------

  /** Snapshot defensivo de todas las series crudas. */
  public Map<String, Map<String, Double>> getAllSeries() {
    ensureLoaded();
    Map<String, Map<String, Double>> out = new LinkedHashMap<>();
    for (var e : series.entrySet()) {
      out.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
    }
    return out;
  }

  /** Calcula último + previo + deltas para una serie (p.ej., "CER"). */
  public SerieCalcDto computeSerie(String code) {
    ensureLoaded();
    Map<String, Double> raw = series.get(code);
    if (raw == null || raw.isEmpty()) return null;

    TreeMap<LocalDate, Double> sorted = new TreeMap<>();
    for (var e : raw.entrySet()) {
      try { sorted.put(LocalDate.parse(e.getKey()), e.getValue()); } catch (Exception ignored) {}
    }
    if (sorted.isEmpty()) return null;

    LocalDate last = sorted.lastKey();
    Double lastVal = sorted.get(last);

    LocalDate prev = null; Double prevVal = null;
    var it = sorted.descendingKeySet().iterator();
    if (it.hasNext()) it.next(); // consume last
    if (it.hasNext()) { prev = it.next(); prevVal = sorted.get(prev); }

    Double delta = (prevVal != null) ? (lastVal - prevVal) : null;
    Double deltaPct = (prevVal != null && prevVal != 0.0) ? (delta / prevVal) : null;

    return new SerieCalcDto(
        code,
        last.toString(),
        lastVal,
        (prev != null ? prev.toString() : null),
        prevVal,
        delta,
        deltaPct
    );
  }

  /** Construye el DTO completo que consumirá el front. */
  public BcraResponseDto buildResponse() {
    ensureLoaded();
    if (series.isEmpty()) return null;

    Map<String, SerieCalcDto> calc = new LinkedHashMap<>();
    for (String k : series.keySet()) {
      SerieCalcDto sc = computeSerie(k);
      if (sc != null) calc.put(k, sc);
    }
    if (calc.isEmpty()) return null;

    String ts = ZonedDateTime.now(ZoneId.of("America/Argentina/Buenos_Aires")).toString();
    return new BcraResponseDto(ts, "bcra.json", calc);
  }
// ==== RAW SERIES (ordenadas) ====

// Devuelve lista [{date:"YYYY-MM-DD", value:Double}] para JSON limpio
public java.util.List<java.util.Map<String,Object>> getSeriesAsList(
    String code, java.time.LocalDate from, java.time.LocalDate to
) {
  var sorted = getSeriesSorted(code);
  if (sorted.isEmpty()) return java.util.List.of();
  var sub = (from == null && to == null)
      ? sorted
      : sorted.subMap(
          (from != null ? from : sorted.firstKey()),
          true,
          (to   != null ? to   : sorted.lastKey()),
          true
        );
  java.util.List<java.util.Map<String,Object>> out = new java.util.ArrayList<>(sub.size());
  for (var e : sub.entrySet()) {
    out.add(java.util.Map.of("date", e.getKey().toString(), "value", e.getValue()));
  }
  return out;
}

// Devuelve la serie completa {fecha -> valor}, ordenada asc por fecha (TreeMap)
public java.util.NavigableMap<java.time.LocalDate, Double> getSeriesSorted(String code) {
  ensureLoaded();
  Map<String, Double> raw = series.get(code);
  if (raw == null || raw.isEmpty()) return new java.util.TreeMap<>();
  java.util.TreeMap<java.time.LocalDate, Double> sorted = new java.util.TreeMap<>();
  for (var e : raw.entrySet()) {
    try { sorted.put(java.time.LocalDate.parse(e.getKey()), e.getValue()); } catch (Exception ignored) {}
  }
  return sorted;
}

// Devuelve todas las series disponibles como { nombre -> [ {date,value}, ... ] }
public java.util.Map<String, java.util.List<java.util.Map<String,Object>>> getAllSeriesAsList(
    java.time.LocalDate from, java.time.LocalDate to
) {
  ensureLoaded();
  java.util.Map<String, java.util.List<java.util.Map<String,Object>>> out = new java.util.LinkedHashMap<>();
  for (String k : series.keySet()) {
    out.put(k, getSeriesAsList(k, from, to));
  }
  return out;
}

}
