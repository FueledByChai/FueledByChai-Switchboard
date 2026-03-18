package com.fueledbychai.switchboard.service;

import com.fueledbychai.switchboard.model.SupportedAssetType;
import com.fueledbychai.switchboard.model.SupportedExchange;
import com.fueledbychai.data.InstrumentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataCatalogServiceTests {

    private final MarketDataCatalogService catalog = new MarketDataCatalogService();

    @Test
    void discoversMarketDataExchangesFromClasspathProviders() {
        Set<String> exchangeNames = catalog.supportedExchanges().stream()
                .map(SupportedExchange::name)
                .collect(Collectors.toSet());

        assertTrue(exchangeNames.containsAll(List.of("LIGHTER", "HYPERLIQUID", "PARADEX", "BINANCE_SPOT")));

        SupportedAssetType perp = SupportedAssetType.fromInstrumentType(InstrumentType.PERPETUAL_FUTURES);
        SupportedAssetType spot = SupportedAssetType.fromInstrumentType(InstrumentType.CRYPTO_SPOT);

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
