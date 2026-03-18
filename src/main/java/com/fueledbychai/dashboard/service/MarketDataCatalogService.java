package com.fueledbychai.dashboard.service;

import com.fueledbychai.dashboard.model.SupportedAssetType;
import com.fueledbychai.dashboard.model.SupportedExchange;
import com.fueledbychai.data.Exchange;
import com.fueledbychai.data.InstrumentType;
import com.fueledbychai.data.Ticker;
import com.fueledbychai.marketdata.QuoteEngine;
import com.fueledbychai.util.ITickerRegistry;
import com.fueledbychai.util.TickerRegistryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class MarketDataCatalogService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataCatalogService.class);
    private static final Comparator<SupportedAssetType> ASSET_TYPE_ORDER =
            Comparator.comparing(SupportedAssetType::displayName).thenComparing(SupportedAssetType::name);
    private static final Comparator<SupportedExchange> EXCHANGE_ORDER =
            Comparator.comparing(SupportedExchange::displayName).thenComparing(SupportedExchange::name);

    private final List<SupportedExchange> supportedExchanges;
    private final List<SupportedAssetType> supportedAssetTypes;
    private final Map<String, SupportedExchange> exchangesByName;
    private final Map<String, Map<String, Integer>> tickerCountsByExchange;

    public MarketDataCatalogService() {
        this.supportedExchanges = discoverSupportedExchanges();
        this.supportedAssetTypes = supportedExchanges.stream()
                .flatMap(exchange -> exchange.supportedAssetTypes().stream())
                .distinct()
                .sorted(ASSET_TYPE_ORDER)
                .toList();
        this.exchangesByName = supportedExchanges.stream()
                .collect(Collectors.toUnmodifiableMap(SupportedExchange::name, exchange -> exchange));
        this.tickerCountsByExchange = buildTickerCounts();
    }

    public List<SupportedExchange> supportedExchanges() {
        return supportedExchanges;
    }

    public List<SupportedAssetType> supportedAssetTypes() {
        return supportedAssetTypes;
    }

    public Map<String, Integer> tickerCountsForExchange(String exchangeName) {
        return tickerCountsByExchange.getOrDefault(exchangeName, Map.of());
    }

    public List<Map<String, String>> instrumentsForExchange(String exchangeName, String assetTypeName) {
        SupportedExchange exchange = exchangesByName.get(normalizeExchangeName(exchangeName));
        if (exchange == null) {
            return List.of();
        }
        ITickerRegistry registry = safeRegistry(exchange.exchange());
        if (registry == null) {
            return List.of();
        }
        return exchange.supportedAssetTypes().stream()
                .filter(at -> assetTypeName == null || assetTypeName.isBlank()
                        || at.name().equalsIgnoreCase(assetTypeName.trim()))
                .flatMap(at -> {
                    try {
                        Ticker[] tickers = registry.getAllTickersForType(at.instrumentType());
                        if (tickers == null) {
                            return java.util.stream.Stream.empty();
                        }
                        return Arrays.stream(tickers)
                                .filter(t -> t.getSymbol() != null && !t.getSymbol().isBlank())
                                .map(t -> Map.<String, String>of(
                                        "symbol", t.getSymbol(),
                                        "assetType", at.name(),
                                        "assetTypeLabel", at.displayName()
                                ));
                    } catch (RuntimeException e) {
                        log.debug("Cannot get tickers for {} on {}", at.name(), exchangeName, e);
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();
    }

    public SupportedExchange requireExchange(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Exchange is required.");
        }

        String normalized = normalizeExchangeName(value);
        SupportedExchange exchange = exchangesByName.get(normalized);
        if (exchange == null) {
            throw new IllegalArgumentException("Unsupported exchange: " + value);
        }
        return exchange;
    }

    private List<SupportedExchange> discoverSupportedExchanges() {
        return Arrays.stream(Exchange.ALL_EXCHANGES)
                .filter(Objects::nonNull)
                .filter(QuoteEngine::isRegistered)
                .filter(TickerRegistryFactory::isRegistered)
                .map(this::discoverExchange)
                .filter(exchange -> !exchange.supportedAssetTypes().isEmpty())
                .sorted(EXCHANGE_ORDER)
                .toList();
    }

    private SupportedExchange discoverExchange(Exchange exchange) {
        ITickerRegistry registry = safeRegistry(exchange);
        List<SupportedAssetType> assetTypes = registry == null
                ? List.of()
                : supportedAssetTypes(registry);
        return new SupportedExchange(
                exchange.getExchangeName(),
                displayName(exchange),
                exchange,
                assetTypes
        );
    }

    List<SupportedAssetType> supportedAssetTypes(ITickerRegistry registry) {
        LinkedHashSet<SupportedAssetType> assetTypes = new LinkedHashSet<>();
        for (InstrumentType instrumentType : InstrumentType.values()) {
            if (instrumentType == InstrumentType.NONE) {
                continue;
            }
            try {
                Ticker[] tickers = registry.getAllTickersForType(instrumentType);
                if (tickers != null && tickers.length > 0) {
                    assetTypes.add(SupportedAssetType.fromInstrumentType(instrumentType));
                }
            } catch (RuntimeException e) {
                log.debug("Ticker registry does not expose {} via getAllTickersForType", instrumentType, e);
            }
        }

        return assetTypes.stream()
                .sorted(ASSET_TYPE_ORDER)
                .toList();
    }

    private Map<String, Map<String, Integer>> buildTickerCounts() {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (SupportedExchange exchange : supportedExchanges) {
            ITickerRegistry registry = safeRegistry(exchange.exchange());
            if (registry == null) {
                result.put(exchange.name(), Map.of());
                continue;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (SupportedAssetType assetType : exchange.supportedAssetTypes()) {
                try {
                    Ticker[] tickers = registry.getAllTickersForType(assetType.instrumentType());
                    counts.put(assetType.name(), tickers != null ? tickers.length : 0);
                } catch (RuntimeException e) {
                    log.debug("Cannot get ticker count for {} on {}", assetType.name(), exchange.name(), e);
                    counts.put(assetType.name(), 0);
                }
            }
            result.put(exchange.name(), Collections.unmodifiableMap(counts));
        }
        return Collections.unmodifiableMap(result);
    }

    private ITickerRegistry safeRegistry(Exchange exchange) {
        try {
            return TickerRegistryFactory.getInstance(exchange);
        } catch (Exception e) {
            log.debug("Ticker registry unavailable for {}", exchange, e);
            return null;
        }
    }

    private String displayName(Exchange exchange) {
        return Arrays.stream(exchange.getExchangeName().split("_"))
                .filter(token -> !token.isBlank())
                .map(this::displayToken)
                .collect(Collectors.joining(" "));
    }

    private String displayToken(String token) {
        String upper = token.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "OKX" -> "OKX";
            case "DYDX" -> "dYdX";
            default -> upper.length() <= 3
                    ? upper
                    : upper.substring(0, 1) + upper.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    private String normalizeExchangeName(String value) {
        return value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }
}
