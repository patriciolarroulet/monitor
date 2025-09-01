package ar.monitor.model.cauciones;

import java.time.Instant;
import java.util.List;

public record CaucionesPayload(
    Instant asOf,
    String fuente,
    String mercado,
    String moneda,
    List<CaucionQuote> quotes
) {}
