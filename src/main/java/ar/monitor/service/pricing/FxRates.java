package ar.monitor.service.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)  // <— clave
public class FxRates {
  private BigDecimal mep, ccl, oficial;
  private String ts_utc;

  public BigDecimal getMep() { return mep; }
  public void setMep(BigDecimal mep) { this.mep = mep; }
  public BigDecimal getCcl() { return ccl; }
  public void setCcl(BigDecimal ccl) { this.ccl = ccl; }
  public BigDecimal getOficial() { return oficial; }
  public void setOficial(BigDecimal oficial) { this.oficial = oficial; }
  public String getTs_utc() { return ts_utc; }
  public void setTs_utc(String ts_utc) { this.ts_utc = ts_utc; }
}
