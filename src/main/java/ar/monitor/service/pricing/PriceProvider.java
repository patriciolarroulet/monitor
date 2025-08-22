package ar.monitor.service.pricing;

import java.util.Map;

public interface PriceProvider {
    Map<String, PriceQuote> fetchPrices();
}
