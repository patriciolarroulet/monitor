package ar.monitor.service.pricing;

import ar.monitor.model.CaucionQuote;

import java.time.Instant;
import java.util.List;

public record CaucionesSnapshot(
    Instant asOf,
    String fuente,
    String mercado,
    String moneda,
    List<CaucionQuote> quotes
) {}
