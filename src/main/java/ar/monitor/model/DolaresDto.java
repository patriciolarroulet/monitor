package ar.monitor.model;

import java.math.BigDecimal;

public class DolaresDto {
    private BigDecimal mep;
    private BigDecimal ccl;
    private BigDecimal oficial;

    public DolaresDto() {}
    public DolaresDto(BigDecimal mep, BigDecimal ccl, BigDecimal oficial) {
        this.mep = mep; this.ccl = ccl; this.oficial = oficial;
    }

    public BigDecimal getMep() { return mep; }
    public void setMep(BigDecimal mep) { this.mep = mep; }
    public BigDecimal getCcl() { return ccl; }
    public void setCcl(BigDecimal ccl) { this.ccl = ccl; }
    public BigDecimal getOficial() { return oficial; }
    public void setOficial(BigDecimal oficial) { this.oficial = oficial; }
}