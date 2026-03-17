package com.fueledbychai.dashboard.service;

import com.fueledbychai.dashboard.api.DashboardSnapshot;
import com.fueledbychai.dashboard.api.InstrumentLookupOption;
import com.fueledbychai.dashboard.api.LiveDashboardSnapshot;
import com.fueledbychai.dashboard.api.MarketSnapshot;
import com.fueledbychai.dashboard.api.MarketSubscriptionRequest;
import com.fueledbychai.dashboard.api.OrderSnapshot;
import com.fueledbychai.dashboard.api.OrderTicketRequest;
import com.fueledbychai.dashboard.api.PairSnapshot;
import com.fueledbychai.dashboard.api.PairSubscriptionRequest;
import com.fueledbychai.dashboard.api.PriceHistoryPoint;
import com.fueledbychai.dashboard.config.DashboardProperties;
import com.fueledbychai.dashboard.model.OrderSide;
import com.fueledbychai.dashboard.model.OrderStatus;
import com.fueledbychai.dashboard.model.OrderType;
import com.fueledbychai.dashboard.model.SupportedAssetType;
import com.fueledbychai.dashboard.model.SupportedExchange;
import com.fueledbychai.dashboard.model.TimeInForce;
import com.fueledbychai.data.Exchange;
import com.fueledbychai.data.InstrumentType;
import com.fueledbychai.data.Ticker;
import com.fueledbychai.marketdata.ILevel1Quote;
import com.fueledbychai.marketdata.Level1QuoteListener;
import com.fueledbychai.marketdata.QuoteEngine;
import com.fueledbychai.marketdata.QuoteType;
import com.fueledbychai.util.ITickerRegistry;
import com.fueledbychai.util.TickerRegistryFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Service
public class DashboardStateService {

    private static final Logger log = LoggerFactory.getLogger(DashboardStateService.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal BPS_MULTIPLIER = BigDecimal.valueOf(10_000);
    private static final BigDecimal APR_TO_BPS_PER_HOUR = BigDecimal.valueOf(100).divide(BigDecimal.valueOf(24L * 365L), MC);
    private static final BigDecimal BPS_PER_HOUR_TO_APR = BigDecimal.valueOf(24L * 365L).divide(BigDecimal.valueOf(100), MC);
    private static final String PAPER_ORDER_NOTE = "Paper order only. Live exchange execution is not wired in this repo yet.";
    private static final List<String> LOOKUP_QUOTES = List.of("USDC", "USDT", "USD");

    private final DashboardProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final ConcurrentMap<String, MarketSubscriptionContext> marketsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PairDefinition> pairsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OrderDefinition> ordersById = new ConcurrentHashMap<>();
    private final ConcurrentMap<Exchange, QuoteEngine> enginesByExchange = new ConcurrentHashMap<>();

    public DashboardStateService(DashboardProperties properties) {
        this.properties = properties;
    }

    public int maxRows() {
        return properties.getMaxRows();
    }

    public MarketSnapshot addMarket(MarketSubscriptionRequest request) {
        Objects.requireNonNull(request, "request is required");
        if (marketsById.size() >= properties.getMaxRows()) {
            throw new IllegalArgumentException("Maximum subscribed markets reached (" + properties.getMaxRows() + ").");
        }

        SupportedExchange exchange = SupportedExchange.fromInput(request.exchange());
        SupportedAssetType assetType = SupportedAssetType.fromInput(request.assetType());
        if (!exchange.supports(assetType)) {
            throw new IllegalArgumentException(exchange.displayName() + " does not support " + assetType.displayName() + " routing.");
        }

        String commonSymbol = normalizeRequestedSymbol(request.symbol(), assetType);
        int rowIndex = assignRow(request.rowIndex());

        Ticker ticker = resolveTicker(exchange, assetType, commonSymbol);
        QuoteEngine engine = getOrStartEngine(exchange.exchange());
        String marketId = UUID.randomUUID().toString();

        MarketSubscriptionContext context = new MarketSubscriptionContext(
                marketId,
                rowIndex,
                commonSymbol,
                Optional.ofNullable(ticker.getSymbol()).orElse(commonSymbol),
                exchange,
                assetType,
                ticker,
                engine,
                Instant.now(clock),
                Duration.ofMinutes(properties.getHistoryMinutes())
        );
        Level1QuoteListener listener = quote -> context.applyQuote(quote, Instant.now(clock));
        context.listener = listener;

        marketsById.put(marketId, context);
        try {
            engine.subscribeLevel1(ticker, listener);
        } catch (Exception e) {
            marketsById.remove(marketId);
            throw new IllegalStateException("Failed to subscribe " + commonSymbol + " on " + exchange.displayName(), e);
        }

        return context.toSnapshot();
    }

    public void removeMarket(String marketId) {
        if (marketId == null || marketId.isBlank()) {
            return;
        }
        MarketSubscriptionContext removed = marketsById.remove(marketId);
        if (removed == null) {
            return;
        }

        try {
            removed.engine.unsubscribeLevel1(removed.ticker, removed.listener);
        } catch (Exception e) {
            log.debug("Failed to unsubscribe market {} cleanly", marketId, e);
        }

        pairsById.entrySet().removeIf(entry ->
                entry.getValue().leftMarketId.equals(marketId) || entry.getValue().rightMarketId.equals(marketId));
    }

    public PairSnapshot addPair(PairSubscriptionRequest request) {
        Objects.requireNonNull(request, "request is required");
        String leftId = request.leftMarketId();
        String rightId = request.rightMarketId();
        if (Objects.equals(leftId, rightId)) {
            throw new IllegalArgumentException("Pair legs must be different markets.");
        }

        MarketSubscriptionContext left = requireMarket(leftId);
        MarketSubscriptionContext right = requireMarket(rightId);

        String pairId = UUID.randomUUID().toString();
        PairDefinition pair = new PairDefinition(pairId, leftId, rightId, Instant.now(clock));
        pairsById.put(pairId, pair);
        return pairSnapshot(pair, left, right);
    }

    public void removePair(String pairId) {
        if (pairId == null || pairId.isBlank()) {
            return;
        }
        pairsById.remove(pairId);
    }

    public OrderSnapshot submitOrder(OrderTicketRequest request) {
        Objects.requireNonNull(request, "request is required");

        MarketSubscriptionContext market = requireMarket(request.marketId());
        OrderSide side = OrderSide.fromInput(request.side());
        OrderType orderType = OrderType.fromInput(request.orderType());
        TimeInForce timeInForce = TimeInForce.fromInput(request.timeInForce());
        BigDecimal quantity = normalizePositive(request.quantity(), "Quantity must be positive.");
        BigDecimal limitPrice = request.limitPrice() == null ? null : normalizePositive(request.limitPrice(), "Limit price must be positive.");

        if (orderType == OrderType.LIMIT && limitPrice == null) {
            throw new IllegalArgumentException("Limit orders require a limit price.");
        }
        if (orderType == OrderType.MARKET && timeInForce == TimeInForce.POST_ONLY) {
            throw new IllegalArgumentException("Post Only is not valid for market orders.");
        }

        BigDecimal referencePrice = firstNonNull(referencePrice(market, side), limitPrice);
        Instant now = Instant.now(clock);
        String orderId = UUID.randomUUID().toString();

        OrderDefinition order = new OrderDefinition(
                orderId,
                market.marketId,
                market.commonSymbol,
                market.exchangeSymbol,
                market.exchange,
                market.assetType,
                side,
                orderType,
                timeInForce,
                quantity,
                limitPrice,
                referencePrice,
                PAPER_ORDER_NOTE,
                now
        );
        ordersById.put(orderId, order);
        return order.toSnapshot();
    }

    public void cancelOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        Optional.ofNullable(ordersById.get(orderId))
                .ifPresent(order -> order.cancel(Instant.now(clock)));
    }

    public List<MarketSnapshot> marketSnapshots() {
        return marketsById.values().stream()
                .sorted(Comparator
                        .comparingInt((MarketSubscriptionContext context) -> context.rowIndex)
                        .thenComparing(context -> context.createdAt))
                .map(MarketSubscriptionContext::toSnapshot)
                .toList();
    }

    public List<InstrumentLookupOption> instrumentLookup(String symbol) {
        String normalizedQuery = normalizeLookupSymbol(symbol);
        String underlying = extractUnderlying(normalizedQuery);
        Map<String, InstrumentLookupOption> matches = new LinkedHashMap<>();

        for (SupportedAssetType assetType : SupportedAssetType.values()) {
            for (SupportedExchange exchange : SupportedExchange.values()) {
                if (!exchange.supports(assetType)) {
                    continue;
                }
                ITickerRegistry registry = safeRegistry(exchange.exchange());
                if (registry == null) {
                    continue;
                }
                for (String candidate : instrumentLookupCandidates(normalizedQuery, underlying)) {
                    Ticker ticker = resolveUsingRegistryCandidateStrict(registry, assetType.instrumentType(), candidate, exchange.exchange());
                    if (ticker != null) {
                        addInstrumentLookupOption(matches, exchange, assetType, ticker, normalizedQuery);
                    }
                }
            }
        }

        return matches.values().stream()
                .limit(24)
                .toList();
    }

    public List<PairSnapshot> pairSnapshots() {
        return pairsById.values().stream()
                .sorted(Comparator.comparing(def -> def.createdAt))
                .map(this::pairSnapshot)
                .flatMap(Optional::stream)
                .toList();
    }

    public List<OrderSnapshot> orderSnapshots() {
        return ordersById.values().stream()
                .sorted(Comparator.comparing((OrderDefinition order) -> order.createdAt).reversed())
                .map(OrderDefinition::toSnapshot)
                .toList();
    }

    public DashboardSnapshot fullSnapshot() {
        List<MarketSnapshot> markets = marketSnapshots();
        List<PairSnapshot> pairs = pairSnapshots();
        List<OrderSnapshot> orders = orderSnapshots();
        Map<String, List<PriceHistoryPoint>> history = new LinkedHashMap<>();
        for (MarketSubscriptionContext context : marketsById.values()) {
            history.put(context.marketId, context.historySnapshot());
        }
        return new DashboardSnapshot(Instant.now(clock), markets, pairs, orders, history);
    }

    public LiveDashboardSnapshot liveSnapshot() {
        return new LiveDashboardSnapshot(Instant.now(clock), marketSnapshots(), pairSnapshots(), orderSnapshots());
    }

    public List<PriceHistoryPoint> history(String marketId) {
        return Optional.ofNullable(marketsById.get(marketId))
                .map(MarketSubscriptionContext::historySnapshot)
                .orElse(List.of());
    }

    @PreDestroy
    public void shutdown() {
        List<String> marketIds = new ArrayList<>(marketsById.keySet());
        marketIds.forEach(this::removeMarket);
        enginesByExchange.values().forEach(engine -> {
            try {
                engine.stopEngine();
            } catch (Exception ignored) {
            }
            try {
                engine.shutdown();
            } catch (Exception ignored) {
            }
        });
        enginesByExchange.clear();
    }

    private QuoteEngine getOrStartEngine(Exchange exchange) {
        QuoteEngine engine = enginesByExchange.computeIfAbsent(exchange, QuoteEngine::getInstance);
        synchronized (engine) {
            if (!engine.started()) {
                engine.startEngine(new Properties());
            }
        }
        return engine;
    }

    private Optional<PairSnapshot> pairSnapshot(PairDefinition pair) {
        MarketSubscriptionContext left = marketsById.get(pair.leftMarketId);
        MarketSubscriptionContext right = marketsById.get(pair.rightMarketId);
        if (left == null || right == null) {
            return Optional.empty();
        }
        return Optional.of(pairSnapshot(pair, left, right));
    }

    private PairSnapshot pairSnapshot(PairDefinition pair,
                                      MarketSubscriptionContext left,
                                      MarketSubscriptionContext right) {
        BigDecimal effectiveBid = minus(left.bid, right.ask);
        BigDecimal effectiveAsk = minus(left.ask, right.bid);

        BigDecimal buyLeftSellRightTakerSpread = minus(right.bid, left.ask);
        BigDecimal buyLeftSellRightMakerTakerSpread = minus(right.bid, left.bid);
        BigDecimal buyRightSellLeftTakerSpread = minus(left.bid, right.ask);
        BigDecimal buyRightSellLeftMakerTakerSpread = minus(left.bid, right.bid);

        BigDecimal buyLeftSellRightTakerBps = toBps(buyLeftSellRightTakerSpread, left.ask, right.bid);
        BigDecimal buyLeftSellRightMakerTakerBps = toBps(buyLeftSellRightMakerTakerSpread, left.bid, right.bid);
        BigDecimal buyRightSellLeftTakerBps = toBps(buyRightSellLeftTakerSpread, right.ask, left.bid);
        BigDecimal buyRightSellLeftMakerTakerBps = toBps(buyRightSellLeftMakerTakerSpread, right.bid, left.bid);

        BigDecimal fundingAnnualizedDiffPercent = minus(left.fundingRateApr, right.fundingRateApr);
        BigDecimal fundingBpsPerHourDiff = minus(left.fundingRateBpsPerHour, right.fundingRateBpsPerHour);

        Instant updatedAt = maxTime(left.updatedAt, right.updatedAt);

        return new PairSnapshot(
                pair.pairId,
                pair.leftMarketId,
                pair.rightMarketId,
                left.commonSymbol + " • " + left.exchange.displayName(),
                right.commonSymbol + " • " + right.exchange.displayName(),
                effectiveBid,
                effectiveAsk,
                buyLeftSellRightTakerSpread,
                buyLeftSellRightMakerTakerSpread,
                buyRightSellLeftTakerSpread,
                buyRightSellLeftMakerTakerSpread,
                buyLeftSellRightTakerBps,
                buyLeftSellRightMakerTakerBps,
                buyRightSellLeftTakerBps,
                buyRightSellLeftMakerTakerBps,
                fundingAnnualizedDiffPercent,
                fundingBpsPerHourDiff,
                updatedAt
        );
    }

    private BigDecimal toBps(BigDecimal spread, BigDecimal buyPrice, BigDecimal sellPrice) {
        if (spread == null || buyPrice == null || sellPrice == null) {
            return null;
        }
        BigDecimal ref = buyPrice.add(sellPrice, MC).divide(TWO, MC);
        if (ref.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return spread.multiply(BPS_MULTIPLIER, MC).divide(ref, MC);
    }

    private MarketSubscriptionContext requireMarket(String marketId) {
        MarketSubscriptionContext context = marketsById.get(marketId);
        if (context == null) {
            throw new IllegalArgumentException("Unknown market id: " + marketId);
        }
        return context;
    }

    private Ticker resolveTicker(SupportedExchange exchange, SupportedAssetType assetType, String commonSymbol) {
        ITickerRegistry registry = safeRegistry(exchange.exchange());
        InstrumentType instrumentType = assetType.instrumentType();

        if (registry != null) {
            Ticker resolved = resolveUsingRegistry(registry, instrumentType, commonSymbol, exchange.exchange());
            if (resolved != null) {
                return resolved;
            }
        }

        Ticker ticker = new Ticker();
        ticker.setSymbol(commonSymbol);
        ticker.setExchange(exchange.exchange());
        ticker.setInstrumentType(instrumentType);
        return ticker;
    }

    private ITickerRegistry safeRegistry(Exchange exchange) {
        try {
            return TickerRegistryFactory.getInstance(exchange);
        } catch (Exception e) {
            log.warn("Ticker registry unavailable for {}. Falling back to direct symbol handling.", exchange, e);
            return null;
        }
    }

    private Ticker resolveUsingRegistry(ITickerRegistry registry,
                                        InstrumentType instrumentType,
                                        String commonSymbol,
                                        Exchange exchange) {
        Set<String> commonCandidates = commonSymbolCandidates(commonSymbol);

        for (String candidate : commonCandidates) {
            Ticker resolved = resolveUsingRegistryCandidate(registry, instrumentType, candidate, exchange);
            if (resolved != null) {
                return resolved;
            }
        }

        for (String candidate : commonCandidates) {
            try {
                Ticker byBroker = registry.lookupByBrokerSymbol(instrumentType, candidate);
                if (byBroker != null) {
                    return byBroker;
                }
            } catch (RuntimeException e) {
                log.debug("Ticker lookup failed for broker symbol {} on {}", candidate, exchange, e);
            }
        }

        return null;
    }

    private Ticker resolveUsingRegistryCandidate(ITickerRegistry registry,
                                                 InstrumentType instrumentType,
                                                 String candidate,
                                                 Exchange exchange) {
        try {
            Ticker byCommon = registry.lookupByCommonSymbol(instrumentType, candidate);
            if (byCommon != null) {
                return byCommon;
            }
            String exchangeSymbol = registry.commonSymbolToExchangeSymbol(instrumentType, candidate);
            if (exchangeSymbol != null && !exchangeSymbol.isBlank()) {
                Ticker byBroker = registry.lookupByBrokerSymbol(instrumentType, exchangeSymbol);
                if (byBroker != null) {
                    return byBroker;
                }
                Ticker ticker = new Ticker();
                ticker.setSymbol(exchangeSymbol);
                ticker.setExchange(exchange);
                ticker.setInstrumentType(instrumentType);
                return ticker;
            }
        } catch (RuntimeException e) {
            log.debug("Ticker lookup failed for common symbol {} on {}", candidate, exchange, e);
        }
        return null;
    }

    private Ticker resolveUsingRegistryCandidateStrict(ITickerRegistry registry,
                                                       InstrumentType instrumentType,
                                                       String candidate,
                                                       Exchange exchange) {
        try {
            Ticker byCommon = registry.lookupByCommonSymbol(instrumentType, candidate);
            if (byCommon != null) {
                return byCommon;
            }
            String exchangeSymbol = registry.commonSymbolToExchangeSymbol(instrumentType, candidate);
            if (exchangeSymbol != null && !exchangeSymbol.isBlank()) {
                Ticker byBroker = registry.lookupByBrokerSymbol(instrumentType, exchangeSymbol);
                if (byBroker != null) {
                    return byBroker;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Strict ticker lookup failed for common symbol {} on {}", candidate, exchange, e);
        }
        return null;
    }

    private Set<String> commonSymbolCandidates(String commonSymbol) {
        String normalized = normalizeLookupSymbol(commonSymbol);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (normalized.contains("/")) {
            candidates.add(normalized);
            return candidates;
        }
        LOOKUP_QUOTES.forEach(quote -> candidates.add(normalized + "/" + quote));
        candidates.add(normalized);
        return candidates;
    }

    private Set<String> instrumentLookupCandidates(String normalizedQuery, String underlying) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (normalizedQuery.contains("/")) {
            candidates.add(normalizedQuery);
        } else {
            LOOKUP_QUOTES.forEach(quote -> candidates.add(underlying + "/" + quote));
            candidates.add(underlying);
        }
        return candidates;
    }

    private void addInstrumentLookupOption(Map<String, InstrumentLookupOption> matches,
                                           SupportedExchange exchange,
                                           SupportedAssetType assetType,
                                           Ticker ticker,
                                           String requestedSymbol) {
        String exchangeSymbol = Optional.ofNullable(ticker.getSymbol()).orElse("");
        String symbol = canonicalLookupSymbol(exchange, assetType, exchangeSymbol, requestedSymbol);
        String key = exchange.name() + "|" + assetType.name() + "|" + exchangeSymbol;
        matches.putIfAbsent(key, new InstrumentLookupOption(
                symbol,
                exchangeSymbol,
                exchange.name(),
                exchange.displayName(),
                assetType.name(),
                assetType.displayName(),
                assetType.displayName() + " on " + exchange.displayName()
        ));
    }

    private String canonicalLookupSymbol(SupportedExchange exchange,
                                         SupportedAssetType assetType,
                                         String exchangeSymbol,
                                         String requestedSymbol) {
        if (exchangeSymbol == null || exchangeSymbol.isBlank()) {
            return normalizeRequestedSymbol(requestedSymbol, assetType);
        }

        String normalizedExchangeSymbol = exchangeSymbol.trim().toUpperCase(Locale.ROOT);
        return switch (exchange) {
            case PARADEX -> canonicalParadexSymbol(assetType, normalizedExchangeSymbol);
            case LIGHTER -> canonicalLighterSymbol(assetType, normalizedExchangeSymbol, requestedSymbol);
            case HYPERLIQUID -> canonicalHyperliquidSymbol(normalizedExchangeSymbol, requestedSymbol);
            case BINANCE_SPOT -> canonicalBinanceSpotSymbol(normalizedExchangeSymbol, requestedSymbol);
        };
    }

    private String canonicalParadexSymbol(SupportedAssetType assetType, String exchangeSymbol) {
        String normalized = exchangeSymbol;
        if (assetType == SupportedAssetType.PERP && normalized.endsWith("-PERP")) {
            normalized = normalized.substring(0, normalized.length() - "-PERP".length());
        }
        return normalized.replace('-', '/');
    }

    private String canonicalLighterSymbol(SupportedAssetType assetType, String exchangeSymbol, String requestedSymbol) {
        if (exchangeSymbol.contains("/")) {
            return exchangeSymbol;
        }
        if (assetType == SupportedAssetType.PERP && exchangeSymbol.endsWith("-PERP")) {
            String base = exchangeSymbol.substring(0, exchangeSymbol.length() - "-PERP".length());
            return normalizeRequestedSymbol(base, assetType);
        }
        return normalizeRequestedSymbol(requestedSymbol, assetType);
    }

    private String canonicalHyperliquidSymbol(String exchangeSymbol, String requestedSymbol) {
        if (exchangeSymbol.contains("/")) {
            return exchangeSymbol;
        }
        return normalizeRequestedSymbol(exchangeSymbol, SupportedAssetType.PERP);
    }

    private String canonicalBinanceSpotSymbol(String exchangeSymbol, String requestedSymbol) {
        for (String quote : LOOKUP_QUOTES) {
            if (exchangeSymbol.endsWith(quote) && exchangeSymbol.length() > quote.length()) {
                return exchangeSymbol.substring(0, exchangeSymbol.length() - quote.length()) + "/" + quote;
            }
        }
        return normalizeRequestedSymbol(requestedSymbol, SupportedAssetType.SPOT);
    }

    private int assignRow(Integer requestedRowIndex) {
        if (requestedRowIndex != null) {
            int validated = validateRowIndex(requestedRowIndex);
            boolean occupied = marketsById.values().stream().anyMatch(context -> context.rowIndex == validated);
            if (occupied) {
                throw new IllegalArgumentException("Watchlist row " + (validated + 1) + " is already in use.");
            }
            return validated;
        }

        for (int index = 0; index < properties.getMaxRows(); index++) {
            final int candidate = index;
            boolean occupied = marketsById.values().stream().anyMatch(context -> context.rowIndex == candidate);
            if (!occupied) {
                return candidate;
            }
        }

        throw new IllegalArgumentException("Maximum subscribed markets reached (" + properties.getMaxRows() + ").");
    }

    private int validateRowIndex(Integer rowIndex) {
        if (rowIndex == null) {
            throw new IllegalArgumentException("Watchlist row is required.");
        }
        if (rowIndex < 0 || rowIndex >= properties.getMaxRows()) {
            throw new IllegalArgumentException("Watchlist row must be between 1 and " + properties.getMaxRows() + ".");
        }
        return rowIndex;
    }

    private String normalizeLookupSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is required.");
        }
        return symbol.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace('-', '/');
    }

    private String normalizeRequestedSymbol(String symbol, SupportedAssetType assetType) {
        String normalized = normalizeLookupSymbol(symbol);
        if (normalized.contains("/")) {
            return normalized;
        }
        return switch (assetType) {
            case PERP, SPOT -> normalized + "/USDC";
        };
    }

    private String extractUnderlying(String normalizedQuery) {
        int separator = normalizedQuery.indexOf('/');
        return separator > 0 ? normalizedQuery.substring(0, separator) : normalizedQuery;
    }

    private BigDecimal normalizePositive(BigDecimal value, String message) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value.stripTrailingZeros();
    }

    private static BigDecimal minus(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return left.subtract(right, MC);
    }

    private static BigDecimal midpoint(BigDecimal bid, BigDecimal ask) {
        if (bid == null || ask == null) {
            return null;
        }
        return bid.add(ask, MC).divide(TWO, MC);
    }

    private static BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
    }

    private static Instant maxTime(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private static BigDecimal referencePrice(MarketSubscriptionContext market, OrderSide side) {
        if (side == OrderSide.BUY) {
            return firstNonNull(market.ask, firstNonNull(market.last, midpoint(market.bid, market.ask)));
        }
        return firstNonNull(market.bid, firstNonNull(market.last, midpoint(market.bid, market.ask)));
    }

    private static final class PairDefinition {
        private final String pairId;
        private final String leftMarketId;
        private final String rightMarketId;
        private final Instant createdAt;

        private PairDefinition(String pairId, String leftMarketId, String rightMarketId, Instant createdAt) {
            this.pairId = pairId;
            this.leftMarketId = leftMarketId;
            this.rightMarketId = rightMarketId;
            this.createdAt = createdAt;
        }
    }

    private static final class OrderDefinition {
        private final String orderId;
        private final String marketId;
        private final String symbol;
        private final String exchangeSymbol;
        private final SupportedExchange exchange;
        private final SupportedAssetType assetType;
        private final OrderSide side;
        private final OrderType orderType;
        private final TimeInForce timeInForce;
        private final BigDecimal quantity;
        private final BigDecimal limitPrice;
        private final BigDecimal referencePrice;
        private final String note;
        private final Instant createdAt;

        private volatile OrderStatus status;
        private volatile Instant updatedAt;
        private volatile Instant canceledAt;

        private OrderDefinition(String orderId,
                                String marketId,
                                String symbol,
                                String exchangeSymbol,
                                SupportedExchange exchange,
                                SupportedAssetType assetType,
                                OrderSide side,
                                OrderType orderType,
                                TimeInForce timeInForce,
                                BigDecimal quantity,
                                BigDecimal limitPrice,
                                BigDecimal referencePrice,
                                String note,
                                Instant createdAt) {
            this.orderId = orderId;
            this.marketId = marketId;
            this.symbol = symbol;
            this.exchangeSymbol = exchangeSymbol;
            this.exchange = exchange;
            this.assetType = assetType;
            this.side = side;
            this.orderType = orderType;
            this.timeInForce = timeInForce;
            this.quantity = quantity;
            this.limitPrice = limitPrice;
            this.referencePrice = referencePrice;
            this.note = note;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.status = OrderStatus.OPEN;
        }

        private synchronized void cancel(Instant now) {
            if (status != OrderStatus.OPEN) {
                return;
            }
            status = OrderStatus.CANCELED;
            canceledAt = now;
            updatedAt = now;
        }

        private synchronized OrderSnapshot toSnapshot() {
            return new OrderSnapshot(
                    orderId,
                    marketId,
                    symbol,
                    exchange.name(),
                    exchangeSymbol,
                    assetType.name(),
                    side.name(),
                    orderType.name(),
                    timeInForce.name(),
                    quantity,
                    limitPrice,
                    referencePrice,
                    status.name(),
                    note,
                    createdAt,
                    updatedAt,
                    canceledAt
            );
        }
    }

    private static final class MarketSubscriptionContext {
        private final String marketId;
        private final int rowIndex;
        private final String commonSymbol;
        private final String exchangeSymbol;
        private final SupportedExchange exchange;
        private final SupportedAssetType assetType;
        private final Ticker ticker;
        private final QuoteEngine engine;
        private final Instant createdAt;
        private final Duration historyRetention;
        private final ConcurrentLinkedDeque<PriceHistoryPoint> history = new ConcurrentLinkedDeque<>();
        private volatile Level1QuoteListener listener;

        private volatile BigDecimal bid;
        private volatile BigDecimal ask;
        private volatile BigDecimal last;
        private volatile BigDecimal volume;
        private volatile BigDecimal fundingRateApr;
        private volatile BigDecimal fundingRateBpsPerHour;
        private volatile Instant updatedAt;

        private MarketSubscriptionContext(String marketId,
                                          int rowIndex,
                                          String commonSymbol,
                                          String exchangeSymbol,
                                          SupportedExchange exchange,
                                          SupportedAssetType assetType,
                                          Ticker ticker,
                                          QuoteEngine engine,
                                          Instant createdAt,
                                          Duration historyRetention) {
            this.marketId = marketId;
            this.rowIndex = rowIndex;
            this.commonSymbol = commonSymbol;
            this.exchangeSymbol = exchangeSymbol;
            this.exchange = exchange;
            this.assetType = assetType;
            this.ticker = ticker;
            this.engine = engine;
            this.createdAt = createdAt;
            this.historyRetention = historyRetention;
        }

        private synchronized void applyQuote(ILevel1Quote quote, Instant now) {
            bid = quoteValue(quote, QuoteType.BID, bid);
            ask = quoteValue(quote, QuoteType.ASK, ask);
            last = quoteValue(quote, QuoteType.LAST, last);
            volume = quoteValue(quote, QuoteType.VOLUME, quoteValue(quote, QuoteType.VOLUME_NOTIONAL, volume));
            fundingRateApr = quoteValue(quote, QuoteType.FUNDING_RATE_APR, fundingRateApr);
            fundingRateBpsPerHour = quoteValue(quote, QuoteType.FUNDING_RATE_HOURLY_BPS, fundingRateBpsPerHour);

            if (fundingRateApr != null && fundingRateBpsPerHour == null) {
                fundingRateBpsPerHour = fundingRateApr.multiply(APR_TO_BPS_PER_HOUR, MC);
            } else if (fundingRateApr == null && fundingRateBpsPerHour != null) {
                fundingRateApr = fundingRateBpsPerHour.multiply(BPS_PER_HOUR_TO_APR, MC);
            }

            updatedAt = Optional.ofNullable(quote.getTimeStamp()).map(ZonedDateTime::toInstant).orElse(now);
            BigDecimal historyValue = last != null ? last : midpoint(bid, ask);
            if (historyValue != null) {
                history.addLast(new PriceHistoryPoint(updatedAt, historyValue));
            }

            Instant cutoff = now.minus(historyRetention);
            while (true) {
                PriceHistoryPoint oldest = history.peekFirst();
                if (oldest == null || !oldest.time().isBefore(cutoff)) {
                    break;
                }
                history.pollFirst();
            }
        }

        private synchronized MarketSnapshot toSnapshot() {
            return new MarketSnapshot(
                    marketId,
                    rowIndex,
                    commonSymbol,
                    exchangeSymbol,
                    exchange.name(),
                    assetType.name(),
                    bid,
                    ask,
                    last,
                    volume,
                    fundingRateApr,
                    fundingRateBpsPerHour,
                    updatedAt
            );
        }

        private synchronized List<PriceHistoryPoint> historySnapshot() {
            return List.copyOf(history);
        }

        private static BigDecimal quoteValue(ILevel1Quote quote, QuoteType quoteType, BigDecimal currentValue) {
            if (quote.containsType(quoteType)) {
                BigDecimal value = quote.getValue(quoteType);
                if (value != null) {
                    return value;
                }
            }
            return currentValue;
        }
    }
}
