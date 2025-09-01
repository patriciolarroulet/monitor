package ar.monitor.web;

import java.time.Instant;
import java.util.List;

public record CaucionesIngestPayload(
    Instant asOf,
    String fuente,
    String mercado,
    String moneda,
    List<CaucionQuotePost> quotes
) {}
