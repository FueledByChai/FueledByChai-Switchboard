package com.fueledbychai.switchboard.controller;

import com.fueledbychai.switchboard.api.SwitchboardSnapshot;
import com.fueledbychai.switchboard.api.BrokerProfileSnapshot;
import com.fueledbychai.switchboard.api.BrokerProfilesResponse;
import com.fueledbychai.switchboard.api.BrokerProfileUpsertRequest;
import com.fueledbychai.switchboard.api.InstrumentLookupOption;
import com.fueledbychai.switchboard.api.MarketSnapshot;
import com.fueledbychai.switchboard.api.MarketSubscriptionRequest;
import com.fueledbychai.switchboard.api.OrderModifyRequest;
import com.fueledbychai.switchboard.api.OrderSnapshot;
import com.fueledbychai.switchboard.api.OrderTicketRequest;
import com.fueledbychai.switchboard.api.SnapshotQuoteRequest;
import com.fueledbychai.switchboard.api.SnapshotQuoteResponse;
import com.fueledbychai.switchboard.api.PairSnapshot;
import com.fueledbychai.switchboard.api.PairSubscriptionRequest;
import com.fueledbychai.switchboard.api.PriceHistoryPoint;
import com.fueledbychai.switchboard.model.OrderSide;
import com.fueledbychai.switchboard.model.OrderType;
import com.fueledbychai.switchboard.model.SupportedAssetType;
import com.fueledbychai.switchboard.model.SupportedExchange;
import com.fueledbychai.switchboard.model.TimeInForce;
import com.fueledbychai.switchboard.service.SwitchboardStateService;
import com.fueledbychai.switchboard.service.BrokerProfileService;
import com.fueledbychai.switchboard.service.MarketDataCatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SwitchboardApiController {

    private final SwitchboardStateService switchboardStateService;
    private final MarketDataCatalogService marketDataCatalogService;
    private final BrokerProfileService brokerProfileService;

    public SwitchboardApiController(SwitchboardStateService switchboardStateService,
                                    MarketDataCatalogService marketDataCatalogService,
                                    BrokerProfileService brokerProfileService) {
        this.switchboardStateService = switchboardStateService;
        this.marketDataCatalogService = marketDataCatalogService;
        this.brokerProfileService = brokerProfileService;
    }

    @GetMapping("/snapshot")
    public SwitchboardSnapshot snapshot() {
        return switchboardStateService.fullSnapshot();
    }

    @GetMapping("/markets")
    public List<MarketSnapshot> markets() {
        return switchboardStateService.marketSnapshots();
    }

    @GetMapping("/instruments")
    public List<InstrumentLookupOption> instruments(@RequestParam(required = false) String symbol,
                                                    @RequestParam(required = false) String exchange,
                                                    @RequestParam(required = false) String assetType) {
        return switchboardStateService.instrumentLookup(symbol, exchange, assetType);
    }

    @PostMapping("/markets")
    @ResponseStatus(HttpStatus.CREATED)
    public MarketSnapshot addMarket(@Valid @RequestBody MarketSubscriptionRequest request) {
        return switchboardStateService.addMarket(request);
    }

    @DeleteMapping("/markets/{marketId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMarket(@PathVariable String marketId) {
        switchboardStateService.removeMarket(marketId);
    }

    @PostMapping("/snapshot-quote")
    public SnapshotQuoteResponse snapshotQuote(@Valid @RequestBody SnapshotQuoteRequest request) {
        return switchboardStateService.requestSnapshotQuote(request);
    }

    @GetMapping("/history/{marketId}")
    public List<PriceHistoryPoint> history(@PathVariable String marketId) {
        return switchboardStateService.history(marketId);
    }

    @GetMapping("/orders")
    public List<OrderSnapshot> orders() {
        return switchboardStateService.orderSnapshots();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderSnapshot submitOrder(@Valid @RequestBody OrderTicketRequest request) {
        return switchboardStateService.submitOrder(request);
    }

    @DeleteMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable String orderId) {
        switchboardStateService.cancelOrder(orderId);
    }

    @DeleteMapping("/orders/by-client/{exchangeName}/{clientOrderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrderByClientOrderId(@PathVariable String exchangeName,
                                           @PathVariable String clientOrderId) {
        switchboardStateService.cancelOrderByClientOrderId(exchangeName, clientOrderId);
    }

    @PostMapping("/orders/{orderId}")
    public OrderSnapshot modifyOrder(@PathVariable String orderId,
                                     @Valid @RequestBody OrderModifyRequest request) {
        return switchboardStateService.modifyOrder(orderId, request);
    }

    @PostMapping("/pairs")
    @ResponseStatus(HttpStatus.CREATED)
    public PairSnapshot addPair(@Valid @RequestBody PairSubscriptionRequest request) {
        return switchboardStateService.addPair(request);
    }

    @DeleteMapping("/pairs/{pairId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePair(@PathVariable String pairId) {
        switchboardStateService.removePair(pairId);
    }

    @GetMapping("/catalog/{exchangeName}/instruments")
    public List<Map<String, String>> catalogInstruments(@PathVariable String exchangeName,
                                                        @RequestParam(required = false) String assetType) {
        return marketDataCatalogService.instrumentsForExchange(exchangeName, assetType);
    }

    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog() {
        return marketDataCatalogService.supportedExchanges().stream()
                .map(exchange -> {
                    Map<String, Integer> counts = marketDataCatalogService.tickerCountsForExchange(exchange.name());
                    return Map.<String, Object>of(
                            "name", exchange.name(),
                            "displayName", exchange.displayName(),
                            "assetTypes", exchange.supportedAssetTypes().stream()
                                    .map(at -> Map.<String, Object>of(
                                            "name", at.name(),
                                            "label", at.displayName(),
                                            "tickerCount", counts.getOrDefault(at.name(), 0)
                                    ))
                                    .toList()
                    );
                })
                .toList();
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadata() {
        return Map.of(
                "exchanges", marketDataCatalogService.supportedExchanges().stream()
                        .map(value -> Map.of(
                                "value", value.name(),
                                "label", value.displayName()))
                        .toList(),
                "assetTypes", marketDataCatalogService.supportedAssetTypes().stream()
                        .map(value -> Map.of(
                                "value", value.name(),
                                "label", value.displayName()))
                        .toList(),
                "orderSides", Arrays.stream(OrderSide.values())
                        .map(value -> Map.of(
                                "value", value.name(),
                                "label", value.displayName()))
                        .toList(),
                "orderTypes", Arrays.stream(OrderType.values())
                        .map(value -> Map.of(
                                "value", value.name(),
                                "label", value.displayName()))
                        .toList(),
                "timeInForce", Arrays.stream(TimeInForce.values())
                        .map(value -> Map.of(
                                "value", value.name(),
                                "label", value.displayName()))
                        .toList(),
                "orderMode", "LIVE",
                "maxRows", switchboardStateService.maxRows()
        );
    }

    @GetMapping("/admin/broker/profiles")
    public BrokerProfilesResponse brokerProfiles() {
        return brokerProfileService.brokerProfilesResponse();
    }

    @PutMapping("/admin/broker/profiles/{exchangeName}")
    public BrokerProfileSnapshot upsertBrokerProfile(@PathVariable String exchangeName,
                                                     @Valid @RequestBody BrokerProfileUpsertRequest request) {
        return brokerProfileService.upsertProfile(exchangeName, request);
    }

    @DeleteMapping("/admin/broker/profiles/{exchangeName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBrokerProfile(@PathVariable String exchangeName) {
        brokerProfileService.deleteProfile(exchangeName);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> illegalState(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
