package ar.monitor.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Quote enriquecido para el monitor.
 * Campos: price (c), previousPrice, pctChange, qtyOperated (q_op), volume (v),
 * source (fuente), horaInput, fechaInput.
 */
public class PriceQuote {

    private final BigDecimal price;          // último precio
    private final BigDecimal previousPrice;  // precio previo (cierre anterior, etc.)
    private final BigDecimal pctChange;      // variación diaria en %
    private final long       qtyOperated;    // cantidad
    private final BigDecimal volume;         // monto = price * qty
    private final String     source;         // API, PY, MOCK, etc.
    private final String     horaInput;      // "HH:mm:ss"
    private final String     fechaInput;     // "dd/MM/yyyy" (opcional)

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /* =======================
       Constructores (Compat)
       ======================= */

    /** A (compat): previousPrice, sin fechaInput */
    public PriceQuote(BigDecimal price,
                      BigDecimal previousPrice,
                      long qtyOperated,
                      BigDecimal volume,
                      String source,
                      String horaInput) {
        this(price, previousPrice, qtyOperated, volume, source, horaInput, null);
    }

    /** B (compat): pctChange explícito, sin fechaInput */
    public PriceQuote(BigDecimal price,
                      long qtyOperated,
                      BigDecimal volume,
                      BigDecimal pctChange,
                      String source,
                      String horaInput) {
        this(price, qtyOperated, volume, pctChange, source, horaInput, null);
    }

    /* =======================
       Constructores (Nuevos)
       ======================= */

    /** A (nuevo): previousPrice, con fechaInput (server calcula pctChange) */
    public PriceQuote(BigDecimal price,
                      BigDecimal previousPrice,
                      long qtyOperated,
                      BigDecimal volume,
                      String source,
                      String horaInput,
                      String fechaInput) {

        this.price         = scale(price);
        this.previousPrice = scale(previousPrice);
        this.qtyOperated   = qtyOperated;
        this.volume        = scale(volume);
        this.source        = source;
        this.horaInput     = normalizeHora(horaInput);
        this.fechaInput    = fechaInput;

        if (this.previousPrice == null || this.previousPrice.signum() == 0) {
            this.pctChange = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            this.pctChange = this.price
                    .divide(this.previousPrice, 6, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    /** B (nuevo): pctChange explícito, con fechaInput */
    public PriceQuote(BigDecimal price,
                      long qtyOperated,
                      BigDecimal volume,
                      BigDecimal pctChange,
                      String source,
                      String horaInput,
                      String fechaInput) {

        this.price         = scale(price);
        this.previousPrice = null;
        this.qtyOperated   = qtyOperated;
        this.volume        = scale(volume);
        this.source        = source;
        this.horaInput     = normalizeHora(horaInput);
        this.fechaInput    = fechaInput;

        this.pctChange = (pctChange == null)
                ? null
                : pctChange.setScale(2, RoundingMode.HALF_UP);
    }

    /* ===============
       Utilidades
       =============== */

    private static BigDecimal scale(BigDecimal x) {
        if (x == null) return null;
        // 3 decimales suele ser suficiente para precio; ajustá si necesitás otro scale
        return x.setScale(3, RoundingMode.HALF_UP);
    }

    private static String normalizeHora(String h) {
        if (h == null || h.isBlank()) return nowHHmmss();
        // Aseguramos "HH:mm:ss" tomando los primeros 8 chars si vino con milisegundos
        return (h.length() >= 8) ? h.substring(0, 8) : nowHHmmss();
    }

    public static String nowHHmmss() {
        return LocalTime.now().format(HHMMSS);
    }

    /* ===============
       Getters
       =============== */

    public BigDecimal getPrice()        { return price; }
    public BigDecimal getPreviousPrice(){ return previousPrice; }
    public BigDecimal getPctChange()    { return pctChange; }
    public long       getQtyOperated()  { return qtyOperated; }
    public BigDecimal getVolume()       { return volume; }
    public String     getSource()       { return source; }
    public String     getHoraInput()    { return horaInput; }
    public String     getFechaInput()   { return fechaInput; }

    // Compat con código viejo
    public BigDecimal getLast()         { return getPrice(); }

    @Override
    public String toString() {
        return "PriceQuote{" +
                "price=" + price +
                ", previousPrice=" + previousPrice +
                ", pctChange=" + pctChange +
                ", qtyOperated=" + qtyOperated +
                ", volume=" + volume +
                ", source='" + source + '\'' +
                ", horaInput='" + horaInput + '\'' +
                ", fechaInput='" + fechaInput + '\'' +
                '}';
    }
}
