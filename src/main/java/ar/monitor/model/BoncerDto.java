package ar.monitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class BoncerDto {
  public String ticker;
  public String isin;
  public String emisor;

  @JsonProperty("fecha") public String fecha;

  @JsonProperty("VR") public Double vr;
  public Double capital;
  @JsonProperty("interes") public Double interes;
  public Double flujo;
  @JsonProperty("cer_inicial") public Double cerInicial;
  public Double cupon;

  @JsonProperty("precio_limpio") public Double precioLimpio;
  @JsonProperty("precio_sucio")  public Double precioSucio;
  public Double interesesCorridos;
  public Double valorTecnico;
  public Double paridad;
  public Double volumen;
  @JsonProperty("variacion_diaria") public Double variacionDiaria;

  public Double tna;
  @JsonProperty("tirea") public Double tirea;
  public Double duration;
  @JsonProperty("modified_duration") public Double modifiedDuration;
  public Double convexity;
  @JsonProperty("vida_promedio") public Double vidaPromedio;

  @JsonProperty("dias_al_vto")       public Integer diasAlVto;
  @JsonProperty("fecha_liquidacion") public String  fechaLiquidacion;

  // --- CER de referencia (t-10 hábiles) usado para VT/AI ---
  @com.fasterxml.jackson.annotation.JsonProperty("cer_ref")
  public Double cerRef;

  @com.fasterxml.jackson.annotation.JsonProperty("cer_ref_date")
  public String cerRefDate;
}
