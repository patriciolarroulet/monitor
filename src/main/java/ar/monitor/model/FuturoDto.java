package ar.monitor.model;

public class FuturoDto {
    private String mes;    // p.ej. "AGO25"
    private String rofex;  // p.ej. "1.312,000"
    private String tna;    // p.ej. "53,63"
    private String tir;    // p.ej. "70,31"

    public FuturoDto() {}

    public FuturoDto(String mes, String rofex, String tna, String tir) {
        this.mes = mes;
        this.rofex = rofex;
        this.tna = tna;
        this.tir = tir;
    }

    public String getMes() { return mes; }
    public void setMes(String mes) { this.mes = mes; }

    public String getRofex() { return rofex; }
    public void setRofex(String rofex) { this.rofex = rofex; }

    public String getTna() { return tna; }
    public void setTna(String tna) { this.tna = tna; }

    public String getTir() { return tir; }
    public void setTir(String tir) { this.tir = tir; }
}