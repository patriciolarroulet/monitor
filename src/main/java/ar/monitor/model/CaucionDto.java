package ar.monitor.model;

import java.math.BigDecimal;

public class CaucionDto {
    private String plazo;    // ej: "7 dÃ­as"
    private BigDecimal tna;  // ej: 41.50 (sin %)

    public CaucionDto() {}
    public CaucionDto(String plazo, BigDecimal tna) {
        this.plazo = plazo;
        this.tna = tna;
    }
    public String getPlazo() { return plazo; }
    public void setPlazo(String plazo) { this.plazo = plazo; }
    public BigDecimal getTna() { return tna; }
    public void setTna(BigDecimal tna) { this.tna = tna; }
}