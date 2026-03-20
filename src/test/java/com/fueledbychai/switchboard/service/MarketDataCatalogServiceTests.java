package com.fueledbychai.switchboard.service;

import com.fueledbychai.switchboard.model.SupportedAssetType;
import com.fueledbychai.switchboard.model.SupportedExchange;
import com.fueledbychai.data.Exchange;
import com.fueledbychai.data.InstrumentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataCatalogServiceTests {

    private final SupportedAssetType perp = SupportedAssetType.fromInstrumentType(InstrumentType.PERPETUAL_FUTURES);
    private final SupportedAssetType spot = SupportedAssetType.fromInstrumentType(InstrumentType.CRYPTO_SPOT);
    private final MarketDataCatalogService catalog = new MarketDataCatalogService(List.of(
            new SupportedExchange("LIGHTER", "Lighter", Exchange.LIGHTER, List.of(perp, spot)),
            new SupportedExchange("HYPERLIQUID", "Hyperliquid", Exchange.HYPERLIQUID, List.of(perp)),
            new SupportedExchange("PARADEX", "Paradex", Exchange.PARADEX, List.of(perp, spot)),
            new SupportedExchange("BINANCE_SPOT", "Binance Spot", Exchange.BINANCE_SPOT, List.of(spot))
    ));

    @Test
    void discoversMarketDataExchangesFromClasspathProviders() {
        Set<String> exchangeNames = catalog.supportedExchanges().stream()
                .map(SupportedExchange::name)
                .collect(Collectors.toSet());

        assertTrue(exchangeNames.containsAll(List.of("LIGHTER", "HYPERLIQUID", "PARADEX", "BINANCE_SPOT")));

        assertTrue(findExchange("LIGHTER").supports(perp));
        assertTrue(findExchange("LIGHTER").supports(spot));

        assertTrue(findExchange("HYPERLIQUID").supports(perp));
        assertFalse(findExchange("HYPERLIQUID").supports(spot));

        assertTrue(findExchange("PARADEX").supports(perp));
        assertTrue(findExchange("PARADEX").supports(spot));

        assertTrue(findExchange("BINANCE_SPOT").supports(spot));
        assertFalse(findExchange("BINANCE_SPOT").supports(perp));
    }

    @Test
    void exposesUnionOfDiscoveredAssetTypesUsingBackwardCompatibleNames() {
        Set<String> assetTypes = catalog.supportedAssetTypes().stream()
                .map(SupportedAssetType::name)
                .collect(Collectors.toSet());

        assertTrue(assetTypes.contains("PERP"));
        assertTrue(assetTypes.contains("SPOT"));
    }

    @Test
    void resolvesExchangeAndAssetTypeAliases() {
        assertEquals("BINANCE_SPOT", catalog.requireExchange("Binance Spot").name());
        assertEquals("PERP", SupportedAssetType.fromInput("perpetual-futures").name());
        assertEquals("SPOT", SupportedAssetType.fromInput("crypto_spot").name());
        assertEquals(InstrumentType.PERPETUAL_OPTION, SupportedAssetType.fromInput("perp_option").instrumentType());
    }

    private SupportedExchange findExchange(String name) {
        return catalog.supportedExchanges().stream()
                .filter(exchange -> exchange.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected exchange not found: " + name));
    }
}
