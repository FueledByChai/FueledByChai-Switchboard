package com.fueledbychai.switchboard.service;

import com.fueledbychai.aster.common.api.AsterConfiguration;
import com.fueledbychai.broker.BrokerError;
import com.fueledbychai.broker.BrokerRequestResult;
import com.fueledbychai.broker.IBroker;
import com.fueledbychai.broker.Position;
import com.fueledbychai.broker.aster.AsterBroker;
import com.fueledbychai.broker.bybit.BybitBroker;
import com.fueledbychai.broker.drift.DriftBroker;
import com.fueledbychai.broker.hibachi.HibachiBroker;
import com.fueledbychai.broker.hyperliquid.HyperliquidBroker;
import com.fueledbychai.broker.lighter.LighterBroker;
import com.fueledbychai.broker.okx.OkxBroker;
import com.fueledbychai.broker.order.Fill;
import com.fueledbychai.broker.order.OrderEvent;
import com.fueledbychai.broker.order.OrderStatus;
import com.fueledbychai.broker.order.OrderTicket;
import com.fueledbychai.broker.order.TradeDirection;
import com.fueledbychai.broker.paradex.ResilientParadexBroker;
import com.fueledbychai.bybit.common.api.BybitConfiguration;
import com.fueledbychai.data.Exchange;
import com.fueledbychai.data.Side;
import com.fueledbychai.data.Ticker;
import com.fueledbychai.drift.common.api.DriftConfiguration;
import com.fueledbychai.hibachi.common.api.HibachiConfiguration;
import com.fueledbychai.hyperliquid.ws.HyperliquidConfiguration;
import com.fueledbychai.lighter.common.api.LighterConfiguration;
import com.fueledbychai.okx.common.api.OkxConfiguration;
import com.fueledbychai.paradex.common.api.ParadexConfiguration;
import com.fueledbychai.switchboard.api.BalanceSnapshot;
import com.fueledbychai.switchboard.api.BrokerConnectionSnapshot;
import com.fueledbychai.switchboard.api.FillSnapshot;
import com.fueledbychai.switchboard.api.OrderModifyRequest;
import com.fueledbychai.switchboard.api.OrderSnapshot;
import com.fueledbychai.switchboard.api.OrderTicketRequest;
import com.fueledbychai.switchboard.api.PositionSnapshot;
import com.fueledbychai.switchboard.brokerprofile.BrokerEnvironment;
import com.fueledbychai.switchboard.brokerprofile.BrokerExchangeDefinition;
import com.fueledbychai.switchboard.brokerprofile.BrokerProfileChangedEvent;
import com.fueledbychai.switchboard.model.OrderSide;
import com.fueledbychai.switchboard.model.OrderType;
import com.fueledbychai.switchboard.model.SupportedAssetType;
import com.fueledbychai.switchboard.model.SupportedExchange;
import com.fueledbychai.switchboard.model.TimeInForce;
import com.fueledbychai.util.ExchangeRestApiFactory;
import com.fueledbychai.util.ExchangeWebSocketApiFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class LiveBrokerService {

    private static final Logger log = LoggerFactory.getLogger(LiveBrokerService.class);
    private static final int MAX_FILLS = 200;

    private final BrokerProfileService brokerProfileService;
    private final ConcurrentMap<String, ManagedBrokerSession> sessionsByExchange = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LiveOrderRecord> ordersByInternalId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> orderInternalIdByExchangeOrderId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> orderInternalIdByClientOrderId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BalanceState> balancesByExchange = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PositionSnapshot> positionsByExchangeAndSymbol = new ConcurrentHashMap<>();
    private final Deque<FillSnapshot> recentFills = new LinkedList<>();

    public LiveBrokerService(BrokerProfileService brokerProfileService) {
        this.brokerProfileService = brokerProfileService;
    }

    @PostConstruct
    public void initialize() {
        brokerProfileService.supportedDefinitions().forEach(definition -> refreshExchange(definition.exchange()));
    }

    @PreDestroy
    public void shutdown() {
        new ArrayList<>(sessionsByExchange.keySet()).forEach(this::disconnectExchange);
    }

    @EventListener(BrokerProfileChangedEvent.class)
    public void onBrokerProfileChanged(BrokerProfileChangedEvent event) {
        if (event != null && event.exchangeName() != null) {
            refreshExchange(event.exchangeName());
        }
    }

    @Scheduled(fixedDelayString = "${switchboard.broker-refresh-interval-ms:5000}")
    public void refreshPositions() {
        for (ManagedBrokerSession session : sessionsByExchange.values()) {
            if (!session.connected || !session.positionsSupported || session.broker == null) {
                continue;
            }
            try {
                List<Position> positions = session.broker.getAllPositions();
                replacePositions(session.exchangeName, session.exchangeLabel, positions);
            } catch (UnsupportedOperationException e) {
                session.positionsSupported = false;
                clearExchangePositions(session.exchangeName);
            } catch (Exception e) {
                session.lastError = e.getMessage();
                session.updatedAt = Instant.now();
            }
        }
    }

    @Scheduled(fixedDelayString = "${switchboard.open-orders-refresh-interval-ms:5000}")
    public void refreshOpenOrders() {
        for (ManagedBrokerSession session : sessionsByExchange.values()) {
            if (session == null || !session.connected || session.broker == null) {
                continue;
            }
            syncOpenOrders(session, "Synchronized from live broker.", false);
        }
    }

    public OrderSnapshot placeOrder(String marketId,
                                    String symbol,
                                    String exchangeSymbol,
                                    SupportedExchange exchange,
                                    SupportedAssetType assetType,
                                    Ticker ticker,
                                    OrderTicketRequest request) {
        ManagedBrokerSession session = requireSession(exchange.name());
        OrderTicket order = buildOrderTicket(session, ticker, request.side(), request.orderType(), request.timeInForce(),
                request.clientOrderId(), request.quantity(), request.limitPrice());
        LiveOrderRecord record = resolveOrderRecord(exchange.name(), null, order.getClientOrderId());
        record.applyPlacement(marketId, symbol, exchangeSymbol, exchange, assetType, order, "Submitted to live broker.");

        BrokerRequestResult result = session.broker.placeOrder(order);
        if (!result.isSuccess()) {
            record.applyFailure(result.getMessage());
            throw new IllegalStateException(result.getMessage().isBlank()
                    ? "Failed to place live order on " + exchange.displayName() + "."
                    : result.getMessage());
        }

        return record.toSnapshot(exchange.displayName());
    }

    public OrderSnapshot modifyOrder(String internalOrderId, OrderModifyRequest request) {
        LiveOrderRecord existing = requireOrder(internalOrderId);
        ManagedBrokerSession session = requireSession(existing.exchangeName);
        OrderTicket order = buildOrderTicket(session, existing.ticker, request.side(), request.orderType(),
                request.timeInForce(),
                trimToNull(request.clientOrderId()) == null ? existing.clientOrderId : request.clientOrderId(),
                request.quantity(), request.limitPrice());
        if (existing.exchangeOrderId != null && !existing.exchangeOrderId.isBlank()) {
            order.setOrderId(existing.exchangeOrderId);
        }

        BrokerRequestResult result = session.broker.modifyOrder(order);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getMessage().isBlank()
                    ? "Failed to modify live order."
                    : result.getMessage());
        }

        existing.applyModifyRequest(order);
        return existing.toSnapshot(existing.exchangeLabel);
    }

    public void cancelOrder(String internalOrderId) {
        LiveOrderRecord order = requireOrder(internalOrderId);
        ManagedBrokerSession session = requireSession(order.exchangeName);
        BrokerRequestResult result;
        if (order.exchangeOrderId != null && !order.exchangeOrderId.isBlank()) {
            result = session.broker.cancelOrder(order.exchangeOrderId);
        } else if (order.clientOrderId != null && !order.clientOrderId.isBlank()) {
            result = session.broker.cancelOrderByClientOrderId(order.clientOrderId);
        } else {
            throw new IllegalStateException("Order cannot be canceled because neither exchange order id nor client order id is known yet.");
        }

        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getMessage().isBlank()
                    ? "Failed to cancel live order."
                    : result.getMessage());
        }

        order.markPendingCancel();
    }

    public void cancelOrderByClientOrderId(String exchangeName, String clientOrderId) {
        ManagedBrokerSession session = requireSession(exchangeName);
        BrokerRequestResult result = session.broker.cancelOrderByClientOrderId(clientOrderId);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getMessage().isBlank()
                    ? "Failed to cancel order for clientOrderId " + clientOrderId + "."
                    : result.getMessage());
        }

        Optional.ofNullable(orderInternalIdByClientOrderId.get(clientLookupKey(exchangeName, clientOrderId)))
                .map(ordersByInternalId::get)
                .ifPresent(LiveOrderRecord::markPendingCancel);
    }

    public List<OrderSnapshot> orderSnapshots() {
        return ordersByInternalId.values().stream()
                .sorted(Comparator.comparing((LiveOrderRecord record) -> record.createdAt).reversed())
                .map(record -> record.toSnapshot(record.exchangeLabel))
                .toList();
    }

    public List<BrokerConnectionSnapshot> connectionSnapshots() {
        List<BrokerConnectionSnapshot> snapshots = new ArrayList<>();
        for (BrokerExchangeDefinition definition : brokerProfileService.supportedDefinitions()) {
            Optional<BrokerProfileService.ConfiguredBrokerProfile> profile = brokerProfileService.configuredProfile(definition.exchange());
            ManagedBrokerSession session = sessionsByExchange.get(definition.exchange());
            boolean configured = profile.isPresent();
            boolean connected = session != null && session.connected;
            String status = configured
                    ? (connected ? "CONNECTED" : "CONFIGURED")
                    : "MISSING_PROFILE";
            String error = session != null ? session.lastError : null;
            Instant updatedAt = session != null && session.updatedAt != null
                    ? session.updatedAt
                    : profile.map(BrokerProfileService.ConfiguredBrokerProfile::updatedAt).orElse(null);
            snapshots.add(new BrokerConnectionSnapshot(
                    definition.exchange(),
                    definition.displayName(),
                    true,
                    configured,
                    connected,
                    profile.map(p -> p.environment().name()).orElse(null),
                    profile.map(BrokerProfileService.ConfiguredBrokerProfile::accountLabel).orElse(definition.displayName()),
                    status,
                    error,
                    updatedAt
            ));
        }
        return snapshots;
    }

    public List<BalanceSnapshot> balanceSnapshots() {
        return balancesByExchange.values().stream()
                .sorted(Comparator.comparing(BalanceState::exchangeName))
                .map(BalanceState::toSnapshot)
                .toList();
    }

    public List<PositionSnapshot> positionSnapshots() {
        return positionsByExchangeAndSymbol.values().stream()
                .sorted(Comparator.comparing(PositionSnapshot::exchange).thenComparing(PositionSnapshot::symbol))
                .toList();
    }

    public List<FillSnapshot> fillSnapshots() {
        synchronized (recentFills) {
            return List.copyOf(recentFills);
        }
    }

    private void refreshExchange(String exchangeName) {
        disconnectExchange(exchangeName);
        Optional<BrokerProfileService.ConfiguredBrokerProfile> profile = brokerProfileService.configuredProfile(exchangeName);
        if (profile.isEmpty()) {
            clearExchangeState(exchangeName);
            return;
        }

        BrokerProfileService.ConfiguredBrokerProfile configuredProfile = profile.get();
        BrokerExchangeDefinition definition = configuredProfile.definition();

        try {
            configureSystemProperties(definition.exchange(), configuredProfile);
            ManagedBrokerSession session = new ManagedBrokerSession();
            session.exchangeName = definition.exchange();
            session.exchangeLabel = definition.displayName();
            session.environment = configuredProfile.environment().name();
            session.accountLabel = configuredProfile.accountLabel();
            session.profileUpdatedAt = configuredProfile.updatedAt();
            session.broker = createBroker(definition.exchange());
            attachListeners(session);
            session.broker.connect();
            session.connected = true;
            session.updatedAt = Instant.now();
            sessionsByExchange.put(definition.exchange(), session);
            loadOpenOrders(session);
        } catch (Exception e) {
            ManagedBrokerSession failedSession = new ManagedBrokerSession();
            failedSession.exchangeName = definition.exchange();
            failedSession.exchangeLabel = definition.displayName();
            failedSession.environment = configuredProfile.environment().name();
            failedSession.accountLabel = configuredProfile.accountLabel();
            failedSession.profileUpdatedAt = configuredProfile.updatedAt();
            failedSession.connected = false;
            failedSession.lastError = e.getMessage();
            failedSession.updatedAt = Instant.now();
            sessionsByExchange.put(definition.exchange(), failedSession);
            log.warn("Unable to initialize live broker for {}", definition.exchange(), e);
        }
    }

    private void disconnectExchange(String exchangeName) {
        ManagedBrokerSession existing = sessionsByExchange.remove(normalizeExchange(exchangeName));
        if (existing != null && existing.broker != null) {
            try {
                existing.broker.disconnect();
            } catch (Exception e) {
                log.debug("Unable to disconnect broker for {}", exchangeName, e);
            }
        }
    }

    private void clearExchangeState(String exchangeName) {
        clearExchangePositions(exchangeName);
        balancesByExchange.remove(normalizeExchange(exchangeName));
        ordersByInternalId.values().removeIf(order -> normalizeExchange(exchangeName).equals(order.exchangeName));
        orderInternalIdByExchangeOrderId.keySet().removeIf(key -> key.startsWith(normalizeExchange(exchangeName) + "|"));
        orderInternalIdByClientOrderId.keySet().removeIf(key -> key.startsWith(normalizeExchange(exchangeName) + "|"));
        synchronized (recentFills) {
            recentFills.removeIf(fill -> normalizeExchange(exchangeName).equals(fill.exchange()));
        }
    }

    private void attachListeners(ManagedBrokerSession session) {
        session.broker.addOrderEventListener(event -> handleOrderEvent(session, event));
        session.broker.addFillEventListener(fill -> handleFillEvent(session, fill));
        session.broker.addBrokerAccountInfoListener(new com.fueledbychai.broker.BrokerAccountInfoListener() {
            @Override
            public void accountEquityUpdated(double equity) {
                BalanceState state = balancesByExchange.computeIfAbsent(session.exchangeName,
                        ignored -> new BalanceState(session.exchangeName, session.exchangeLabel));
                state.equity = BigDecimal.valueOf(equity);
                state.updatedAt = Instant.now();
            }

            @Override
            public void availableFundsUpdated(double availableFunds) {
                BalanceState state = balancesByExchange.computeIfAbsent(session.exchangeName,
                        ignored -> new BalanceState(session.exchangeName, session.exchangeLabel));
                state.availableFunds = BigDecimal.valueOf(availableFunds);
                state.updatedAt = Instant.now();
            }
        });
        session.broker.addBrokerErrorListener(error -> handleBrokerError(session, error));
    }

    private void loadOpenOrders(ManagedBrokerSession session) {
        syncOpenOrders(session, "Loaded from broker on connect.", true);
    }

    private void syncOpenOrders(ManagedBrokerSession session, String note, boolean warnOnFailure) {
        if (session == null || session.broker == null) {
            return;
        }

        try {
            List<OrderTicket> openOrders = session.broker.getOpenOrders();
            Instant observedAt = Instant.now();
            Set<String> observedOrderIds = new HashSet<>();
            if (openOrders != null) {
                for (OrderTicket order : openOrders) {
                    if (order == null) {
                        continue;
                    }
                    LiveOrderRecord record = resolveOrderRecord(session.exchangeName,
                            trimToNull(order.getOrderId()),
                            trimToNull(order.getClientOrderId()));
                    observedOrderIds.add(record.id);
                    record.applyOpenOrder(session.exchangeName, session.exchangeLabel, order, observedAt, note);
                }
            }
            reconcileMissingOpenOrders(session, observedAt, observedOrderIds);
            session.updatedAt = observedAt;
        } catch (UnsupportedOperationException e) {
            log.debug("Open-order synchronization is not supported for {}", session.exchangeName, e);
        } catch (Exception e) {
            session.lastError = trimToNull(e.getMessage());
            session.updatedAt = Instant.now();
            if (warnOnFailure) {
                log.warn("Unable to synchronize open orders for {}", session.exchangeName, e);
            } else {
                log.debug("Unable to synchronize open orders for {}", session.exchangeName, e);
            }
        }
    }

    private void reconcileMissingOpenOrders(ManagedBrokerSession session,
                                            Instant observedAt,
                                            Set<String> observedOrderIds) {
        String normalizedExchange = normalizeExchange(session.exchangeName);
        for (LiveOrderRecord record : ordersByInternalId.values()) {
            if (record == null || !normalizedExchange.equals(record.exchangeName) || !record.isActive()) {
                continue;
            }
            if (observedOrderIds.contains(record.id)) {
                continue;
            }

            try {
                OrderTicket brokerOrder = fetchOrderFromBroker(session, record.exchangeOrderId, record.clientOrderId);
                if (brokerOrder != null) {
                    record.applyOpenOrder(session.exchangeName, session.exchangeLabel, brokerOrder, observedAt,
                            "Refreshed from live broker.");
                    continue;
                }
            } catch (UnsupportedOperationException e) {
                log.debug("Broker order status lookup is not supported for {}", session.exchangeName, e);
            } catch (Exception e) {
                log.debug("Unable to refresh missing broker order for {}", session.exchangeName, e);
            }

            record.reconcileMissingFromOpenOrders(observedAt);
        }
    }

    private void handleBrokerError(ManagedBrokerSession session, BrokerError error) {
        session.lastError = error == null ? "Unknown broker error" : error.getMessage();
        session.updatedAt = Instant.now();
    }

    private void handleOrderEvent(ManagedBrokerSession session, OrderEvent event) {
        if (event == null) {
            return;
        }

        OrderTicket order = event.getOrder();
        OrderStatus status = event.getOrderStatus();

        if (order == null && status == null) {
            return;
        }

        String exchangeOrderId = firstNonBlank(
                trimToNull(status == null ? null : status.getOrderId()),
                trimToNull(order == null ? null : order.getOrderId()));
        String clientOrderId = firstNonBlank(
                trimToNull(status == null ? null : status.getClientOrderId()),
                trimToNull(order == null ? null : order.getClientOrderId()));

        if (order == null && exchangeOrderId == null && clientOrderId == null) {
            log.debug("Ignoring broker order event for {} because no order identifiers were supplied.", session.exchangeName);
            return;
        }

        LiveOrderRecord record = resolveOrderRecord(session.exchangeName, exchangeOrderId, clientOrderId);
        record.exchangeName = session.exchangeName;
        record.exchangeLabel = session.exchangeLabel;
        record.applyFromOrderEvent(order, status);
        if (order == null) {
            hydrateOrderFromBroker(session, record, exchangeOrderId, clientOrderId);
        }
        session.updatedAt = Instant.now();
    }

    private void handleFillEvent(ManagedBrokerSession session, Fill fill) {
        if (fill == null) {
            return;
        }

        String id = trimToNull(fill.getFillId());
        if (id == null) {
            id = UUID.randomUUID().toString();
        }

        FillSnapshot snapshot = new FillSnapshot(
                id,
                session.exchangeName,
                session.exchangeLabel,
                firstNonBlank(commonSymbol(fill.getTicker()), symbol(fill.getTicker())),
                symbol(fill.getTicker()),
                mapSide(fill.getSide()),
                fill.getSize(),
                fill.getPrice(),
                fill.getCommission(),
                fill.getOrderId(),
                fill.getClientOrderId(),
                fill.isTaker(),
                fill.isSnapshot(),
                Optional.ofNullable(fill.getTime()).map(ZonedDateTime::toInstant).orElse(Instant.now())
        );

        synchronized (recentFills) {
            recentFills.addFirst(snapshot);
            while (recentFills.size() > MAX_FILLS) {
                recentFills.removeLast();
            }
        }

        LiveOrderRecord record = resolveOrderRecord(session.exchangeName, fill.getOrderId(), fill.getClientOrderId());
        record.applyFill(fill);
        session.updatedAt = Instant.now();
    }

    private void hydrateOrderFromBroker(ManagedBrokerSession session,
                                        LiveOrderRecord record,
                                        String exchangeOrderId,
                                        String clientOrderId) {
        if (session == null || session.broker == null || record == null) {
            return;
        }

        try {
            OrderTicket brokerOrder = fetchOrderFromBroker(session, exchangeOrderId, clientOrderId);
            if (brokerOrder == null) {
                return;
            }
            Instant observedAt = Instant.now();
            record.applyOpenOrder(session.exchangeName, session.exchangeLabel, brokerOrder, observedAt,
                    "Observed from broker order event.");
            session.updatedAt = observedAt;
        } catch (UnsupportedOperationException e) {
            log.debug("Broker order hydration is not supported for {}", session.exchangeName, e);
        } catch (Exception e) {
            log.debug("Unable to hydrate broker order for {}", session.exchangeName, e);
        }
    }

    private OrderTicket fetchOrderFromBroker(ManagedBrokerSession session,
                                             String exchangeOrderId,
                                             String clientOrderId) {
        if (session == null || session.broker == null) {
            return null;
        }

        OrderTicket brokerOrder = null;
        if (exchangeOrderId != null) {
            brokerOrder = session.broker.requestOrderStatus(exchangeOrderId);
        }
        if (brokerOrder == null && clientOrderId != null) {
            brokerOrder = session.broker.requestOrderStatusByClientOrderId(clientOrderId);
        }
        return brokerOrder;
    }

    private void replacePositions(String exchangeName, String exchangeLabel, List<Position> positions) {
        clearExchangePositions(exchangeName);
        if (positions == null) {
            return;
        }
        for (Position position : positions) {
            if (position == null || position.getTicker() == null || position.getSize() == null
                    || position.getSize().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            PositionSnapshot snapshot = new PositionSnapshot(
                    exchangeName,
                    exchangeLabel,
                    firstNonBlank(commonSymbol(position.getTicker()), symbol(position.getTicker())),
                    symbol(position.getTicker()),
                    mapPositionSide(position.getSide()),
                    position.getSize(),
                    position.getAverageCost(),
                    position.getLiquidationPrice(),
                    position.getStatus() == null ? "OPEN" : position.getStatus().name()
            );
            positionsByExchangeAndSymbol.put(exchangeName + "|" + snapshot.exchangeSymbol(), snapshot);
        }
    }

    private void clearExchangePositions(String exchangeName) {
        String normalized = normalizeExchange(exchangeName);
        positionsByExchangeAndSymbol.keySet().removeIf(key -> key.startsWith(normalized + "|"));
    }

    private LiveOrderRecord requireOrder(String internalOrderId) {
        LiveOrderRecord record = ordersByInternalId.get(internalOrderId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown order id: " + internalOrderId);
        }
        return record;
    }

    private ManagedBrokerSession requireSession(String exchangeName) {
        String normalized = normalizeExchange(exchangeName);
        ManagedBrokerSession session = sessionsByExchange.get(normalized);
        if (session == null || session.broker == null) {
            throw new IllegalStateException("No live broker profile is configured for " + normalized + ".");
        }
        if (!session.connected) {
            throw new IllegalStateException("Live broker for " + session.exchangeLabel + " is not connected."
                    + (session.lastError == null || session.lastError.isBlank() ? "" : " " + session.lastError));
        }
        return session;
    }

    private LiveOrderRecord resolveOrderRecord(String exchangeName, String exchangeOrderId, String clientOrderId) {
        String exchangeKey = orderLookupKey(exchangeName, exchangeOrderId);
        if (exchangeOrderId != null && orderInternalIdByExchangeOrderId.containsKey(exchangeKey)) {
            return ordersByInternalId.get(orderInternalIdByExchangeOrderId.get(exchangeKey));
        }

        String clientKey = clientLookupKey(exchangeName, clientOrderId);
        if (clientOrderId != null && orderInternalIdByClientOrderId.containsKey(clientKey)) {
            return ordersByInternalId.get(orderInternalIdByClientOrderId.get(clientKey));
        }

        LiveOrderRecord record = new LiveOrderRecord();
        record.id = UUID.randomUUID().toString();
        record.exchangeName = normalizeExchange(exchangeName);
        record.exchangeLabel = normalizeExchange(exchangeName);
        ordersByInternalId.put(record.id, record);
        if (exchangeOrderId != null && !exchangeOrderId.isBlank()) {
            orderInternalIdByExchangeOrderId.put(exchangeKey, record.id);
        }
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            orderInternalIdByClientOrderId.put(clientKey, record.id);
        }
        return record;
    }

    private void configureSystemProperties(String exchangeName,
                                           BrokerProfileService.ConfiguredBrokerProfile profile) {
        clearCachedApis(exchangeName);
        switch (normalizeExchange(exchangeName)) {
            case "HYPERLIQUID" -> {
                clearProperties("hyperliquid.account.address", "hyperliquid.private.key", "hyperliquid.sub.address",
                        "hyperliquid.environment");
                setProperty("hyperliquid.account.address", profile.fieldValues().get("accountAddress"));
                setProperty("hyperliquid.private.key", profile.fieldValues().get("privateKey"));
                setProperty("hyperliquid.sub.address", profile.fieldValues().get("subAccountAddress"));
                setProperty("hyperliquid.environment", mapEnvironment(exchangeName, profile.environment()));
                HyperliquidConfiguration.reset();
            }
            case "PARADEX" -> {
                clearProperties("paradex.account.address", "paradex.private.key", "paradex.environment");
                setProperty("paradex.account.address", profile.fieldValues().get("accountAddress"));
                setProperty("paradex.private.key", profile.fieldValues().get("privateKey"));
                setProperty("paradex.environment", mapEnvironment(exchangeName, profile.environment()));
                ParadexConfiguration.reset();
            }
            case "LIGHTER" -> {
                clearProperties("lighter.account.address", "lighter.private.key", "lighter.account.index",
                        "lighter.api.key.index", "lighter.signer.library.path", "lighter.environment");
                setProperty("lighter.account.address", profile.fieldValues().get("accountAddress"));
                setProperty("lighter.private.key", profile.fieldValues().get("privateKey"));
                setProperty("lighter.account.index", profile.fieldValues().get("accountIndex"));
                setProperty("lighter.api.key.index", profile.fieldValues().get("apiKeyIndex"));
                setProperty("lighter.signer.library.path", profile.fieldValues().get("signerLibraryPath"));
                setProperty("lighter.environment", mapEnvironment(exchangeName, profile.environment()));
                LighterConfiguration.reset();
            }
            case "ASTER" -> {
                clearProperties("aster.api.key", "aster.api.secret", "aster.environment");
                setProperty("aster.api.key", profile.fieldValues().get("apiKey"));
                setProperty("aster.api.secret", profile.fieldValues().get("apiSecret"));
                setProperty("aster.environment", mapEnvironment(exchangeName, profile.environment()));
                AsterConfiguration.reset();
            }
            case "BYBIT" -> {
                clearProperties("bybit.api.key", "bybit.api.secret", "bybit.environment");
                setProperty("bybit.api.key", profile.fieldValues().get("apiKey"));
                setProperty("bybit.api.secret", profile.fieldValues().get("apiSecret"));
                setProperty("bybit.environment", mapEnvironment(exchangeName, profile.environment()));
                BybitConfiguration.reset();
            }
            case "OKX" -> {
                clearProperties("okx.account.address", "okx.private.key", "okx.environment");
                setProperty("okx.account.address", profile.fieldValues().get("accountAddress"));
                setProperty("okx.private.key", profile.fieldValues().get("privateKey"));
                setProperty("okx.environment", mapEnvironment(exchangeName, profile.environment()));
                OkxConfiguration.reset();
            }
            case "DRIFT" -> {
                clearProperties("drift.gateway.rest.url", "drift.gateway.ws.url", "drift.sub.account.id",
                        "drift.environment");
                setProperty("drift.gateway.rest.url", profile.fieldValues().get("gatewayRestUrl"));
                setProperty("drift.gateway.ws.url", profile.fieldValues().get("gatewayWsUrl"));
                setProperty("drift.sub.account.id", profile.fieldValues().get("subAccountId"));
                setProperty("drift.environment", mapEnvironment(exchangeName, profile.environment()));
                DriftConfiguration.reset();
            }
            case "HIBACHI" -> {
                clearProperties("hibachi.api.key", "hibachi.api.secret", "hibachi.account.id",
                        "hibachi.environment");
                setProperty("hibachi.api.key", profile.fieldValues().get("apiKey"));
                setProperty("hibachi.api.secret", profile.fieldValues().get("apiSecret"));
                setProperty("hibachi.account.id", profile.fieldValues().get("accountId"));
                setProperty("hibachi.environment", mapEnvironment(exchangeName, profile.environment()));
                HibachiConfiguration.reset();
            }
            default -> throw new IllegalArgumentException("Unsupported broker exchange: " + exchangeName);
        }
    }

    private IBroker createBroker(String exchangeName) {
        return switch (normalizeExchange(exchangeName)) {
            case "HYPERLIQUID" -> new HyperliquidBroker();
            case "PARADEX" -> new ResilientParadexBroker();
            case "LIGHTER" -> new LighterBroker();
            case "ASTER" -> new AsterBroker();
            case "BYBIT" -> new BybitBroker();
            case "OKX" -> new OkxBroker();
            case "DRIFT" -> new DriftBroker();
            case "HIBACHI" -> new HibachiBroker();
            default -> throw new IllegalArgumentException("Unsupported broker exchange: " + exchangeName);
        };
    }

    private void clearCachedApis(String exchangeName) {
        Exchange exchange = exchangeForName(exchangeName);
        clearFactoryCache(ExchangeRestApiFactory.class, "publicApis", exchange);
        clearFactoryCache(ExchangeRestApiFactory.class, "apis", exchange);
        clearFactoryCache(ExchangeRestApiFactory.class, "privateApis", exchange);
        clearFactoryCache(ExchangeWebSocketApiFactory.class, "websocketApis", exchange);
    }

    @SuppressWarnings("unchecked")
    private void clearFactoryCache(Class<?> factoryClass, String fieldName, Exchange exchange) {
        try {
            Field field = factoryClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object cache = field.get(null);
            if (cache instanceof Map<?, ?> map) {
                ((Map<Exchange, Object>) map).remove(exchange);
            }
        } catch (ReflectiveOperationException e) {
            log.debug("Unable to clear {}.{} for {}", factoryClass.getSimpleName(), fieldName, exchange, e);
        }
    }

    private Exchange exchangeForName(String exchangeName) {
        return switch (normalizeExchange(exchangeName)) {
            case "HYPERLIQUID" -> Exchange.HYPERLIQUID;
            case "PARADEX" -> Exchange.PARADEX;
            case "LIGHTER" -> Exchange.LIGHTER;
            case "ASTER" -> Exchange.ASTER;
            case "BYBIT" -> Exchange.BYBIT;
            case "OKX" -> Exchange.OKX;
            case "DRIFT" -> Exchange.DRIFT;
            case "HIBACHI" -> Exchange.HIBACHI;
            default -> throw new IllegalArgumentException("Unsupported broker exchange: " + exchangeName);
        };
    }

    private OrderTicket buildOrderTicket(ManagedBrokerSession session,
                                         Ticker ticker,
                                         String sideValue,
                                         String orderTypeValue,
                                         String timeInForceValue,
                                         String clientOrderId,
                                         BigDecimal quantity,
                                         BigDecimal limitPrice) {
        OrderSide side = OrderSide.fromInput(sideValue);
        OrderType orderType = OrderType.fromInput(orderTypeValue);
        TimeInForce timeInForce = TimeInForce.fromInput(timeInForceValue);

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive.");
        }
        if (orderType == OrderType.LIMIT && (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("Limit orders require a limit price.");
        }
        if (orderType == OrderType.MARKET && timeInForce == TimeInForce.POST_ONLY) {
            throw new IllegalArgumentException("Post only is not valid for market orders.");
        }

        OrderTicket order = new OrderTicket();
        order.setTicker(ticker);
        order.setTradeDirection(side == OrderSide.BUY ? TradeDirection.BUY : TradeDirection.SELL);
        order.setType(orderType == OrderType.MARKET ? OrderTicket.Type.MARKET : OrderTicket.Type.LIMIT);
        order.setDuration(mapDuration(timeInForce));
        order.setSize(quantity.stripTrailingZeros());
        order.setClientOrderId(firstNonBlank(trimToNull(clientOrderId), session.broker.getNextOrderId()));
        if (orderType == OrderType.LIMIT) {
            order.setLimitPrice(limitPrice.stripTrailingZeros());
        }
        if (timeInForce == TimeInForce.POST_ONLY) {
            order.addModifier(OrderTicket.Modifier.POST_ONLY);
        }
        return order;
    }

    private OrderTicket.Duration mapDuration(TimeInForce timeInForce) {
        return switch (timeInForce) {
            case GTC, POST_ONLY -> OrderTicket.Duration.GOOD_UNTIL_CANCELED;
            case IOC -> OrderTicket.Duration.IMMEDIATE_OR_CANCEL;
        };
    }

    private String orderLookupKey(String exchangeName, String orderId) {
        return normalizeExchange(exchangeName) + "|" + Objects.toString(orderId, "");
    }

    private String clientLookupKey(String exchangeName, String clientOrderId) {
        return normalizeExchange(exchangeName) + "|" + Objects.toString(clientOrderId, "");
    }

    private void setProperty(String key, String value) {
        if (value == null || value.isBlank()) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }

    private void clearProperties(String... keys) {
        for (String key : keys) {
            System.clearProperty(key);
        }
    }

    private String mapEnvironment(String exchangeName, BrokerEnvironment environment) {
        if (environment == BrokerEnvironment.MAINNET) {
            return switch (normalizeExchange(exchangeName)) {
                case "DRIFT" -> "mainnet";
                default -> "prod";
            };
        }
        return switch (normalizeExchange(exchangeName)) {
            case "BYBIT", "OKX" -> "test";
            default -> "testnet";
        };
    }

    private String normalizeExchange(String exchangeName) {
        return exchangeName == null ? "" : exchangeName.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String commonSymbol(Ticker ticker) {
        return null;
    }

    private String symbol(Ticker ticker) {
        if (ticker == null || ticker.getSymbol() == null || ticker.getSymbol().isBlank()) {
            return null;
        }
        return ticker.getSymbol();
    }

    private String mapSide(TradeDirection direction) {
        if (direction == null) {
            return "UNKNOWN";
        }
        return switch (direction) {
            case BUY, BUY_TO_COVER -> "BUY";
            case SELL, SELL_SHORT -> "SELL";
        };
    }

    private String mapPositionSide(Side side) {
        return side == null ? "UNKNOWN" : side.name();
    }

    private String mapOrderType(OrderTicket.Type type) {
        if (type == null) {
            return OrderType.MARKET.name();
        }
        return type == OrderTicket.Type.MARKET ? OrderType.MARKET.name() : OrderType.LIMIT.name();
    }

    private String mapTimeInForce(OrderTicket order) {
        if (order != null && order.containsModifier(OrderTicket.Modifier.POST_ONLY)) {
            return TimeInForce.POST_ONLY.name();
        }
        if (order == null || order.getDuration() == null) {
            return TimeInForce.GTC.name();
        }
        return switch (order.getDuration()) {
            case IMMEDIATE_OR_CANCEL -> TimeInForce.IOC.name();
            default -> TimeInForce.GTC.name();
        };
    }

    private String mapAssetType(Ticker ticker, String existing) {
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        if (ticker == null || ticker.getInstrumentType() == null) {
            return null;
        }
        try {
            return SupportedAssetType.fromInstrumentType(ticker.getInstrumentType()).name();
        } catch (Exception e) {
            return null;
        }
    }

    private final class LiveOrderRecord {
        private String id;
        private String marketId;
        private String exchangeName;
        private String exchangeLabel;
        private String symbol;
        private String exchangeSymbol;
        private String assetType;
        private String side;
        private String orderType;
        private String timeInForce;
        private String exchangeOrderId;
        private String clientOrderId;
        private BigDecimal quantity;
        private BigDecimal filledQuantity = BigDecimal.ZERO;
        private BigDecimal remainingQuantity;
        private BigDecimal limitPrice;
        private BigDecimal averageFillPrice;
        private String status = OrderStatus.Status.NEW.name();
        private String cancelReason;
        private String note;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = createdAt;
        private Instant canceledAt;
        private Instant filledAt;
        private Ticker ticker;

        private synchronized void applyPlacement(String marketId,
                                                 String symbol,
                                                 String exchangeSymbol,
                                                 SupportedExchange exchange,
                                                 SupportedAssetType assetType,
                                                 OrderTicket order,
                                                 String note) {
            this.marketId = marketId;
            this.exchangeName = exchange.name();
            this.exchangeLabel = exchange.displayName();
            this.symbol = symbol;
            this.exchangeSymbol = exchangeSymbol;
            this.assetType = assetType.name();
            this.ticker = order.getTicker();
            this.side = mapSide(order.getTradeDirection());
            this.orderType = mapOrderType(order.getType());
            this.timeInForce = mapTimeInForce(order);
            this.exchangeOrderId = trimToNull(order.getOrderId());
            this.clientOrderId = trimToNull(order.getClientOrderId());
            this.quantity = order.getSize();
            this.remainingQuantity = order.getSize();
            this.limitPrice = order.getLimitPrice();
            this.status = OrderStatus.Status.NEW.name();
            this.note = note;
            this.createdAt = Instant.now();
            this.updatedAt = this.createdAt;
            if (exchangeOrderId != null) {
                orderInternalIdByExchangeOrderId.put(orderLookupKey(exchangeName, exchangeOrderId), id);
            }
            if (clientOrderId != null) {
                orderInternalIdByClientOrderId.put(clientLookupKey(exchangeName, clientOrderId), id);
            }
        }

        private synchronized void applyModifyRequest(OrderTicket order) {
            this.side = mapSide(order.getTradeDirection());
            this.orderType = mapOrderType(order.getType());
            this.timeInForce = mapTimeInForce(order);
            this.clientOrderId = trimToNull(order.getClientOrderId());
            this.quantity = order.getSize();
            this.limitPrice = order.getLimitPrice();
            this.remainingQuantity = quantity.subtract(filledQuantity == null ? BigDecimal.ZERO : filledQuantity);
            this.note = "Modify requested.";
            this.updatedAt = Instant.now();
            if (clientOrderId != null) {
                orderInternalIdByClientOrderId.put(clientLookupKey(exchangeName, clientOrderId), id);
            }
        }

        private synchronized void applyFailure(String message) {
            this.status = OrderStatus.Status.REJECTED.name();
            this.note = message;
            this.updatedAt = Instant.now();
        }

        private synchronized void markPendingCancel() {
            this.status = OrderStatus.Status.PENDING_CANCEL.name();
            this.note = "Cancel requested.";
            this.updatedAt = Instant.now();
        }

        private synchronized boolean isActive() {
            if (status == null || status.isBlank()) {
                return true;
            }
            return switch (status.trim().toUpperCase(Locale.ROOT)) {
                case "NEW", "PARTIAL_FILL", "PENDING_CANCEL", "REPLACED" -> true;
                default -> false;
            };
        }

        private synchronized void applyOpenOrder(String exchangeName,
                                                 String exchangeLabel,
                                                 OrderTicket order,
                                                 Instant observedAt,
                                                 String note) {
            this.exchangeName = exchangeName;
            this.exchangeLabel = exchangeLabel;
            this.ticker = order.getTicker() != null ? order.getTicker() : this.ticker;
            this.symbol = firstNonBlank(this.symbol, commonSymbol(order.getTicker()), symbol(order.getTicker()));
            this.exchangeSymbol = firstNonBlank(symbol(order.getTicker()), this.exchangeSymbol);
            this.assetType = mapAssetType(order.getTicker(), this.assetType);
            this.side = mapSide(order.getTradeDirection());
            this.orderType = mapOrderType(order.getType());
            this.timeInForce = mapTimeInForce(order);
            this.exchangeOrderId = firstNonBlank(trimToNull(order.getOrderId()), this.exchangeOrderId);
            this.clientOrderId = firstNonBlank(trimToNull(order.getClientOrderId()), this.clientOrderId);
            this.quantity = firstNonNull(order.getSize(), this.quantity);
            this.filledQuantity = firstNonNull(order.getFilledSize(), this.filledQuantity);
            this.remainingQuantity = firstNonNull(order.getRemainingSize(), this.remainingQuantity);
            this.limitPrice = firstNonNull(order.getLimitPrice(), this.limitPrice);
            this.averageFillPrice = firstNonNull(order.getFilledPrice(), this.averageFillPrice);
            this.status = Optional.ofNullable(order.getCurrentStatus()).orElse(OrderStatus.Status.NEW).name();

            Instant createdAt = Optional.ofNullable(order.getOrderEntryTime()).map(ZonedDateTime::toInstant).orElse(observedAt);
            if (this.createdAt == null || createdAt.isBefore(this.createdAt)) {
                this.createdAt = createdAt;
            }
            this.updatedAt = observedAt;
            this.filledAt = Optional.ofNullable(order.getOrderFilledTime()).map(ZonedDateTime::toInstant).orElse(this.filledAt);
            this.note = note;

            if (exchangeOrderId != null) {
                orderInternalIdByExchangeOrderId.put(orderLookupKey(exchangeName, exchangeOrderId), id);
            }
            if (clientOrderId != null) {
                orderInternalIdByClientOrderId.put(clientLookupKey(exchangeName, clientOrderId), id);
            }
        }

        private synchronized void reconcileMissingFromOpenOrders(Instant observedAt) {
            BigDecimal normalizedFilled = filledQuantity == null ? BigDecimal.ZERO : filledQuantity;
            this.updatedAt = observedAt;
            if (quantity != null && normalizedFilled.compareTo(quantity) >= 0) {
                this.status = OrderStatus.Status.FILLED.name();
                this.remainingQuantity = BigDecimal.ZERO;
                if (this.filledAt == null) {
                    this.filledAt = observedAt;
                }
                this.note = "No longer reported by broker open orders.";
                return;
            }

            this.status = OrderStatus.Status.CANCELED.name();
            if (this.quantity != null) {
                BigDecimal nextRemaining = this.quantity.subtract(normalizedFilled);
                this.remainingQuantity = nextRemaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : nextRemaining;
            }
            if (this.canceledAt == null) {
                this.canceledAt = observedAt;
            }
            this.note = "No longer reported by broker open orders.";
        }

        private synchronized void applyFromOrderEvent(OrderTicket order, OrderStatus orderStatus) {
            if (order != null) {
                this.ticker = order.getTicker() != null ? order.getTicker() : this.ticker;
                this.symbol = firstNonBlank(this.symbol, commonSymbol(order.getTicker()), symbol(order.getTicker()));
                this.exchangeSymbol = firstNonBlank(symbol(order.getTicker()), this.exchangeSymbol);
                this.side = mapSide(order.getTradeDirection());
                this.orderType = mapOrderType(order.getType());
                this.timeInForce = mapTimeInForce(order);
                this.exchangeOrderId = firstNonBlank(trimToNull(order.getOrderId()), this.exchangeOrderId);
                this.clientOrderId = firstNonBlank(trimToNull(order.getClientOrderId()), this.clientOrderId);
                this.quantity = firstNonNull(order.getSize(), this.quantity);
                this.limitPrice = firstNonNull(order.getLimitPrice(), this.limitPrice);
                this.assetType = mapAssetType(order.getTicker(), this.assetType);
            }

            if (orderStatus != null) {
                this.ticker = orderStatus.getTicker() != null ? orderStatus.getTicker() : this.ticker;
                this.symbol = firstNonBlank(this.symbol, commonSymbol(orderStatus.getTicker()), symbol(orderStatus.getTicker()));
                this.exchangeSymbol = firstNonBlank(symbol(orderStatus.getTicker()), this.exchangeSymbol);
                this.assetType = mapAssetType(orderStatus.getTicker(), this.assetType);
                this.exchangeOrderId = firstNonBlank(trimToNull(orderStatus.getOrderId()), this.exchangeOrderId);
                this.clientOrderId = firstNonBlank(trimToNull(orderStatus.getClientOrderId()), this.clientOrderId);
                this.filledQuantity = firstNonNull(orderStatus.getFilled(), this.filledQuantity);
                this.remainingQuantity = firstNonNull(orderStatus.getRemaining(), this.remainingQuantity);
                this.averageFillPrice = firstNonNull(orderStatus.getFillPrice(), this.averageFillPrice);
                this.quantity = inferQuantity(this.quantity, orderStatus);
                this.status = orderStatus.getStatus() == null ? this.status : orderStatus.getStatus().name();
                this.cancelReason = null;
                this.updatedAt = Optional.ofNullable(orderStatus.getTimestamp()).map(ZonedDateTime::toInstant).orElse(Instant.now());
                if (orderStatus.getStatus() == OrderStatus.Status.CANCELED) {
                    this.canceledAt = updatedAt;
                }
                if (orderStatus.getStatus() == OrderStatus.Status.FILLED) {
                    this.filledAt = updatedAt;
                }
                if (orderStatus.getStatus() == OrderStatus.Status.REJECTED && (note == null || note.isBlank())) {
                    this.note = "Order rejected by broker.";
                } else if (order == null && (note == null || note.isBlank())) {
                    this.note = "Observed from broker order event.";
                }
            }

            if (exchangeOrderId != null) {
                orderInternalIdByExchangeOrderId.put(orderLookupKey(exchangeName, exchangeOrderId), id);
            }
            if (clientOrderId != null) {
                orderInternalIdByClientOrderId.put(clientLookupKey(exchangeName, clientOrderId), id);
            }
        }

        private synchronized void applyFill(Fill fill) {
            this.symbol = firstNonBlank(this.symbol, commonSymbol(fill.getTicker()), symbol(fill.getTicker()));
            this.exchangeSymbol = firstNonBlank(symbol(fill.getTicker()), this.exchangeSymbol);
            this.exchangeOrderId = firstNonBlank(trimToNull(fill.getOrderId()), this.exchangeOrderId);
            this.clientOrderId = firstNonBlank(trimToNull(fill.getClientOrderId()), this.clientOrderId);
            this.averageFillPrice = fill.getPrice();
            if (fill.getSize() != null) {
                this.filledQuantity = (filledQuantity == null ? BigDecimal.ZERO : filledQuantity).add(fill.getSize());
                if (quantity != null) {
                    this.remainingQuantity = quantity.subtract(filledQuantity);
                    if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                        this.remainingQuantity = BigDecimal.ZERO;
                        this.status = OrderStatus.Status.FILLED.name();
                        this.filledAt = Optional.ofNullable(fill.getTime()).map(ZonedDateTime::toInstant).orElse(Instant.now());
                    } else {
                        this.status = OrderStatus.Status.PARTIAL_FILL.name();
                    }
                }
            }
            this.updatedAt = Optional.ofNullable(fill.getTime()).map(ZonedDateTime::toInstant).orElse(Instant.now());
            if (exchangeOrderId != null) {
                orderInternalIdByExchangeOrderId.put(orderLookupKey(exchangeName, exchangeOrderId), id);
            }
            if (clientOrderId != null) {
                orderInternalIdByClientOrderId.put(clientLookupKey(exchangeName, clientOrderId), id);
            }
        }

        private synchronized OrderSnapshot toSnapshot(String exchangeLabel) {
            return new OrderSnapshot(
                    id,
                    marketId,
                    symbol,
                    exchangeName,
                    exchangeLabel,
                    exchangeSymbol,
                    assetType,
                    side,
                    orderType,
                    timeInForce,
                    exchangeOrderId,
                    clientOrderId,
                    quantity,
                    filledQuantity,
                    remainingQuantity,
                    limitPrice,
                    averageFillPrice,
                    status,
                    cancelReason,
                    note,
                    createdAt,
                    updatedAt,
                    canceledAt,
                    filledAt
            );
        }
    }

    private static BigDecimal firstNonNull(BigDecimal left, BigDecimal right) {
        return left != null ? left : right;
    }

    private static BigDecimal inferQuantity(BigDecimal existingQuantity, OrderStatus orderStatus) {
        if (existingQuantity != null || orderStatus == null) {
            return existingQuantity;
        }

        BigDecimal filled = orderStatus.getFilled();
        BigDecimal remaining = orderStatus.getRemaining();
        if (filled != null && remaining != null) {
            return filled.add(remaining);
        }
        if (orderStatus.getStatus() == OrderStatus.Status.FILLED && filled != null) {
            return filled;
        }
        if ((orderStatus.getStatus() == OrderStatus.Status.NEW
                || orderStatus.getStatus() == OrderStatus.Status.CANCELED
                || orderStatus.getStatus() == OrderStatus.Status.REJECTED) && remaining != null) {
            return remaining;
        }
        return null;
    }

    private static final class ManagedBrokerSession {
        private String exchangeName;
        private String exchangeLabel;
        private String environment;
        private String accountLabel;
        private Instant profileUpdatedAt;
        private IBroker broker;
        private boolean connected;
        private boolean positionsSupported = true;
        private String lastError;
        private Instant updatedAt;
    }

    private static final class BalanceState {
        private final String exchangeName;
        private final String exchangeLabel;
        private BigDecimal equity;
        private BigDecimal availableFunds;
        private Instant updatedAt;

        private BalanceState(String exchangeName, String exchangeLabel) {
            this.exchangeName = exchangeName;
            this.exchangeLabel = exchangeLabel;
        }

        private BalanceSnapshot toSnapshot() {
            return new BalanceSnapshot(exchangeName, exchangeLabel, equity, availableFunds, updatedAt);
        }

        private String exchangeName() {
            return exchangeName;
        }
    }
}
