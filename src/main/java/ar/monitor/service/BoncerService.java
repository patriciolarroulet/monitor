package ar.monitor.service;

import ar.monitor.model.BoncerDto;
import ar.monitor.service.pricing.PriceService;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * BONCER (CER)
 * Lee flujos desde: classpath:data/API LECAPS_con_comisiones.xlsx (hoja "BONCER")
 * Calcula: TIREA, TNA, Duration, Modified Duration, Convexity, WAL, Valor Técnico, Paridad, AI.
 * VT usa CER de 10 días hábiles antes de la fecha de liquidación (BcraSeriesService).
 * DICP: aplica factor de capitalización -> VR_ajustado = VR / (factor * 100).
 */
@Service
public class BoncerService {

  // ======= RESPONSE interna (sin crear otro archivo) =======
  public static class BoncerResponse {
    public long   updated_ts;
    public Long   liquidacion_ts; // puede ser null
    public List<BoncerDto> rows;
    public static BoncerResponse of(long updated, Long liq, List<BoncerDto> rows){
      BoncerResponse r = new BoncerResponse();
      r.updated_ts = updated;
      r.liquidacion_ts = liq;
      r.rows = rows;
      return r;
    }
  }

  private final ResourceLoader loader;

  // ===== Config =====
  private static final String EXCEL_PATH = "classpath:data/API LECAPS_con_comisiones.xlsx";
  private static final String SHEET_NAME = "BONCER";
  private static final int    T_PLUS     = 1; // fallback T+1

  private static final Locale AR = Locale.forLanguageTag("es-AR");
  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  @Autowired private BusinessCalendar  calendar;
  @Autowired private BcraSeriesService bcra;
  @Autowired(required = false) private PriceService priceService; // opcional

  public BoncerService(ResourceLoader loader) { this.loader = loader; }

  // ===== Tipos internos =====
  static class Cashflow { LocalDate date; double amount; double principal; }
  static class BondBundle {
    String ticker, isin, emisor;
    List<Cashflow> cfs = new ArrayList<>();
    Double vr, coupon, cerInicial;
    Double capFactor;              // factor de capitalización (1 si no aplica)
    LocalDate maturity;
  }

  // ====== Market Price Injection (push_prices) ======
private static java.lang.reflect.Method m(Object o, String name, Class<?>... sig) {
  try { return o.getClass().getMethod(name, sig); } catch (NoSuchMethodException e) { return null; }
}
private static Double asD(Object v) {
  if (v == null) return null;
  if (v instanceof java.util.OptionalDouble od) return od.isPresent()? od.getAsDouble(): null;
  if (v instanceof java.util.Optional<?> opt) {
    if (opt.isEmpty()) return null;
    Object x = opt.get();
    if (x instanceof Number n) return n.doubleValue();
    if (x instanceof CharSequence s) { try { return Double.valueOf(s.toString().trim().replace(",", ".")); } catch(Exception ignore){} }
    return null;
  }
  if (v instanceof Number n) return n.doubleValue();
  if (v instanceof CharSequence s) { try { return Double.valueOf(s.toString().trim().replace(",", ".")); } catch(Exception ignore){} }
  return null;
}

/** Intenta obtener un "quote" del PriceService (lastQuote/getQuote/quote/getLastQuote/findQuote). */
private Object fetchQuote(String ticker) {
  if (priceService == null) return null;
  for (String meth : new String[]{"lastQuote","getQuote","quote","getLastQuote","findQuote"}) {
    var mm = m(priceService, meth, String.class);
    if (mm == null) continue;
    try {
      Object q = mm.invoke(priceService, ticker);
      if (q != null) return q;
    } catch (Exception ignore) {}
  }
  return null;
}

/** Lee un double de un objeto llamando al primer getter disponible. */
private Double getterDouble(Object obj, String... getters) {
  if (obj == null) return null;
  for (String g : getters) {
    var mm = m(obj, g);
    if (mm == null) continue;
    try { return asD(mm.invoke(obj)); } catch (Exception ignore) {}
  }
  return null;
}

/** Completa precioSucio y variacionDiaria desde PriceService/PriceQuote, si faltan. */
private void completeMarketFields(BoncerDto dto) {
  if (dto == null) return;
  String tick = (dto.ticker == null) ? null : dto.ticker.trim().toUpperCase(java.util.Locale.ROOT);
  if (tick == null || tick.isEmpty()) return;

  Object quote = fetchQuote(tick);

  // --- precio sucio ---
  if (dto.precioSucio == null || dto.precioSucio <= 0.0) {
    Double px = getterDouble(quote, "getDirtyPrice","getLast","getPrice","getClose");
    if (px == null && priceService != null) {
      for (String meth : new String[]{"lastDirty","lastDirtyPrice","lastPrice","getLast","getLastPrice"}) {
        var mm = m(priceService, meth, String.class);
        if (mm == null) continue;
        try { px = asD(mm.invoke(priceService, tick)); if (px != null) break; } catch (Exception ignore) {}
      }
    }
    if (px != null && px > 0) dto.precioSucio = px;
  }

  // --- variación diaria (%)
  if (dto.variacionDiaria == null) {
    Double pct = getterDouble(quote, "getChangePct","getPctChange","getChange","getDeltaPct");
    if (pct == null && priceService != null) {
      for (String meth : new String[]{"pctChange","changePct","lastChangePct","getChangePct"}) {
        var mm = m(priceService, meth, String.class);
        if (mm == null) continue;
        try { pct = asD(mm.invoke(priceService, tick)); if (pct != null) break; } catch (Exception ignore) {}
      }
    }
    if (pct != null) dto.variacionDiaria = pct;
  }
}

/** Aplica la inyección de mercado a toda la lista. */
private java.util.List<BoncerDto> completeMarketFields(java.util.List<BoncerDto> list) {
  if (list == null) return null;
  for (BoncerDto r : list) completeMarketFields(r);
  return list;
}

  // ===== API =====
  public BoncerResponse load() {
    List<BoncerDto> out = new ArrayList<>();
    Long liqTs;

    try {
      Resource res = loader.getResource(EXCEL_PATH);
      try (InputStream in = res.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
        Sheet sh = wb.getSheet(SHEET_NAME);
        if (sh == null) return BoncerResponse.of(now(), null, out);

        DataFormatter df = new DataFormatter(AR, true);

        // Buscar fila de encabezados (tomamos la primera no vacía)
        int headerRowIdx = sh.getFirstRowNum();
        Row head = sh.getRow(headerRowIdx);
        if (head == null) return BoncerResponse.of(now(), null, out);

        Map<String,Integer> idx = mapHeaders(head, df);

        // Aliases tolerantes (espacios, acentos y snake-case)
        final Map<String,String> alias = new HashMap<>();
        // VR
        alias.put("vr","vr"); alias.put("valor nominal","vr"); alias.put("valor residual","vr");
        // interés
        alias.put("interes","interes"); alias.put("interés","interes");
        // CER inicial
        alias.put("cer inicial","cer_inicial"); alias.put("cer_inicial","cer inicial"); alias.put("cer","cer_inicial");
        // precio limpio/sucio
        alias.put("precio limpio","precio_limpio"); alias.put("precio_limpio","precio limpio");
        alias.put("precio sucio","precio_sucio");   alias.put("precio_sucio","precio sucio");
        // cupón
        alias.put("cupón","cupon"); alias.put("cupon","cupón");
        // TNA / TIREA
        alias.put("tna real","tna"); alias.put("tirea real","tirea");
        // días al vto
        alias.put("días al vto","dias_al_vto"); alias.put("dias al vto","dias_al_vto"); alias.put("dias_al_vto","días al vto");
        // fecha / vencimiento
        alias.put("fecha vencimiento","fecha"); alias.put("vencimiento","fecha");
        // fecha liquidación
        alias.put("fecha liquidacion","fecha_liquidacion"); alias.put("fecha de liquidacion","fecha_liquidacion"); alias.put("fecha_liquidacion","fecha liquidacion");
        // factor capitalización
        alias.put("factor capitalizacion","factor_cap"); alias.put("factor de capitalizacion","factor_cap"); alias.put("factor_cap","factor capitalizacion");
        // básicos
        alias.put("flujo","flujo"); alias.put("capital","capital"); alias.put("ticker","ticker"); alias.put("isin","isin"); alias.put("emisor","emisor");

        final Map<String,Integer> headers = idx;

        // Acceso por nombre con alias
        java.util.function.Function<String,Integer> col = (name) -> {
          String n = normalize(name);
          Integer p = headers.get(n);
          if (p != null) return p;
          String al = alias.get(n);
          return (al != null) ? headers.getOrDefault(al, -1) : -1;
        };

        // Fecha de liquidación (de hoja o T+1)
        LocalDate liqDate = tryReadGlobalLiquidation(sh, df, col);
        if (liqDate == null) liqDate = tPlusBusinessDays(LocalDate.now(), T_PLUS);
        liqTs = (liqDate != null)
            ? liqDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            : null;

        // Fecha CER de referencia = liqDate - 10 días hábiles
        LocalDate cerRefDate = minusBusinessDaysSimple(liqDate, 10);
        Double cerRef = bcra.get("CER", cerRefDate);
        if (cerRef == null) {
          for (int i = 1; i <= 10 && cerRef == null; i++) {
            cerRef = bcra.get("CER", cerRefDate.minusDays(i));
          }
        }
        System.out.println("[BCRACER] liqDate=" + liqDate + " cerRefDate=" + cerRefDate + " cerRef=" + cerRef);

        
        // Parse filas
        Map<String,BondBundle> byTicker = new LinkedHashMap<>();
        int r0 = headerRowIdx + 1, rN = sh.getLastRowNum();

        for (int r=r0; r<=rN; r++){
          Row row = sh.getRow(r);
          if (row==null || isRowEmpty(row, df)) continue;

          String ticker = str(row, col.apply("ticker"), df);
          if (ticker==null || ticker.isEmpty()) continue;

          BondBundle b = byTicker.computeIfAbsent(ticker, k -> new BondBundle());
          if (b.ticker==null) b.ticker=ticker;
          if (b.isin==null)   b.isin  = str(row, col.apply("isin"), df);
          if (b.emisor==null) b.emisor= str(row, col.apply("emisor"), df);

          Double vr = num(row, col.apply("vr"), df);
          if (b.vr==null && vr!=null) b.vr=vr;

          Double cupon = num(row, col.apply("cupon"), df);
          if (b.coupon==null && cupon!=null) b.coupon=cupon;

          Double cerIni = num(row, col.apply("cer_inicial"), df);
          if (b.cerInicial==null && cerIni!=null) b.cerInicial=cerIni;

          Double fcap = num(row, col.apply("factor_cap"), df);
          if (b.capFactor==null && fcap!=null) b.capFactor=fcap;

          LocalDate f = date(row, col.apply("fecha"), df);
          if (f==null) f = date(row, col.apply("vencimiento"), df);
          if (f != null) b.maturity = (b.maturity == null || f.isAfter(b.maturity)) ? f : b.maturity;

          Double flujo   = num(row, col.apply("flujo"), df);
          Double capital = num(row, col.apply("capital"), df);
          Double interes = num(row, col.apply("interes"), df);

          if (f!=null && (flujo!=null || capital!=null || interes!=null)){
            Cashflow cf = new Cashflow();
            cf.date = f;
            cf.amount = nz(flujo, nz(capital)+nz(interes));
            cf.principal = nz(capital);
            b.cfs.add(cf);
          }
        }

        // ===== Cálculos por instrumento =====
    LocalDate today = LocalDate.now();

    for (BondBundle b : byTicker.values()) {

      // --- Lectura de mercado ---
MarketData mkt = getMarketData(priceService, b.ticker);
Double dirtyDeMercado = (mkt != null ? mkt.dirtyPrice : null);
Double volumen        = (mkt != null ? mkt.volume     : null);
Double variacionPct   = (mkt != null ? mkt.changePct  : null);

// --- Factores y bases ---
double factorCap = (b.capFactor != null && b.capFactor > 0) ? b.capFactor : 1.0;

// baseAI = VR * (CER_ref / CER_inicial) * factor
Double baseAI = null;
if (b.vr != null && b.cerInicial != null && b.cerInicial > 0 && cerRef != null) {
  baseAI = b.vr * (cerRef / b.cerInicial) * factorCap;
}

// AI (intereses corridos) sobre capital ajustado
double accrued = 0.0;
if (baseAI != null) {
  double cup = (b.coupon != null ? b.coupon : 0.0);
  accrued = estimateAccrued(b, today, baseAI * cup);
}

    // ✅ Precio sucio/limpio coherentes con la fuente:
    // si viene DIRTY desde mercado, CLEAN = DIRTY - AI (>=0)
    // si no viene DIRTY pero sí CLEAN (caso raro), DIRTY = CLEAN + AI
    Double precioSucio, precioLimpio;
    if (dirtyDeMercado != null) {
      precioSucio  = dirtyDeMercado;
      precioLimpio = (accrued > 0) ? Math.max(0.0, dirtyDeMercado - accrued) : dirtyDeMercado;
    } else {
      // fallback si algún día tomás clean del quote
      Double cleanFromQuote = null; // no lo tenemos hoy; si lo agregás, setéalo aquí
      if (cleanFromQuote != null) {
        precioLimpio = cleanFromQuote;
        precioSucio  = cleanFromQuote + accrued;
      } else {
        precioLimpio = null;
        precioSucio  = null;
      }
    }

    Double vt      = baseAI;
    Double paridad = (precioSucio != null && vt != null && vt > 0) ? (precioSucio / vt) : null;


      // --- Tasas y sensibilidades ---
      // IMPORTANTE: sólo si hay precio sucio calculamos y_d e IRR/durations.
      Double tna = null, tirea = null, macaulay = null, modified = null, convexity = null;

      if (precioSucio != null && precioSucio > 0 && !b.cfs.isEmpty()) {
        double y_d = bisectionIRR(b.cfs, precioSucio, today, -0.005, 0.02); // diario
        tna   = y_d * 365.0;
        tirea = Math.pow(1.0 + y_d, 365.0) - 1.0;

        // Durations/convexity con ese y_d
        macaulay = macaulayDurationYears(b.cfs, y_d, today, precioSucio);
        modified = macaulay / (1.0 + (tna != null ? tna : 0.0) / 365.0);
        convexity = convexityYears2(b.cfs, y_d, today, precioSucio);
      }

      // WAL podés calcularlo independiente del precio (sobre principal nominal)
      // Si preferís ponderarlo por factorCap, multiplicá nz(b.vr)*factorCap
      Double wal = walYears(b.cfs, today, nz(b.vr) /* o nz(b.vr)*factorCap */);

      // --- DTO ---
      BoncerDto dto = new BoncerDto();
      dto.ticker = b.ticker;
      dto.isin   = b.isin;
      dto.emisor = b.emisor;
      dto.fecha  = (b.maturity != null) ? DMY.format(b.maturity) : null;

      // VR que mostramos: VR nominal (100) – si querés mostrar VR ajustado poné baseAI en vez de vt/cer…
      dto.vr         = round2(b.vr);
      dto.cerInicial = b.cerInicial;
      dto.cupon      = b.coupon;
      dto.cerRef      = cerRef;
      dto.cerRefDate  = cerRefDate != null ? DMY.format(cerRefDate) : null; // ✅ en dd/MM/yyyy


      dto.valorTecnico      = round2(vt);            // ✅ incluye factor + CER
      dto.interesesCorridos = round2(accrued);       // ✅ sobre capital ajustado
      dto.precioLimpio      = round2(precioLimpio);
      dto.precioSucio       = round2(precioSucio);
      dto.paridad           = round6(paridad);

      dto.tna              = round6(tna);            // null si no hay precio
      dto.tirea            = round6(tirea);          // null si no hay precio
      dto.duration         = round4(macaulay);       // null si no hay precio
      dto.modifiedDuration = round4(modified);       // null si no hay precio
      dto.convexity        = round4(convexity);      // null si no hay precio
      dto.vidaPromedio     = round4(wal);

      dto.volumen         = volumen;
      dto.variacionDiaria = variacionPct;

      dto.diasAlVto        = (b.maturity != null) ? (int) daysBetween(today, b.maturity) : null;
      dto.fechaLiquidacion = (liqDate != null) ? DMY.format(liqDate) : null;

      out.add(dto);
    }

      out = completeMarketFields(out);
      return BoncerResponse.of(now(), liqTs, out);
      }
    } catch (Exception e) {
      e.printStackTrace();
      liqTs = null;
    }
      out = completeMarketFields(out);
      return BoncerResponse.of(now(), liqTs, out);
      }

 // ===== Market (reflexión para no romper con tu PriceService/PriceQuote) =====
static class MarketData { Double dirtyPrice, volume, changePct; }

private MarketData getMarketData(PriceService svc, String ticker){
  if (svc == null) return null;
  try {
    Object quote = null;

    for (String m : new String[]{"lastQuote","getQuote","quote","getLastQuote","findQuote","findByTicker","get"}) {
      try {
        Method mm = svc.getClass().getMethod(m, String.class);
        quote = mm.invoke(svc, ticker);
        if (quote != null) break;
      } catch (NoSuchMethodException ignore) {}
    }
    if (quote == null) return null;

    MarketData md = new MarketData();

    // ⚠️ precio DIRTY si existe; si no, caemos a price/last
    Double priceNum = tryNum(quote, "getDirtyPrice","getPrice","getLast","price","last","getCleanPrice");
    if (priceNum == null) {
      java.math.BigDecimal bd = tryBD(quote, "getDirtyPrice","getPrice","getLast","price","last","getCleanPrice");
      if (bd != null) priceNum = bd.doubleValue();
    }
    md.dirtyPrice = priceNum;

    // variación diaria — dejamos el valor tal cual venga del store (suele ser %)
    Double chg = tryNum(quote, "getChangePct","getPctChange","pctChange","getChange","change","getDeltaPct");
    if (chg == null) {
      java.math.BigDecimal bd = tryBD(quote, "getChangePct","getPctChange","pctChange","getChange","change","getDeltaPct");
      if (bd != null) chg = bd.doubleValue();
    }
    md.changePct = chg;

    // volumen
    Double vol = tryNum(quote, "getVolume","volume");
    if (vol == null) {
      java.math.BigDecimal bd = tryBD(quote, "getVolume","volume");
      if (bd != null) vol = bd.doubleValue();
    }
    md.volume = vol;

    return md;
  } catch (Exception ignore) {
    return null;
  }
}

  private static Double tryNum(Object obj, String... getters){
    for (String g : getters){
      try {
        Method m = obj.getClass().getMethod(g);
        Object v = m.invoke(obj);
        if (v instanceof Number) return ((Number)v).doubleValue();
      } catch (Exception ignore){}
    }
    return null;
  }

  private static java.math.BigDecimal tryBD(Object obj, String... getters){
    for (String g : getters){
      try {
        Method m = obj.getClass().getMethod(g);
        Object v = m.invoke(obj);
        if (v instanceof java.math.BigDecimal) return (java.math.BigDecimal) v;
      } catch (Exception ignore){}
    }
    return null;
  }

  // ===== Helpers lectura/headers/fechas =====
  private static Map<String,Integer> mapHeaders(Row head, DataFormatter df){
    Map<String,Integer> idx = new HashMap<>();
    for (Cell c: head){
      String k = normalize(df.formatCellValue(c));
      if (!k.isEmpty()) idx.put(k, c.getColumnIndex());
    }
    return idx;
  }
  private static String normalize(String s){
    if (s==null) return "";
    String t = s.trim().toLowerCase(Locale.ROOT)
      .replace("á","a").replace("é","e").replace("í","i").replace("ó","o").replace("ú","u")
      .replaceAll("\\s+"," ");
    return t;
  }
  private static boolean isRowEmpty(Row row, DataFormatter df){
    int last = Math.max(10, row.getLastCellNum());
    for (int i=0;i<=last;i++){
      Cell c = row.getCell(i);
      if (c!=null && !df.formatCellValue(c).trim().isEmpty()) return false;
    }
    return true;
  }
  private static String str(Row row, int idx, DataFormatter df){
    if (idx<0) return null;
    Cell c = row.getCell(idx);
    if (c==null) return null;
    String v = df.formatCellValue(c).trim();
    return v.isEmpty()? null : v;
  }
  private static Double num(Row row, int idx, DataFormatter df){
    if (idx<0) return null;
    Cell c = row.getCell(idx);
    if (c==null) return null;
    if (c.getCellType()==CellType.NUMERIC) return c.getNumericCellValue();

    String v = df.formatCellValue(c).trim();
    if (v.isEmpty() || v.equals("-")) return null;

    boolean hadPercent = v.contains("%");
    String x = v.replace("%","").replace(".","").replace(",",".");
    try {
      double d = Double.parseDouble(x);
      return hadPercent ? d/100.0 : d;
    } catch(Exception e){ return null; }
  }

  private static LocalDate date(Row row, int idx, DataFormatter df){
    if (idx < 0 || row == null) return null;
    try{
      Cell c = row.getCell(idx);
      if (c == null) return null;

      if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
        return c.getLocalDateTimeCellValue().toLocalDate();
      }

      String s = df.formatCellValue(c).trim();
      if (s.isEmpty()) return null;

      if (s.matches("\\d{2}/\\d{2}/\\d{4}")) {
        String[] p = s.split("/");
        return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
      }
      if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
        return LocalDate.parse(s);
      }
    } catch (Exception ignore) {}
    return null;
  }

    // Sólo saltea sábados y domingos (simple, suficiente para CER t-10)
  private static LocalDate minusBusinessDaysSimple(LocalDate date, int n){
    LocalDate d = date;
    int left = n;
    while (left > 0){
      d = d.minusDays(1);
      switch (d.getDayOfWeek()){
        case SATURDAY, SUNDAY -> { /* no cuenta */ }
        default -> left--;
      }
    }
    return d;
  }


  // Lee una fecha de liquidación global desde la columna `fecha_liquidacion` (si existe)
  private static LocalDate tryReadGlobalLiquidation(
      Sheet sh, DataFormatter df, java.util.function.Function<String, Integer> col
  ) {
    int i = col.apply("fecha_liquidacion");
    if (i == -1) return null;

    int r0 = sh.getFirstRowNum() + 1, rN = sh.getLastRowNum();
    for (int r = r0; r <= rN; r++) {
      Row row = sh.getRow(r);
      if (row == null) continue;
      LocalDate d = date(row, i, df);
      if (d != null) return d;
    }
    return null;
  }

  // T + n días hábiles desde `base` usando BusinessCalendar
  private LocalDate tPlusBusinessDays(LocalDate base, int n){
    LocalDate d = base;
    int added = 0;
    while (added < n) {
      d = d.plusDays(1);
      if (calendar.isBusinessDay(d)) added++;
    }
    return d;
  }

  // ===== Métricas =====
  private static double estimateAccrued(BondBundle b, LocalDate today, double base){
    if (b.coupon==null || b.coupon<=0 || b.cfs.isEmpty()) return 0.0;

    List<LocalDate> ds = new ArrayList<>();
    for (Cashflow cf: b.cfs) ds.add(cf.date);
    ds.sort(Comparator.naturalOrder());

    int daysCoupon = 182;
    for (int i=1;i<ds.size();i++){
      int d=(int)daysBetween(ds.get(i-1), ds.get(i));
      if (d>25){ daysCoupon=d; break; }
    }

    if (base<=0) return 0.0;

    LocalDate last = null;
    for (int i=ds.size()-1;i>=0;i--){
      if (!ds.get(i).isAfter(today)){ last=ds.get(i); break; }
    }
    if (last==null) last = today.minusDays(Math.min(30, daysCoupon));

    int elapsed = (int)daysBetween(last, today);
    elapsed = Math.max(0, Math.min(elapsed, daysCoupon));

    return Math.max(0.0, (b.coupon * base) * (elapsed / (double)daysCoupon));
  }

  private static double sumPV(List<Cashflow> cfs, double y_d, LocalDate today){
    double pv=0;
    for (Cashflow cf: cfs){
      double t=daysBetween(today, cf.date);
      pv += cf.amount / Math.pow(1.0+y_d, t);
    }
    return pv;
  }

  private static double bisectionIRR(List<Cashflow> cfs, double priceDirty, LocalDate today, double lo, double hi){
    for (int it=0; it<70; it++){
      double mid=0.5*(lo+hi), pv=0;
      for (Cashflow cf: cfs){
        double t=daysBetween(today, cf.date);
        pv += cf.amount / Math.pow(1.0+mid, t);
      }
      if (pv > priceDirty) lo=mid; else hi=mid;
    }
    return 0.5*(lo+hi);
  }

  private static double macaulayDurationYears(List<Cashflow> cfs, double y_d, LocalDate today, double priceDirty){
    double num=0;
    for (Cashflow cf: cfs){
      double t=daysBetween(today, cf.date);
      double df=Math.pow(1.0+y_d, t);
      num += (t/365.0)*(cf.amount/df);
    }
    return (priceDirty>0)? num/priceDirty : 0.0;
  }

  private static double convexityYears2(List<Cashflow> cfs, double y_d, LocalDate today, double priceDirty){
    double num=0;
    for (Cashflow cf: cfs){
      double t=daysBetween(today, cf.date);
      double df=Math.pow(1.0+y_d, t);
      double tt=t/365.0;
      num += (cf.amount/df)*tt*(tt+1.0/365.0);
    }
    return (priceDirty>0)? num/priceDirty : 0.0;
  }

  private static double walYears(List<Cashflow> cfs, LocalDate today, double principalTotal){
    if (principalTotal<=0) return 0.0;
    double num=0;
    for (Cashflow cf: cfs){
      if (cf.principal>0){
        double tt=daysBetween(today, cf.date)/365.0;
        num += tt*cf.principal;
      }
    }
    return num / principalTotal;
  }

  private static long now(){ return System.currentTimeMillis(); }
  private static double daysBetween(LocalDate a, LocalDate b){ return (double) java.time.temporal.ChronoUnit.DAYS.between(a,b); }
  private static Double round2(Double x){ return x==null? null : Math.round(x*100.0)/100.0; }
  private static Double round4(Double x){ return x==null? null : Math.round(x*10000.0)/10000.0; }
  private static Double round6(Double x){ return x==null? null : Math.round(x*1_000_000.0)/1_000_000.0; }
  private static double nz(Double x){ return x==null? 0d : x; }
  private static double nz(Double x, double fallback){
  return x == null ? fallback : x;
}
}
