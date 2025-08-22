package ar.monitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class InstrumentoDto {

    // Identificación
    @JsonProperty("ticker") private String ticker;
    @JsonProperty("isin")   private String isin;
    @JsonProperty("emisor") private String emisor;

    // Fechas formateadas desde el reader
    @JsonProperty("vencimiento")       private String vencimiento;        // dd/MM/yyyy
    @JsonProperty("fecha")             private String fecha;              // dd/MM/yyyy (input)
    @JsonProperty("hora_input")        private String horaInput;          // HH:mm:ss (input)
    @JsonProperty("fecha_liquidacion") private String fechaLiquidacion;   // dd/MM/yyyy

    // Valores
    @JsonProperty("capital")       private Double capital;
    @JsonProperty("interes")       private Double interes;
    @JsonProperty("valor_final")   private Double valorFinal;
    @JsonProperty("precio")        private Double precio;
    @JsonProperty("precio_anterior") private Double precioAnterior;

    // Cálculos
    @JsonProperty("dias_al_vto")       private Integer diasAlVto;
    @JsonProperty("tna_simple_bruta")  private Double tnaSimpleBruta;
    @JsonProperty("tem_bruta")         private Double temBruta;
    @JsonProperty("tea_bruta")         private Double teaBruta;
    @JsonProperty("tna_simple_neta")   private Double tnaSimpleNeta;
    @JsonProperty("tem_neta")          private Double temNeta;
    @JsonProperty("tea_neta")          private Double teaNeta;

    // ====== NUEVOS CAMPOS PARA EL MONITOR ======
    // % variación diaria en puntos (ej: 0.31 -> 0,31%)
    @JsonProperty("pct_change")  private Double pctChange;

    // Cantidad operada
    @JsonProperty("q_op")        private Long qOp;

    // Volumen (el front acepta r.v ?? r.volumen; elegimos "v")
    @JsonProperty("v")           private Double volumen;

    // Fuente del dato (API, PY, EXCEL, MOCK, etc.)
    @JsonProperty("fuente")      private String fuente;

    // ===== Getters =====
    public String  getTicker()            { return ticker; }
    public String  getIsin()              { return isin; }
    public String  getEmisor()            { return emisor; }
    public String  getVencimiento()       { return vencimiento; }
    public String  getFecha()             { return fecha; }
    public String  getHoraInput()         { return horaInput; }
    public String  getFechaLiquidacion()  { return fechaLiquidacion; }
    public Double  getCapital()           { return capital; }
    public Double  getInteres()           { return interes; }
    public Double  getValorFinal()        { return valorFinal; }
    public Double  getPrecio()            { return precio; }
    public Double  getPrecioAnterior()    { return precioAnterior; }
    public Integer getDiasAlVto()         { return diasAlVto; }
    public Double  getTnaSimpleBruta()    { return tnaSimpleBruta; }
    public Double  getTemBruta()          { return temBruta; }
    public Double  getTeaBruta()          { return teaBruta; }
    public Double  getTnaSimpleNeta()     { return tnaSimpleNeta; }
    public Double  getTemNeta()           { return temNeta; }
    public Double  getTeaNeta()           { return teaNeta; }
    public Double  getPctChange()         { return pctChange; }
    public Long getQOp()                    { return qOp; }
    public Double  getVolumen()           { return volumen; }
    public String  getFuente()            { return fuente; }

    // ===== Setters =====
    public void setTicker(String ticker)                     { this.ticker = ticker; }
    public void setIsin(String isin)                         { this.isin = isin; }
    public void setEmisor(String emisor)                     { this.emisor = emisor; }
    public void setVencimiento(String vencimiento)           { this.vencimiento = vencimiento; }
    public void setFecha(String fecha)                       { this.fecha = fecha; }
    public void setHoraInput(String horaInput)               { this.horaInput = horaInput; }
    public void setFechaLiquidacion(String fechaLiquidacion) { this.fechaLiquidacion = fechaLiquidacion; }
    public void setCapital(Double capital)                   { this.capital = capital; }
    public void setInteres(Double interes)                   { this.interes = interes; }
    public void setValorFinal(Double valorFinal)             { this.valorFinal = valorFinal; }
    public void setPrecio(Double precio)                     { this.precio = precio; }
    public void setPrecioAnterior(Double precioAnterior)     { this.precioAnterior = precioAnterior; }
    public void setDiasAlVto(Integer diasAlVto)              { this.diasAlVto = diasAlVto; }
    public void setTnaSimpleBruta(Double tnaSimpleBruta)     { this.tnaSimpleBruta = tnaSimpleBruta; }
    public void setTemBruta(Double temBruta)                 { this.temBruta = temBruta; }
    public void setTeaBruta(Double teaBruta)                 { this.teaBruta = teaBruta; }
    public void setTnaSimpleNeta(Double tnaSimpleNeta)       { this.tnaSimpleNeta = tnaSimpleNeta; }
    public void setTemNeta(Double temNeta)                   { this.temNeta = temNeta; }
    public void setTeaNeta(Double teaNeta)                   { this.teaNeta = teaNeta; }

    // Nuevos
    public void setPctChange(Double pctChange)               { this.pctChange = pctChange; }
    public void setQOp(Long qOp)                              { this.qOp = qOp; }
    public void setVolumen(Double volumen)                   { this.volumen = volumen; }
    public void setFuente(String fuente)                     { this.fuente = fuente; }
}
