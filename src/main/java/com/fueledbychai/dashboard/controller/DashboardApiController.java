package com.fueledbychai.dashboard.controller;

import com.fueledbychai.dashboard.api.DashboardSnapshot;
import com.fueledbychai.dashboard.api.InstrumentLookupOption;
import com.fueledbychai.dashboard.api.MarketSnapshot;
import com.fueledbychai.dashboard.api.MarketSubscriptionRequest;
import com.fueledbychai.dashboard.api.OrderSnapshot;
import com.fueledbychai.dashboard.api.OrderTicketRequest;
import com.fueledbychai.dashboard.api.PairSnapshot;
import com.fueledbychai.dashboard.api.PairSubscriptionRequest;
import com.fueledbychai.dashboard.api.PriceHistoryPoint;
import com.fueledbychai.dashboard.model.OrderSide;
import com.fueledbychai.dashboard.model.OrderType;
import com.fueledbychai.dashboard.model.SupportedAssetType;
import com.fueledbychai.dashboard.model.SupportedExchange;
import com.fueledbychai.dashboard.model.TimeInForce;
import com.fueledbychai.dashboard.service.DashboardStateService;
import com.fueledbychai.dashboard.service.MarketDataCatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
public class DashboardApiController {

    private final DashboardStateService dashboardStateService;
    private final MarketDataCatalogService marketDataCatalogService;

    public DashboardApiController(DashboardStateService dashboardStateService,
                                  MarketDataCatalogService marketDataCatalogService) {
        this.dashboardStateService = dashboardStateService;
        this.marketDataCatalogService = marketDataCatalogService;
    }

    @GetMapping("/snapshot")
    public DashboardSnapshot snapshot() {
        return dashboardStateService.fullSnapshot();
    }

    @GetMapping("/markets")
    public List<MarketSnapshot> markets() {
        return dashboardStateService.marketSnapshots();
    }

    @GetMapping("/instruments")
    public List<InstrumentLookupOption> instruments(@RequestParam("symbol") String symbol) {
        return dashboardStateService.instrumentLookup(symbol);
    }

    @PostMapping("/markets")
    @ResponseStatus(HttpStatus.CREATED)
    public MarketSnapshot addMarket(@Valid @RequestBody MarketSubscriptionRequest request) {
        return dashboardStateService.addMarket(request);
    }

    @DeleteMapping("/markets/{marketId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMarket(@PathVariable String marketId) {
        dashboardStateService.removeMarket(marketId);
    }

    @GetMapping("/history/{marketId}")
    public List<PriceHistoryPoint> history(@PathVariable String marketId) {
        return dashboardStateService.history(marketId);
    }

    @GetMapping("/orders")
    public List<OrderSnapshot> orders() {
        return dashboardStateService.orderSnapshots();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderSnapshot submitOrder(@Valid @RequestBody OrderTicketRequest request) {
        return dashboardStateService.submitOrder(request);
    }

    @DeleteMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable String orderId) {
        dashboardStateService.cancelOrder(orderId);
    }

    @PostMapping("/pairs")
    @ResponseStatus(HttpStatus.CREATED)
    public PairSnapshot addPair(@Valid @RequestBody PairSubscriptionRequest request) {
        return dashboardStateService.addPair(request);
    }

    @DeleteMapping("/pairs/{pairId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePair(@PathVariable String pairId) {
        dashboardStateService.removePair(pairId);
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
                "orderMode", "PAPER",
                "maxRows", dashboardStateService.maxRows()
        );
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
