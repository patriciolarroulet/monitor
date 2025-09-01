package ar.monitor.model;

import java.util.Map;

public class BcraResponseDto {
    private String updatedAt;
    private String source;
    private Map<String, SerieCalcDto> series;

    public BcraResponseDto() {}

    public BcraResponseDto(String updatedAt, String source, Map<String, SerieCalcDto> series) {
        this.updatedAt = updatedAt;
        this.source = source;
        this.series = series;
    }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Map<String, SerieCalcDto> getSeries() { return series; }
    public void setSeries(Map<String, SerieCalcDto> series) { this.series = series; }
}
