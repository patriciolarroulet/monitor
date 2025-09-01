package ar.monitor.model;   // 👈 ESTE package exacto

import java.math.BigDecimal;

public class CaucionQuote {
  private final Integer plazoDias;
  private final BigDecimal tna;   // decimal (0.4001)
  private final BigDecimal monto; // opcional
  private final String mercado;
  private final String moneda;
  private final String asOf;

  public CaucionQuote(Integer plazoDias, BigDecimal tna, BigDecimal monto,
                      String mercado, String moneda, String asOf) {
    this.plazoDias = plazoDias;
    this.tna = tna;
    this.monto = monto;
    this.mercado = mercado;
    this.moneda = moneda;
    this.asOf = asOf;
  }

  public Integer getPlazoDias() { return plazoDias; }
  public BigDecimal getTna()    { return tna; }
  public BigDecimal getMonto()  { return monto; }
  public String getMercado()    { return mercado; }
  public String getMoneda()     { return moneda; }
  public String getAsOf()       { return asOf; }
}
