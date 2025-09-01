package ar.monitor.service.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** DTO para mapear output/fx.json */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FxRates {

    @JsonProperty("mep")
    private Quote mep;

    @JsonProperty("ccl")
    private Quote ccl;

    /** A3500 (ex "oficial") */
    @JsonProperty("a3500")
    private Quote a3500;

    // --- getters / setters ---
    public Quote getMep() { return mep; }
    public void setMep(Quote mep) { this.mep = mep; }

    public Quote getCcl() { return ccl; }
    public void setCcl(Quote ccl) { this.ccl = ccl; }

    public Quote getA3500() { return a3500; }
    public void setA3500(Quote a3500) { this.a3500 = a3500; }

    /** Sub‑objeto para cada cotización */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Quote {
        @JsonProperty("value")
        private BigDecimal value;

        @JsonProperty("prev")
        private BigDecimal prev;

        @JsonProperty("chg")
        private String chg;	

        @JsonProperty("chg_pct")
        private String chg_pct;	


        @JsonProperty("date")
        private String date; // "YYYY-MM-DD" o null

        public Quote() {}

        public BigDecimal getValue() { return value; }
        public void setValue(BigDecimal value) { this.value = value; }

        public BigDecimal getPrev() { return prev; }
        public void setPrev(BigDecimal prev) { this.prev = prev; }
        
        public String getChg() { return chg; }
        public void setChg(String chg) { this.chg = chg; }

        public String getChg_pct() { return chg_pct; }
        public void setChg_pct(String chg_pct) { this.chg_pct = chg_pct; }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
    }
}
