package ar.monitor.model;

public class SerieCalcDto {
    private String code;
    private String date;
    private Double value;
    private String prevDate;
    private Double prevValue;
    private Double delta;
    private Double deltaPct;

    public SerieCalcDto() {}

    public SerieCalcDto(String code, String date, Double value,
                        String prevDate, Double prevValue,
                        Double delta, Double deltaPct) {
        this.code = code;
        this.date = date;
        this.value = value;
        this.prevDate = prevDate;
        this.prevValue = prevValue;
        this.delta = delta;
        this.deltaPct = deltaPct;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    public String getPrevDate() { return prevDate; }
    public void setPrevDate(String prevDate) { this.prevDate = prevDate; }
    public Double getPrevValue() { return prevValue; }
    public void setPrevValue(Double prevValue) { this.prevValue = prevValue; }
    public Double getDelta() { return delta; }
    public void setDelta(Double delta) { this.delta = delta; }
    public Double getDeltaPct() { return deltaPct; }
    public void setDeltaPct(Double deltaPct) { this.deltaPct = deltaPct; }
}
