package com.fueledbychai.switchboard.service;

import com.fueledbychai.switchboard.config.SwitchboardProperties;
import com.fueledbychai.switchboard.model.SupportedAssetType;
import com.fueledbychai.switchboard.model.SupportedExchange;
import com.fueledbychai.data.Exchange;
import com.fueledbychai.data.InstrumentType;
import com.fueledbychai.data.Ticker;
import com.fueledbychai.marketdata.Level1Quote;
import com.fueledbychai.marketdata.QuoteType;
import com.fueledbychai.util.ITickerRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchboardStateServiceTests {

    @Test
    void lookupExtendsExchangeAssetTypesUsingRegistryContents() {
        SwitchboardStateService service = new SwitchboardStateService(
                new SwitchboardProperties(),
                new MarketDataCatalogService(List.of()),
                null
        );
        SupportedAssetType perp = SupportedAssetType.fromInstrumentType(InstrumentType.PERPETUAL_FUTURES);
        SupportedAssetType spot = SupportedAssetType.fromInstrumentType(InstrumentType.CRYPTO_SPOT);
        SupportedExchange exchange = new SupportedExchange("PARADEX", "Paradex", Exchange.PARADEX, List.of(perp));

        ITickerRegistry registry = new StubTickerRegistry(
                tickersForType(InstrumentType.PERPETUAL_FUTURES, "ETH-PERP"),
                tickersForType(InstrumentType.CRYPTO_SPOT, "ETH-USDC")
        );

        List<SupportedAssetType> assetTypes = service.supportedAssetTypesForLookup(exchange, registry);

        assertEquals(List.of(perp, spot), assetTypes);
        assertTrue(assetTypes.contains(spot));
    }

    @Test
    void quoteLatencyTracksOnlyTopOfBookUpdates() {
        assertTrue(SwitchboardStateService.shouldTrackQuoteLatency(quote(Map.of(QuoteType.BID, BigDecimal.ONE))));
        assertTrue(SwitchboardStateService.shouldTrackQuoteLatency(quote(Map.of(QuoteType.ASK, BigDecimal.TEN))));
        assertFalse(SwitchboardStateService.shouldTrackQuoteLatency(quote(Map.of(QuoteType.VOLUME, BigDecimal.TEN))));
        assertFalse(SwitchboardStateService.shouldTrackQuoteLatency(quote(Map.of(QuoteType.FUNDING_RATE_APR, new BigDecimal("0.12")))));
    }

    private static Ticker[] tickersForType(InstrumentType instrumentType, String... symbols) {
        Ticker[] tickers = new Ticker[symbols.length];
        for (int index = 0; index < symbols.length; index++) {
            tickers[index] = new Ticker()
                    .setSymbol(symbols[index])
                    .setExchange(Exchange.PARADEX)
                    .setInstrumentType(instrumentType);
        }
        return tickers;
    }

    private static Level1Quote quote(Map<QuoteType, BigDecimal> quoteValues) {
        return new Level1Quote(
                new Ticker()
                        .setSymbol("ETH-PERP")
                        .setExchange(Exchange.PARADEX)
                        .setInstrumentType(InstrumentType.PERPETUAL_FUTURES),
                ZonedDateTime.parse("2026-03-19T17:00:00Z"),
                quoteValues
        );
    }

    private static final class StubTickerRegistry implements ITickerRegistry {
        private final Ticker[] perpTickers;
        private final Ticker[] spotTickers;

        private StubTickerRegistry(Ticker[] perpTickers, Ticker[] spotTickers) {
            this.perpTickers = perpTickers;
            this.spotTickers = spotTickers;
        }

        @Override
        public Ticker lookupByBrokerSymbol(InstrumentType instrumentType, String symbol) {
            return null;
        }

        @Override
        public Ticker lookupByCommonSymbol(InstrumentType instrumentType, String symbol) {
            return null;
        }

        @Override
        public String commonSymbolToExchangeSymbol(InstrumentType instrumentType, String symbol) {
            return null;
        }

        @Override
        public Ticker[] getAllTickersForType(InstrumentType instrumentType) {
            return switch (instrumentType) {
                case PERPETUAL_FUTURES -> perpTickers;
                case CRYPTO_SPOT -> spotTickers;
                default -> new Ticker[0];
            };
        }
    }
}
