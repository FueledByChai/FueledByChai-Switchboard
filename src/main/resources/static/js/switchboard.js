(function () {
    const TICK_FLASH_MS = 1400;
    const MIN_WATCHLIST_ROWS = 20;
    const THEME_STORAGE_KEY = "switchboard.theme";
    const DEFAULT_THEME_ID = "switchboard-dark";
    const THEMES = Object.freeze([
        {id: "switchboard-dark", label: "Switchboard Dark"},
        {id: "bloomberg-inspired", label: "Bloomberg Inspired"},
        {id: "tradingview-midnight", label: "TradingView Midnight"},
        {id: "reuters-eikon", label: "Reuters Eikon"},
        {id: "tws-mosaic", label: "TWS Mosaic"},
        {id: "cqg-green", label: "CQG Green"},
        {id: "sierra-chart", label: "Sierra Chart"},
        {id: "nasdaq-depth", label: "Nasdaq Depth"},
        {id: "ice-blue", label: "ICE Blue"},
        {id: "midnight-mono", label: "Midnight Mono"}
    ]);

    const state = {
        metadata: defaultMetadata(),
        markets: [],
        orders: [],
        quoteLatencies: [],
        brokerConnections: [],
        balances: [],
        positions: [],
        fills: [],
        brokerProfiles: {
            definitions: [],
            profiles: []
        },
        selectedMarketId: null,
        selectedSide: "BUY",
        editingOrderId: null,
        selectedProfileExchange: null,
        lastValueByField: new Map(),
        tickDirectionByField: new Map(),
        precisionByMarket: new Map(),
        marketDrafts: new Map(),
        serverTime: null,
        ws: null,
        reconnectTimer: null,
        toastTimer: null,
        marketLayoutSignature: "",
        orderRenderSignature: "",
        themeId: DEFAULT_THEME_ID,
        instrumentPicker: defaultInstrumentPicker(),
        catalogData: null,
        csrfToken: document.querySelector('meta[name="_csrf"]')?.content || "",
        csrfHeader: document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN"
    };

    const dom = {
        connectionBadge: document.getElementById("connectionBadge"),
        serverClock: document.getElementById("serverClock"),
        coverageCount: document.getElementById("coverageCount"),
        themeSelect: document.getElementById("themeSelect"),
        orderModePill: document.getElementById("orderModePill"),
        toast: document.getElementById("toast"),
        marketRows: document.getElementById("marketRows"),
        ticketEmpty: document.getElementById("ticketEmpty"),
        ticketShell: document.getElementById("ticketShell"),
        selectedTicker: document.getElementById("selectedTicker"),
        selectedExchangeSymbol: document.getElementById("selectedExchangeSymbol"),
        selectedRoute: document.getElementById("selectedRoute"),
        selectedAsset: document.getElementById("selectedAsset"),
        selectedBid: document.getElementById("selectedBid"),
        selectedAsk: document.getElementById("selectedAsk"),
        selectedLast: document.getElementById("selectedLast"),
        orderForm: document.getElementById("orderForm"),
        orderTypeSelect: document.getElementById("orderTypeSelect"),
        timeInForceSelect: document.getElementById("timeInForceSelect"),
        clientOrderIdInput: document.getElementById("clientOrderIdInput"),
        quantityInput: document.getElementById("quantityInput"),
        limitPriceField: document.getElementById("limitPriceField"),
        limitPriceInput: document.getElementById("limitPriceInput"),
        ticketReferencePrice: document.getElementById("ticketReferencePrice"),
        ticketEstimatedNotional: document.getElementById("ticketEstimatedNotional"),
        ticketMode: document.getElementById("ticketMode"),
        ticketNote: document.getElementById("ticketNote"),
        submitOrderButton: document.getElementById("submitOrderButton"),
        clearOrderEditButton: document.getElementById("clearOrderEditButton"),
        sideButtons: Array.from(document.querySelectorAll(".side-btn")),
        orderRows: document.getElementById("orderRows"),
        brokerConnectionRows: document.getElementById("brokerConnectionRows"),
        balanceRows: document.getElementById("balanceRows"),
        positionRows: document.getElementById("positionRows"),
        fillRows: document.getElementById("fillRows"),
        instrumentDialog: document.getElementById("instrumentDialog"),
        instrumentDialogPanel: document.getElementById("instrumentDialogPanel"),
        instrumentDialogTitle: document.getElementById("instrumentDialogTitle"),
        instrumentDialogSubtitle: document.getElementById("instrumentDialogSubtitle"),
        instrumentDialogRows: document.getElementById("instrumentDialogRows"),
        closeInstrumentDialogButton: document.getElementById("closeInstrumentDialogButton"),
        cancelInstrumentButton: document.getElementById("cancelInstrumentButton"),
        confirmInstrumentButton: document.getElementById("confirmInstrumentButton"),
        openCatalogBtn: document.getElementById("openCatalogBtn"),
        openLatencyBtn: document.getElementById("openLatencyBtn"),
        catalogDialog: document.getElementById("catalogDialog"),
        catalogDialogPanel: document.getElementById("catalogDialogPanel"),
        closeCatalogDialogButton: document.getElementById("closeCatalogDialogButton"),
        catalogCloseButton: document.getElementById("catalogCloseButton"),
        catalogDialogRows: document.getElementById("catalogDialogRows"),
        catalogTableHead: document.getElementById("catalogTableHead"),
        catalogDrilldownHead: document.getElementById("catalogDrilldownHead"),
        catalogDrilldownTitle: document.getElementById("catalogDrilldownTitle"),
        backFromDrilldownBtn: document.getElementById("backFromDrilldownBtn"),
        latencyDialog: document.getElementById("latencyDialog"),
        latencyDialogPanel: document.getElementById("latencyDialogPanel"),
        closeLatencyDialogButton: document.getElementById("closeLatencyDialogButton"),
        latencyCloseButton: document.getElementById("latencyCloseButton"),
        latencyRows: document.getElementById("latencyRows"),
        openProfilesBtn: document.getElementById("openProfilesBtn"),
        brokerProfilesDialog: document.getElementById("brokerProfilesDialog"),
        brokerProfilesDialogPanel: document.getElementById("brokerProfilesDialogPanel"),
        closeProfilesDialogButton: document.getElementById("closeProfilesDialogButton"),
        brokerProfileRows: document.getElementById("brokerProfileRows"),
        brokerProfileForm: document.getElementById("brokerProfileForm"),
        profileExchangeInput: document.getElementById("profileExchangeInput"),
        profileEnvironmentSelect: document.getElementById("profileEnvironmentSelect"),
        profileFieldContainer: document.getElementById("profileFieldContainer"),
        profileFormTitle: document.getElementById("profileFormTitle"),
        profileFormSubtitle: document.getElementById("profileFormSubtitle"),
        saveProfileButton: document.getElementById("saveProfileButton"),
        deleteProfileButton: document.getElementById("deleteProfileButton")
    };

    init();

    function init() {
        hydrateTheme();
        hydrateMetadata();
        bindEvents();
        renderAll();
        loadBootstrap()
            .finally(connectWebSocket);
    }

    function defaultMetadata() {
        return {
            exchanges: [
                {value: "LIGHTER", label: "Lighter"},
                {value: "HYPERLIQUID", label: "Hyperliquid"},
                {value: "PARADEX", label: "Paradex"},
                {value: "BINANCE_SPOT", label: "Binance Spot"}
            ],
            assetTypes: [
                {value: "PERP", label: "Perp"},
                {value: "SPOT", label: "Spot"}
            ],
            orderTypes: [
                {value: "LIMIT", label: "Limit"},
                {value: "MARKET", label: "Market"}
            ],
            timeInForce: [
                {value: "GTC", label: "GTC"},
                {value: "IOC", label: "IOC"},
                {value: "POST_ONLY", label: "Post Only"}
            ],
            orderMode: "LIVE",
            maxRows: 50
        };
    }

    function defaultInstrumentPicker() {
        return {
            open: false,
            rowIndex: null,
            query: "",
            candidates: [],
            selectedKeys: new Set(),
            focusedIndex: null,
            anchorIndex: null,
            loading: false,
            submitting: false,
            error: ""
        };
    }

    function hydrateTheme() {
        if (dom.themeSelect) {
            dom.themeSelect.innerHTML = THEMES.map(theme => `
                <option value="${escapeAttribute(theme.id)}">${escapeHtml(theme.label)}</option>
            `).join("");
        }
        applyTheme(document.documentElement.dataset.theme || loadStoredTheme(), false);
    }

    function loadStoredTheme() {
        try {
            return localStorage.getItem(THEME_STORAGE_KEY) || DEFAULT_THEME_ID;
        } catch (error) {
            return DEFAULT_THEME_ID;
        }
    }

    function applyTheme(themeId, persist) {
        const resolvedTheme = resolveTheme(themeId);
        state.themeId = resolvedTheme;
        document.documentElement.dataset.theme = resolvedTheme;
        if (dom.themeSelect && dom.themeSelect.value !== resolvedTheme) {
            dom.themeSelect.value = resolvedTheme;
        }
        if (persist) {
            try {
                localStorage.setItem(THEME_STORAGE_KEY, resolvedTheme);
            } catch (error) {
                // Ignore storage failures and keep the theme applied for this session.
            }
        }
    }

    function resolveTheme(themeId) {
        return THEMES.some(theme => theme.id === themeId) ? themeId : DEFAULT_THEME_ID;
    }

    function bindEvents() {
        dom.marketRows.addEventListener("click", onMarketTableClick);
        dom.marketRows.addEventListener("input", onMarketDraftInput);
        dom.marketRows.addEventListener("keydown", onMarketDraftKeyDown);
        dom.orderForm.addEventListener("submit", onOrderSubmit);
        dom.orderRows.addEventListener("click", onOrderTableClick);
        dom.clearOrderEditButton.addEventListener("click", clearOrderEdit);
        dom.instrumentDialog.addEventListener("click", onInstrumentDialogClick);
        dom.closeInstrumentDialogButton.addEventListener("click", closeInstrumentDialog);
        dom.cancelInstrumentButton.addEventListener("click", closeInstrumentDialog);
        dom.confirmInstrumentButton.addEventListener("click", confirmInstrumentSelection);
        dom.instrumentDialogRows.addEventListener("click", onInstrumentPickerRowClick);
        dom.instrumentDialogRows.addEventListener("dblclick", onInstrumentPickerRowDoubleClick);
        dom.openCatalogBtn.addEventListener("click", openCatalogDialog);
        dom.closeCatalogDialogButton.addEventListener("click", closeCatalogDialog);
        dom.catalogCloseButton.addEventListener("click", closeCatalogDialog);
        dom.catalogDialog.addEventListener("click", onCatalogDialogClick);
        dom.catalogDialogRows.addEventListener("click", onCatalogRowClick);
        dom.backFromDrilldownBtn.addEventListener("click", closeCatalogDrilldown);
        dom.openLatencyBtn.addEventListener("click", openLatencyDialog);
        dom.closeLatencyDialogButton.addEventListener("click", closeLatencyDialog);
        dom.latencyCloseButton.addEventListener("click", closeLatencyDialog);
        dom.latencyDialog.addEventListener("click", onLatencyDialogClick);
        dom.openProfilesBtn.addEventListener("click", openProfilesDialog);
        dom.closeProfilesDialogButton.addEventListener("click", closeProfilesDialog);
        dom.brokerProfilesDialog.addEventListener("click", onBrokerProfilesDialogClick);
        dom.brokerProfileRows.addEventListener("click", onBrokerProfileRowClick);
        dom.brokerProfileForm.addEventListener("submit", onBrokerProfileSubmit);
        dom.deleteProfileButton.addEventListener("click", onDeleteProfile);
        document.addEventListener("keydown", onDocumentKeyDown);
        if (dom.themeSelect) {
            dom.themeSelect.addEventListener("change", () => applyTheme(dom.themeSelect.value, true));
        }

        dom.sideButtons.forEach(button => {
            button.addEventListener("click", () => {
                state.selectedSide = button.dataset.side || "BUY";
                syncSideButtons();
                if (dom.orderTypeSelect.value === "LIMIT") {
                    applySuggestedLimitPrice(true);
                }
                updateTicketSummary();
            });
        });

        dom.orderTypeSelect.addEventListener("change", () => {
            if (dom.orderTypeSelect.value === "MARKET" && dom.timeInForceSelect.value === "POST_ONLY") {
                dom.timeInForceSelect.value = "GTC";
            }
            toggleLimitField();
            updateTicketSummary();
        });

        dom.timeInForceSelect.addEventListener("change", updateTicketSummary);
        dom.quantityInput.addEventListener("input", updateTicketSummary);
        dom.limitPriceInput.addEventListener("input", updateTicketSummary);
    }

    async function loadBootstrap() {
        try {
            const [metadata, snapshot, brokerProfiles] = await Promise.all([
                api("/api/metadata"),
                api("/api/snapshot"),
                api("/api/admin/broker/profiles")
            ]);
            state.metadata = {...defaultMetadata(), ...(metadata || {})};
            applyBrokerProfiles(brokerProfiles);
            hydrateMetadata();
            applySnapshot(snapshot);
        } catch (error) {
            showToast(error.message || "Unable to initialize the workstation.");
        }
    }

    function applyBrokerProfiles(payload) {
        state.brokerProfiles = {
            definitions: Array.isArray(payload?.definitions) ? payload.definitions : [],
            profiles: Array.isArray(payload?.profiles) ? payload.profiles : []
        };
        if (!state.selectedProfileExchange && state.brokerProfiles.definitions.length) {
            state.selectedProfileExchange = state.brokerProfiles.definitions[0].exchange;
        }
    }

    function hydrateMetadata() {
        populateSelect(dom.orderTypeSelect, state.metadata.orderTypes || [], dom.orderTypeSelect.value || "LIMIT");
        populateSelect(dom.timeInForceSelect, state.metadata.timeInForce || [], dom.timeInForceSelect.value || "GTC");
        dom.orderModePill.textContent = `${formatEnumLabel(state.metadata.orderMode || "PAPER")} Gateway`;
        dom.ticketMode.textContent = formatEnumLabel(state.metadata.orderMode || "PAPER");
        const exchangeCount = (state.metadata.exchanges || []).length;
        if (dom.coverageCount) {
            dom.coverageCount.textContent = `${exchangeCount} Exchange${exchangeCount !== 1 ? "s" : ""}`;
        }
        syncSideButtons();
        toggleLimitField();
    }

    function populateSelect(select, options, preferredValue) {
        const normalizedOptions = Array.isArray(options) && options.length ? options : [];
        select.innerHTML = normalizedOptions.map(option => `
            <option value="${escapeAttribute(option.value)}">${escapeHtml(option.label || option.value)}</option>
        `).join("");
        const fallbackValue = normalizedOptions[0] ? normalizedOptions[0].value : "";
        select.value = normalizedOptions.some(option => option.value === preferredValue) ? preferredValue : fallbackValue;
    }

    function onMarketDraftInput(event) {
        const input = event.target.closest(".watchlist-symbol-input");
        if (!input) {
            return;
        }
        state.marketDrafts.set(parseInteger(input.dataset.rowIndex), input.value);
    }

    function onMarketDraftKeyDown(event) {
        const input = event.target.closest(".watchlist-symbol-input");
        if (!input || event.key !== "Enter") {
            return;
        }

        event.preventDefault();
        const query = input.value.trim();
        const rowIndex = parseInteger(input.dataset.rowIndex);
        if (!query) {
            showToast("Type a ticker before pressing Return.");
            return;
        }
        openInstrumentDialog(rowIndex, query);
    }

    async function onOrderSubmit(event) {
        event.preventDefault();
        const market = getSelectedMarket();
        if (!market && !state.editingOrderId) {
            showToast("Select a market before transmitting an order.");
            return;
        }

        const payload = {
            marketId: market?.id,
            side: state.selectedSide,
            orderType: dom.orderTypeSelect.value,
            timeInForce: dom.timeInForceSelect.value,
            clientOrderId: dom.clientOrderIdInput.value.trim() || null,
            quantity: dom.quantityInput.value,
            limitPrice: dom.orderTypeSelect.value === "LIMIT" ? dom.limitPriceInput.value : null
        };

        try {
            const editingOrderId = state.editingOrderId;
            const created = editingOrderId
                ? await api(`/api/orders/${encodeURIComponent(editingOrderId)}`, {
                    method: "POST",
                    body: JSON.stringify(payload)
                })
                : await api("/api/orders", {
                    method: "POST",
                    body: JSON.stringify(payload)
                });
            state.orders = mergeById(state.orders, created);
            clearOrderEdit();
            dom.quantityInput.value = "";
            dom.clientOrderIdInput.value = "";
            if (dom.orderTypeSelect.value === "LIMIT") {
                applySuggestedLimitPrice(true);
            }
            updateTicketSummary();
            renderSummary();
            renderOrders();
            showToast(editingOrderId
                ? `Modify requested for ${created.symbol}.`
                : `Live order submitted: ${formatEnumLabel(created.side)} ${created.symbol} on ${formatEnumLabel(created.exchange)}.`);
        } catch (error) {
            showToast(error.message);
        }
    }

    async function onMarketTableClick(event) {
        if (event.target.closest(".watchlist-symbol-input")) {
            return;
        }

        const actionButton = event.target.closest("button[data-action]");
        if (actionButton) {
            const marketId = actionButton.dataset.marketId;
            const action = actionButton.dataset.action;

            if (action === "remove") {
                const removedMarket = state.markets.find(market => market.id === marketId);
                try {
                    await api(`/api/markets/${marketId}`, {method: "DELETE"});
                    state.markets = state.markets.filter(market => market.id !== marketId);
                    if (removedMarket) {
                        state.marketDrafts.delete(normalizeRowIndex(removedMarket.rowIndex));
                    }
                    syncSelectedMarket(true);
                    renderAll();
                } catch (error) {
                    showToast(error.message);
                }
                return;
            }

            if (action === "buy" || action === "sell") {
                selectMarket(marketId, action.toUpperCase(), true);
                dom.quantityInput.focus();
                return;
            }
        }

        const row = event.target.closest("tr[data-market-id]");
        if (row) {
            selectMarket(row.dataset.marketId, state.selectedSide, true);
            return;
        }

        const draftRow = event.target.closest("tr[data-row-index]");
        if (draftRow) {
            const input = draftRow.querySelector(".watchlist-symbol-input");
            input?.focus();
            input?.select();
        }
    }

    async function onOrderTableClick(event) {
        const button = event.target.closest("button[data-order-id]");
        if (!button) {
            return;
        }

        const order = state.orders.find(candidate => candidate.id === button.dataset.orderId);
        const action = button.dataset.action || "cancel";

        if (action === "modify") {
            if (!order) {
                showToast("Unable to load order details for modification.");
                return;
            }
            loadOrderIntoTicket(order);
            return;
        }

        try {
            await api(`/api/orders/${button.dataset.orderId}`, {method: "DELETE"});
            const now = new Date().toISOString();
            state.orders = state.orders.map(existing => existing.id === button.dataset.orderId
                ? {...existing, status: "PENDING_CANCEL", updatedAt: now}
                : existing);
            renderSummary();
            renderOrders();
            showToast("Cancel requested.");
        } catch (error) {
            showToast(error.message);
        }
    }

    function connectWebSocket() {
        clearTimeout(state.reconnectTimer);
        setConnection(false);

        const protocol = window.location.protocol === "https:" ? "wss" : "ws";
        state.ws = new WebSocket(`${protocol}://${window.location.host}/ws/switchboard`);

        state.ws.onopen = () => setConnection(true);
        state.ws.onmessage = event => {
            try {
                const payload = JSON.parse(event.data);
                applySnapshot(payload);
            } catch (error) {
                showToast("Unable to parse workstation update.");
            }
        };
        state.ws.onclose = () => {
            setConnection(false);
            state.reconnectTimer = setTimeout(connectWebSocket, 1500);
        };
        state.ws.onerror = () => {
            if (state.ws) {
                state.ws.close();
            }
        };
    }

    function applySnapshot(payload) {
        const nextMarkets = Array.isArray(payload?.markets) ? payload.markets : [];
        updateTickDirections(nextMarkets);
        state.markets = nextMarkets;
        if (Array.isArray(payload?.orders)) {
            state.orders = payload.orders;
        }
        if (Array.isArray(payload?.quoteLatencies)) {
            state.quoteLatencies = payload.quoteLatencies;
        }
        if (Array.isArray(payload?.brokerConnections)) {
            state.brokerConnections = payload.brokerConnections;
        }
        if (Array.isArray(payload?.balances)) {
            state.balances = payload.balances;
        }
        if (Array.isArray(payload?.positions)) {
            state.positions = payload.positions;
        }
        if (Array.isArray(payload?.fills)) {
            state.fills = payload.fills;
        }
        state.serverTime = payload?.serverTime || state.serverTime;
        syncSelectedMarket();
        renderLiveState();
    }

    function syncSelectedMarket(forceLimitRefresh) {
        const previousSelection = state.selectedMarketId;
        const selectedStillExists = state.markets.some(market => market.id === state.selectedMarketId);
        if (!selectedStillExists) {
            state.selectedMarketId = state.markets[0]?.id || null;
        }
        if (state.selectedMarketId && (forceLimitRefresh || previousSelection !== state.selectedMarketId)) {
            applySuggestedLimitPrice(true);
        }
    }

    function selectMarket(marketId, side, refreshLimitPrice) {
        if (!marketId) {
            return;
        }
        state.selectedMarketId = marketId;
        if (side) {
            state.selectedSide = side;
        }
        syncSideButtons();
        if (refreshLimitPrice) {
            applySuggestedLimitPrice(true);
        }
        renderAll();
    }

    function getSelectedMarket() {
        return state.markets.find(market => market.id === state.selectedMarketId) || null;
    }

    function renderAll() {
        renderLiveState();
        renderInstrumentDialog();
        renderBrokerProfilesDialog();
    }

    function renderLiveState() {
        renderSummary();
        renderMarkets();
        renderTicket();
        renderOrders();
        renderBrokerConnections();
        renderBalances();
        renderPositions();
        renderFills();
        if (!dom.latencyDialog.classList.contains("hidden")) {
            renderLatencyDialog();
        }
    }

    function renderSummary() {
        dom.serverClock.textContent = formatClock(state.serverTime);
    }

    function renderMarkets() {
        const slotMap = buildMarketSlotMap();
        const visibleRows = visibleWatchlistRowCount(slotMap);
        const layoutSignature = buildMarketLayoutSignature(slotMap, visibleRows);

        if (state.marketLayoutSignature !== layoutSignature) {
            const focusState = captureDraftFocus();
            dom.marketRows.innerHTML = Array.from({length: visibleRows}, (_, rowIndex) => {
                const market = slotMap.get(rowIndex);
                return market ? renderActiveMarketRow(market, rowIndex) : renderDraftRow(rowIndex);
            }).join("");
            state.marketLayoutSignature = layoutSignature;
            restoreDraftFocus(focusState);
        }

        updateRenderedMarketRows(slotMap);
    }

    function buildMarketSlotMap() {
        const slots = new Map();
        const unassigned = [];
        const maxRows = configuredMaxRows();

        state.markets.forEach(market => {
            const rowIndex = normalizeRowIndex(market.rowIndex);
            if (Number.isInteger(rowIndex) && rowIndex >= 0 && rowIndex < maxRows && !slots.has(rowIndex)) {
                slots.set(rowIndex, market);
            } else {
                unassigned.push(market);
            }
        });

        let cursor = 0;
        unassigned.forEach(market => {
            while (slots.has(cursor) && cursor < maxRows) {
                cursor += 1;
            }
            if (cursor < maxRows) {
                slots.set(cursor, market);
                cursor += 1;
            }
        });

        return slots;
    }

    function visibleWatchlistRowCount(slotMap) {
        const highestUsedRow = slotMap.size ? Math.max(...slotMap.keys()) : -1;
        return Math.min(configuredMaxRows(), Math.max(MIN_WATCHLIST_ROWS, highestUsedRow + 2));
    }

    function buildMarketLayoutSignature(slotMap, visibleRows) {
        const signature = [String(visibleRows)];
        for (let rowIndex = 0; rowIndex < visibleRows; rowIndex += 1) {
            const market = slotMap.get(rowIndex);
            signature.push(`${rowIndex}:${market ? market.id : "draft"}`);
        }
        return signature.join("|");
    }

    function updateRenderedMarketRows(slotMap) {
        const rows = Array.from(dom.marketRows.querySelectorAll("tr[data-row-index]"));
        rows.forEach(row => {
            const rowIndex = parseInteger(row.dataset.rowIndex);
            const market = slotMap.get(rowIndex);
            if (!market) {
                return;
            }

            row.classList.toggle("selected", market.id === state.selectedMarketId);
            row.dataset.marketId = market.id;

            setRowFieldText(row, "symbolPrimary", market.symbol || "--");
            setRowFieldText(row, "asset", formatEnumLabel(market.assetType));
            setRowFieldText(row, "route", formatEnumLabel(market.exchange));
            setRowFieldHtml(row, "bid", renderQuoteValue(market.id, "bid", market.bid));
            setRowFieldHtml(row, "ask", renderQuoteValue(market.id, "ask", market.ask));
            setRowFieldText(row, "spread", formatNumber(computeSpread(market.bid, market.ask), 1));
            setRowFieldHtml(row, "last", renderQuoteValue(market.id, "last", market.last));
            setRowFieldText(row, "volume", formatCompactNumber(market.volume));
            setRowFieldText(row, "funding", formatNumber(market.fundingRateApr, 2));
            const fundingCell = row.querySelector('[data-market-field="funding"]');
            if (fundingCell) {
                fundingCell.classList.toggle("positive-cell", toNumber(market.fundingRateApr) > 0);
                fundingCell.classList.toggle("negative-cell", toNumber(market.fundingRateApr) < 0);
            }
        });
    }

    function fundingClass(value) {
        const n = toNumber(value);
        return n > 0 ? "positive-cell" : n < 0 ? "negative-cell" : "";
    }

    function setRowFieldText(row, field, value) {
        const element = row.querySelector(`[data-market-field="${field}"]`);
        if (element && element.textContent !== value) {
            element.textContent = value;
        }
    }

    function setRowFieldHtml(row, field, value) {
        const element = row.querySelector(`[data-market-field="${field}"]`);
        if (element && element.innerHTML !== value) {
            element.innerHTML = value;
        }
    }

    function renderActiveMarketRow(market, rowIndex) {
        const spread = computeSpread(market.bid, market.ask);
        const isSelected = market.id === state.selectedMarketId;
        return `
            <tr data-market-id="${escapeAttribute(market.id)}" data-row-index="${rowIndex}" class="${isSelected ? "selected" : ""}">
                <td>
                    <div class="symbol-cell">
                        <strong data-market-field="symbolPrimary">${escapeHtml(market.symbol || "--")}</strong>
                    </div>
                </td>
                <td data-market-field="asset">${escapeHtml(formatEnumLabel(market.assetType))}</td>
                <td data-market-field="route">${escapeHtml(formatEnumLabel(market.exchange))}</td>
                <td class="number-cell" data-market-field="bid">${renderQuoteValue(market.id, "bid", market.bid)}</td>
                <td class="number-cell" data-market-field="ask">${renderQuoteValue(market.id, "ask", market.ask)}</td>
                <td class="number-cell" data-market-field="spread">${formatNumber(spread, 1)}</td>
                <td class="number-cell" data-market-field="last">${renderQuoteValue(market.id, "last", market.last)}</td>
                <td class="number-cell" data-market-field="volume">${formatCompactNumber(market.volume)}</td>
                <td class="number-cell ${fundingClass(market.fundingRateApr)}" data-market-field="funding">${formatNumber(market.fundingRateApr, 2)}</td>
                <td>
                    <div class="action-cluster compact-actions">
                        <button type="button" class="ticket-btn buy" data-action="buy" data-market-id="${escapeAttribute(market.id)}">Buy</button>
                        <button type="button" class="ticket-btn sell" data-action="sell" data-market-id="${escapeAttribute(market.id)}">Sell</button>
                        <button type="button" class="icon-btn remove-btn" data-action="remove" data-market-id="${escapeAttribute(market.id)}" aria-label="Remove watchlist row">×</button>
                    </div>
                </td>
            </tr>
        `;
    }

    function renderDraftRow(rowIndex) {
        const draftValue = state.marketDrafts.get(rowIndex) || "";
        const prompt = draftValue ? "Press Return" : rowIndex === 0 ? "Type BTC and press Return" : "";
        return `
            <tr data-row-index="${rowIndex}" class="draft-row">
                <td class="draft-input-cell">
                    <input
                        type="text"
                        class="watchlist-symbol-input"
                        data-row-index="${rowIndex}"
                        value="${escapeAttribute(draftValue)}"
                        placeholder="${escapeAttribute(prompt)}"
                        autocomplete="off"
                        autocapitalize="characters"
                        spellcheck="false">
                </td>
                <td class="placeholder-cell">--</td>
                <td class="placeholder-cell">--</td>
                <td class="placeholder-cell">--</td>
                <td class="placeholder-cell">--</td>
                <td class="placeholder-cell">--</td>
                <td class="placeholder-cell">--</td>
                <td class="placeholder-cell">--</td>
                <td class="placeholder-cell">--</td>
                <td class="placeholder-cell row-hint-cell">${draftValue ? "Return to resolve" : ""}</td>
            </tr>
        `;
    }

    function captureDraftFocus() {
        const active = document.activeElement;
        if (!active || !active.classList.contains("watchlist-symbol-input")) {
            return null;
        }
        const rowIndex = parseInteger(active.dataset.rowIndex);
        return {
            rowIndex,
            start: active.selectionStart,
            end: active.selectionEnd
        };
    }

    function restoreDraftFocus(focusState) {
        if (!focusState || state.instrumentPicker.open) {
            return;
        }
        const input = dom.marketRows.querySelector(`.watchlist-symbol-input[data-row-index="${focusState.rowIndex}"]`);
        if (!input) {
            return;
        }
        input.focus({preventScroll: true});
        const valueLength = input.value.length;
        const selectionStart = Math.min(focusState.start ?? valueLength, valueLength);
        const selectionEnd = Math.min(focusState.end ?? valueLength, valueLength);
        input.setSelectionRange(selectionStart, selectionEnd);
    }

    function renderTicket() {
        const market = getSelectedMarket();
        const hasSelection = Boolean(market);

        dom.ticketEmpty.classList.toggle("hidden", hasSelection);
        dom.ticketShell.classList.toggle("hidden", !hasSelection);
        dom.submitOrderButton.disabled = !hasSelection;

        if (!hasSelection) {
            dom.ticketNote.textContent = "";
            dom.ticketReferencePrice.textContent = "--";
            dom.ticketEstimatedNotional.textContent = "--";
            dom.submitOrderButton.textContent = state.editingOrderId ? "Modify Order" : "Transmit Order";
            return;
        }

        dom.selectedTicker.textContent = market.symbol || "--";
        dom.selectedExchangeSymbol.textContent = market.exchangeSymbol || "--";
        dom.selectedRoute.textContent = formatEnumLabel(market.exchange);
        dom.selectedAsset.textContent = formatEnumLabel(market.assetType);
        dom.selectedBid.textContent = formatPrice(market.bid);
        dom.selectedAsk.textContent = formatPrice(market.ask);
        dom.selectedLast.textContent = formatPrice(market.last);
        dom.ticketNote.textContent = ticketNoteForMarket(market);
        dom.submitOrderButton.textContent = state.editingOrderId ? "Modify Order" : "Transmit Order";
        dom.clearOrderEditButton.classList.toggle("hidden", !state.editingOrderId);

        syncSideButtons();
        toggleLimitField();
        updateTicketSummary();
    }

    function renderOrders() {
        const orders = Array.isArray(state.orders) ? state.orders : [];
        const nextSignature = buildOrderRenderSignature(orders);
        if (state.orderRenderSignature === nextSignature) {
            return;
        }
        state.orderRenderSignature = nextSignature;

        if (!orders.length) {
            dom.orderRows.innerHTML = `
                <tr>
                    <td colspan="12" class="empty-row">
                        <strong>No broker orders yet</strong>
                        <span>Transmit from the ticket after connecting an exchange profile.</span>
                    </td>
                </tr>
            `;
            return;
        }

        dom.orderRows.innerHTML = orders.map(order => `
            <tr>
                <td>${formatTime(order.updatedAt || order.createdAt)}</td>
                <td>
                    <div class="symbol-cell">
                        <strong>${escapeHtml(order.symbol || "--")}</strong>
                        <span>${escapeHtml(order.exchangeSymbol || "--")}</span>
                    </div>
                </td>
                <td>${escapeHtml(order.exchangeLabel || formatEnumLabel(order.exchange))}</td>
                <td class="number-cell">${escapeHtml(order.clientOrderId || "--")}</td>
                <td><span class="side-pill ${order.side === "BUY" ? "buy" : "sell"}">${escapeHtml(formatEnumLabel(order.side))}</span></td>
                <td>${escapeHtml(formatEnumLabel(order.orderType))}</td>
                <td>${escapeHtml(formatEnumLabel(order.timeInForce))}</td>
                <td class="number-cell">${formatNumber(order.quantity, 6)}</td>
                <td class="number-cell">${formatNumber(order.filledQuantity, 6)}</td>
                <td class="number-cell">${formatPrice(order.limitPrice)}</td>
                <td><span class="status-pill">${escapeHtml(formatEnumLabel(order.status))}</span></td>
                <td>
                    <div class="action-cluster compact-actions">
                        <button type="button" class="secondary-btn compact-btn order-action-btn" data-action="modify" data-order-id="${escapeAttribute(order.id)}" ${!canModifyOrder(order) ? "disabled" : ""}>Modify</button>
                        <button type="button" class="secondary-btn compact-btn order-action-btn" data-action="cancel" data-order-id="${escapeAttribute(order.id)}" ${!canCancelOrder(order) ? "disabled" : ""}>Cancel</button>
                    </div>
                </td>
            </tr>
        `).join("");
    }

    function buildOrderRenderSignature(orders) {
        if (!Array.isArray(orders) || !orders.length) {
            return "empty";
        }

        return orders.map(order => ([
            order?.id,
            order?.updatedAt,
            order?.createdAt,
            order?.symbol,
            order?.exchangeSymbol,
            order?.exchangeLabel,
            order?.clientOrderId,
            order?.side,
            order?.orderType,
            order?.timeInForce,
            order?.quantity,
            order?.filledQuantity,
            order?.limitPrice,
            order?.status,
            canModifyOrder(order) ? "1" : "0",
            canCancelOrder(order) ? "1" : "0"
        ].map(value => value == null ? "" : String(value)).join("~"))).join("||");
    }

    function renderBrokerConnections() {
        const connections = Array.isArray(state.brokerConnections) ? state.brokerConnections : [];
        if (!connections.length) {
            dom.brokerConnectionRows.innerHTML = `<tr><td colspan="5" class="empty-row"><strong>No broker adapters configured.</strong></td></tr>`;
            return;
        }

        dom.brokerConnectionRows.innerHTML = connections.map(connection => `
            <tr>
                <td>${escapeHtml(connection.exchangeLabel || formatEnumLabel(connection.exchange))}</td>
                <td>${escapeHtml(formatEnumLabel(connection.environment || "--"))}</td>
                <td>${escapeHtml(connection.accountLabel || "--")}</td>
                <td><span class="status-pill">${escapeHtml(formatEnumLabel(connection.status || "UNKNOWN"))}</span></td>
                <td>${escapeHtml(formatAge(connection.updatedAt))}</td>
            </tr>
        `).join("");
    }

    function renderBalances() {
        const balances = Array.isArray(state.balances) ? state.balances : [];
        if (!balances.length) {
            dom.balanceRows.innerHTML = `<tr><td colspan="4" class="empty-row"><strong>No balance updates yet.</strong></td></tr>`;
            return;
        }

        dom.balanceRows.innerHTML = balances.map(balance => `
            <tr>
                <td>${escapeHtml(balance.exchangeLabel || formatEnumLabel(balance.exchange))}</td>
                <td class="number-cell">${formatCurrencyLike(balance.equity)}</td>
                <td class="number-cell">${formatCurrencyLike(balance.availableFunds)}</td>
                <td>${escapeHtml(formatAge(balance.updatedAt))}</td>
            </tr>
        `).join("");
    }

    function renderPositions() {
        const positions = Array.isArray(state.positions) ? state.positions : [];
        if (!positions.length) {
            dom.positionRows.innerHTML = `<tr><td colspan="7" class="empty-row"><strong>No open positions reported.</strong></td></tr>`;
            return;
        }

        dom.positionRows.innerHTML = positions.map(position => `
            <tr>
                <td>${escapeHtml(position.exchangeLabel || formatEnumLabel(position.exchange))}</td>
                <td>
                    <div class="symbol-cell">
                        <strong>${escapeHtml(position.symbol || "--")}</strong>
                        <span>${escapeHtml(position.exchangeSymbol || "--")}</span>
                    </div>
                </td>
                <td>${escapeHtml(formatEnumLabel(position.side))}</td>
                <td class="number-cell">${formatNumber(position.size, 6)}</td>
                <td class="number-cell">${formatPrice(position.averageCost)}</td>
                <td class="number-cell">${formatPrice(position.liquidationPrice)}</td>
                <td>${escapeHtml(formatEnumLabel(position.status))}</td>
            </tr>
        `).join("");
    }

    function renderFills() {
        const fills = Array.isArray(state.fills) ? state.fills : [];
        if (!fills.length) {
            dom.fillRows.innerHTML = `<tr><td colspan="8" class="empty-row"><strong>No fill events yet.</strong></td></tr>`;
            return;
        }

        dom.fillRows.innerHTML = fills.map(fill => `
            <tr>
                <td>${formatTime(fill.time)}</td>
                <td>${escapeHtml(fill.exchangeLabel || formatEnumLabel(fill.exchange))}</td>
                <td>
                    <div class="symbol-cell">
                        <strong>${escapeHtml(fill.symbol || "--")}</strong>
                        <span>${escapeHtml(fill.exchangeSymbol || "--")}</span>
                    </div>
                </td>
                <td><span class="side-pill ${fill.side === "BUY" ? "buy" : "sell"}">${escapeHtml(formatEnumLabel(fill.side))}</span></td>
                <td class="number-cell">${formatNumber(fill.size, 6)}</td>
                <td class="number-cell">${formatPrice(fill.price)}</td>
                <td class="number-cell">${escapeHtml(fill.clientOrderId || "--")}</td>
                <td>${fill.snapshot ? "Snapshot" : fill.taker ? "Taker" : "Maker"}</td>
            </tr>
        `).join("");
    }

    function openLatencyDialog() {
        dom.latencyDialog.classList.remove("hidden");
        dom.latencyDialog.setAttribute("aria-hidden", "false");
        renderLatencyDialog();
        dom.latencyDialogPanel.focus({preventScroll: true});
    }

    function closeLatencyDialog() {
        dom.latencyDialog.classList.add("hidden");
        dom.latencyDialog.setAttribute("aria-hidden", "true");
    }

    function onLatencyDialogClick(event) {
        if (event.target.dataset.action === "close-latency") {
            closeLatencyDialog();
        }
    }

    function renderLatencyDialog() {
        const latencies = Array.isArray(state.quoteLatencies) ? state.quoteLatencies : [];
        if (!latencies.length) {
            dom.latencyRows.innerHTML = `
                <tr>
                    <td colspan="6" class="empty-row">
                        <strong>No timestamped L1 quotes yet.</strong>
                        <span>Latency stats will appear after exchanges start streaming quotes with event timestamps.</span>
                    </td>
                </tr>
            `;
            return;
        }

        dom.latencyRows.innerHTML = latencies.map(latency => `
            <tr>
                <td class="latency-exchange-cell">${escapeHtml(latency.exchangeLabel || formatEnumLabel(latency.exchange))}</td>
                <td class="number-cell">${escapeHtml(formatLatency(latency.lastLatencyMs))}</td>
                <td class="number-cell">${escapeHtml(formatLatency(latency.p50LatencyMs))}</td>
                <td class="number-cell">${escapeHtml(formatLatency(latency.p95LatencyMs))}</td>
                <td class="number-cell">${escapeHtml(formatSampleCount(latency.sampleCount))}</td>
                <td>${formatTime(latency.lastProcessedAt)}</td>
            </tr>
        `).join("");
    }

    function openProfilesDialog() {
        dom.brokerProfilesDialog.classList.remove("hidden");
        dom.brokerProfilesDialog.setAttribute("aria-hidden", "false");
        renderBrokerProfilesDialog();
        dom.brokerProfilesDialogPanel.focus({preventScroll: true});
    }

    function closeProfilesDialog() {
        dom.brokerProfilesDialog.classList.add("hidden");
        dom.brokerProfilesDialog.setAttribute("aria-hidden", "true");
    }

    function onBrokerProfilesDialogClick(event) {
        if (event.target.dataset.action === "close-profiles") {
            closeProfilesDialog();
        }
    }

    function onBrokerProfileRowClick(event) {
        const row = event.target.closest("tr[data-profile-exchange]");
        if (!row) {
            return;
        }
        state.selectedProfileExchange = row.dataset.profileExchange;
        renderBrokerProfilesDialog();
    }

    function renderBrokerProfilesDialog() {
        const definitions = state.brokerProfiles.definitions || [];
        const profiles = state.brokerProfiles.profiles || [];

        if (!definitions.length) {
            dom.brokerProfileRows.innerHTML = `<tr><td colspan="4" class="empty-row"><strong>No broker definitions available.</strong></td></tr>`;
            return;
        }

        dom.brokerProfileRows.innerHTML = definitions.map(definition => {
            const profile = findBrokerProfile(definition.exchange);
            const selected = state.selectedProfileExchange === definition.exchange;
            return `
                <tr data-profile-exchange="${escapeAttribute(definition.exchange)}" class="${selected ? "selected" : ""}">
                    <td>${escapeHtml(definition.displayName)}</td>
                    <td>${escapeHtml(profile?.environment ? formatEnumLabel(profile.environment) : "--")}</td>
                    <td>${escapeHtml(profile?.accountLabel || "--")}</td>
                    <td>${profile ? "Saved" : "Not Saved"}</td>
                </tr>
            `;
        }).join("");

        const definition = definitions.find(item => item.exchange === state.selectedProfileExchange) || definitions[0];
        if (!definition) {
            return;
        }
        state.selectedProfileExchange = definition.exchange;
        const profile = findBrokerProfile(definition.exchange);

        dom.profileExchangeInput.value = definition.exchange;
        dom.profileFormTitle.textContent = definition.displayName;
        dom.profileFormSubtitle.textContent = profile
            ? `Last updated ${formatTime(profile.updatedAt)}. Blank secret fields keep the stored value.`
            : "Create a live broker profile for this exchange.";
        dom.profileEnvironmentSelect.value = profile?.environment || "MAINNET";
        dom.deleteProfileButton.disabled = !profile;
        dom.profileFieldContainer.innerHTML = (definition.fields || []).map(field => {
            const savedField = profile?.fields?.find(candidate => candidate.key === field.key);
            const value = savedField?.value || "";
            const placeholder = field.secret
                ? (savedField?.configured ? `${savedField.maskedValue || "Stored"} (leave blank to keep)` : "Enter secret")
                : (savedField?.helperText || field.helperText || "");
            return `
                <label>
                    <span>${escapeHtml(field.label)}${field.required ? " *" : ""}</span>
                    <input
                        id="${escapeAttribute(profileFieldInputId(field.key))}"
                        data-profile-field="${escapeAttribute(field.key)}"
                        type="${field.secret ? "password" : "text"}"
                        value="${escapeAttribute(value)}"
                        placeholder="${escapeAttribute(placeholder)}"
                        ${field.required && !field.secret ? "required" : ""}>
                </label>
            `;
        }).join("");
    }

    async function onBrokerProfileSubmit(event) {
        event.preventDefault();
        const exchange = dom.profileExchangeInput.value;
        if (!exchange) {
            showToast("Select an exchange before saving a profile.");
            return;
        }

        const fields = {};
        dom.profileFieldContainer.querySelectorAll("[data-profile-field]").forEach(input => {
            fields[input.dataset.profileField] = input.value;
        });

        try {
            const saved = await api(`/api/admin/broker/profiles/${encodeURIComponent(exchange)}`, {
                method: "PUT",
                body: JSON.stringify({
                    environment: dom.profileEnvironmentSelect.value,
                    fields
                })
            });
            state.brokerProfiles.profiles = mergeProfileByExchange(state.brokerProfiles.profiles, saved);
            renderBrokerProfilesDialog();
            showToast(`Saved ${saved.displayName} broker profile.`);
        } catch (error) {
            showToast(error.message);
        }
    }

    async function onDeleteProfile() {
        const exchange = dom.profileExchangeInput.value;
        if (!exchange) {
            return;
        }

        try {
            await api(`/api/admin/broker/profiles/${encodeURIComponent(exchange)}`, {method: "DELETE"});
            state.brokerProfiles.profiles = state.brokerProfiles.profiles.filter(profile => profile.exchange !== exchange);
            renderBrokerProfilesDialog();
            showToast("Broker profile deleted.");
        } catch (error) {
            showToast(error.message);
        }
    }

    function syncSideButtons() {
        dom.sideButtons.forEach(button => {
            button.classList.toggle("active", button.dataset.side === state.selectedSide);
        });
    }

    function toggleLimitField() {
        const isLimit = dom.orderTypeSelect.value !== "MARKET";
        dom.limitPriceField.classList.toggle("is-disabled", !isLimit);
        dom.limitPriceInput.disabled = !isLimit;
        dom.limitPriceInput.required = isLimit;

        if (!isLimit) {
            dom.limitPriceInput.value = "";
        } else if (!dom.limitPriceInput.value) {
            applySuggestedLimitPrice(false);
        }
    }

    function applySuggestedLimitPrice(force) {
        const market = getSelectedMarket();
        if (!market || dom.orderTypeSelect.value !== "LIMIT") {
            return;
        }
        if (!force && dom.limitPriceInput.value) {
            return;
        }
        const suggested = state.selectedSide === "BUY"
            ? firstNumber(market.ask, market.last, midpoint(market.bid, market.ask))
            : firstNumber(market.bid, market.last, midpoint(market.bid, market.ask));
        dom.limitPriceInput.value = Number.isFinite(suggested) ? formatEditableNumber(suggested) : "";
    }

    function updateTicketSummary() {
        const market = getSelectedMarket();
        if (!market) {
            dom.ticketReferencePrice.textContent = "--";
            dom.ticketEstimatedNotional.textContent = "--";
            return;
        }

        const reference = state.selectedSide === "BUY"
            ? firstNumber(market.ask, market.last, midpoint(market.bid, market.ask))
            : firstNumber(market.bid, market.last, midpoint(market.bid, market.ask));
        const effectivePrice = dom.orderTypeSelect.value === "LIMIT"
            ? toNumber(dom.limitPriceInput.value)
            : reference;
        const quantity = toNumber(dom.quantityInput.value);
        const notional = Number.isFinite(quantity) && Number.isFinite(effectivePrice)
            ? quantity * effectivePrice
            : null;

        dom.ticketReferencePrice.textContent = formatPrice(reference);
        dom.ticketEstimatedNotional.textContent = Number.isFinite(notional)
            ? formatCurrencyLike(notional)
            : "--";
    }

    function updateTickDirections(markets) {
        const now = Date.now();
        markets.forEach(market => {
            ["bid", "ask", "last"].forEach(field => {
                const key = `${market.id}:${field}`;
                const nextValue = toNumber(market[field]);
                const previousValue = state.lastValueByField.get(key);
                if (Number.isFinite(nextValue) && Number.isFinite(previousValue) && nextValue !== previousValue) {
                    state.tickDirectionByField.set(key, {
                        direction: nextValue > previousValue ? "up" : "down",
                        until: now + TICK_FLASH_MS
                    });
                }
                if (Number.isFinite(nextValue)) {
                    state.lastValueByField.set(key, nextValue);
                    const str = String(nextValue);
                    const dot = str.indexOf(".");
                    const decimals = dot >= 0 ? str.length - dot - 1 : 0;
                    const current = state.precisionByMarket.get(market.id) || 0;
                    if (decimals > current) {
                        state.precisionByMarket.set(market.id, decimals);
                    }
                }
            });
        });
    }

    function renderQuoteValue(marketId, field, value) {
        const key = `${marketId}:${field}`;
        const tick = state.tickDirectionByField.get(key);
        const isActive = tick && tick.until > Date.now();
        if (tick && !isActive) {
            state.tickDirectionByField.delete(key);
        }
        const cssClass = isActive ? `quote-value ${tick.direction}` : "quote-value";
        const precision = state.precisionByMarket.get(marketId) || 0;
        return `<span class="${cssClass}">${escapeHtml(formatPrice(value, precision))}</span>`;
    }

    function setConnection(connected) {
        dom.connectionBadge.classList.toggle("connected", connected);
        dom.connectionBadge.classList.toggle("disconnected", !connected);
        dom.connectionBadge.textContent = connected ? "Connected" : "Disconnected";
    }

    async function openInstrumentDialog(rowIndex, query) {
        state.instrumentPicker = {
            open: true,
            rowIndex,
            query,
            candidates: [],
            selectedKeys: new Set(),
            focusedIndex: null,
            anchorIndex: null,
            loading: true,
            submitting: false,
            error: ""
        };
        renderInstrumentDialog();

        try {
            const candidates = await api(`/api/instruments?symbol=${encodeURIComponent(query)}`);
            state.instrumentPicker.candidates = Array.isArray(candidates) ? candidates : [];
            if (!state.instrumentPicker.candidates.length) {
                state.instrumentPicker.error = `No supported routes found for ${query.toUpperCase()}.`;
            }
        } catch (error) {
            state.instrumentPicker.error = error.message || "Unable to resolve instruments.";
        } finally {
            state.instrumentPicker.loading = false;
            renderInstrumentDialog();
            dom.instrumentDialogPanel.focus({preventScroll: true});
        }
    }

    function closeInstrumentDialog() {
        const rowIndex = state.instrumentPicker.rowIndex;
        state.instrumentPicker = defaultInstrumentPicker();
        renderInstrumentDialog();
        focusDraftRow(rowIndex);
    }

    async function openCatalogDialog() {
        dom.catalogDialog.classList.remove("hidden");
        dom.catalogDialog.setAttribute("aria-hidden", "false");
        dom.catalogDrilldownHead.classList.add("hidden");

        if (state.catalogData) {
            renderCatalogPivot();
        } else {
            setCatalogLoadingState(1);
            try {
                state.catalogData = await api("/api/catalog");
                renderCatalogPivot();
            } catch (error) {
                setCatalogError(error.message);
            }
        }

        dom.catalogDialogPanel.focus({preventScroll: true});
    }

    function closeCatalogDialog() {
        dom.catalogDialog.classList.add("hidden");
        dom.catalogDialog.setAttribute("aria-hidden", "true");
        dom.catalogDrilldownHead.classList.add("hidden");
        state.catalogData = null;
        dom.catalogDialogRows.innerHTML = "";
    }

    function onCatalogDialogClick(event) {
        if (event.target.dataset.action === "close-catalog") {
            closeCatalogDialog();
        }
    }

    function onCatalogRowClick(event) {
        const cell = event.target.closest("td[data-action]");
        if (!cell) {
            return;
        }
        const exchangeName = cell.dataset.exchange;
        const action = cell.dataset.action;

        if (action === "catalog-drill-exchange") {
            const entry = (state.catalogData || []).find(e => e.name === exchangeName);
            openCatalogDrilldown(exchangeName, null, entry?.displayName, "All Instruments");
        } else if (action === "catalog-drill-type") {
            const entry = (state.catalogData || []).find(e => e.name === exchangeName);
            const assetTypeName = cell.dataset.assetType;
            const at = entry?.assetTypes?.find(a => a.name === assetTypeName);
            openCatalogDrilldown(exchangeName, assetTypeName, entry?.displayName, at?.label);
        }
    }

    async function openCatalogDrilldown(exchangeName, assetTypeName, exchangeDisplayName, assetTypeLabel) {
        const title = assetTypeName
            ? `${exchangeDisplayName} — ${assetTypeLabel}`
            : exchangeDisplayName;
        dom.catalogDrilldownHead.classList.remove("hidden");
        dom.catalogDrilldownTitle.textContent = title || "";
        setCatalogLoadingState(2);

        try {
            const url = assetTypeName
                ? `/api/catalog/${encodeURIComponent(exchangeName)}/instruments?assetType=${encodeURIComponent(assetTypeName)}`
                : `/api/catalog/${encodeURIComponent(exchangeName)}/instruments`;
            const instruments = await api(url);
            renderDrilldownView(instruments);
        } catch (error) {
            setCatalogError(error.message);
        }
    }

    function closeCatalogDrilldown() {
        dom.catalogDrilldownHead.classList.add("hidden");
        renderCatalogPivot();
    }

    function renderCatalogPivot() {
        const catalog = state.catalogData;
        if (!Array.isArray(catalog) || !catalog.length) {
            dom.catalogTableHead.innerHTML = `<tr><th>Exchange</th></tr>`;
            dom.catalogDialogRows.innerHTML = `<tr><td class="empty-row"><strong>No exchanges found.</strong></td></tr>`;
            return;
        }

        const assetTypeMap = new Map();
        for (const exchange of catalog) {
            for (const at of (exchange.assetTypes || [])) {
                if (!assetTypeMap.has(at.name)) {
                    assetTypeMap.set(at.name, at.label);
                }
            }
        }
        const assetTypes = Array.from(assetTypeMap.entries());

        dom.catalogTableHead.innerHTML = `<tr>
            <th>Exchange</th>
            ${assetTypes.map(([, label]) => `<th class="number-cell">${escapeHtml(label)}</th>`).join("")}
        </tr>`;

        let html = "";
        for (const exchange of catalog) {
            const countByType = new Map((exchange.assetTypes || []).map(at => [at.name, at.tickerCount]));
            html += `<tr>`;
            html += `<td class="catalog-exchange-cell" data-action="catalog-drill-exchange" data-exchange="${escapeAttribute(exchange.name)}">${escapeHtml(exchange.displayName)}</td>`;
            for (const [atName] of assetTypes) {
                const count = countByType.get(atName);
                if (count != null) {
                    html += `<td class="number-cell catalog-count-cell" data-action="catalog-drill-type" data-exchange="${escapeAttribute(exchange.name)}" data-asset-type="${escapeAttribute(atName)}">${count}</td>`;
                } else {
                    html += `<td class="number-cell placeholder-cell">—</td>`;
                }
            }
            html += `</tr>`;
        }
        dom.catalogDialogRows.innerHTML = html;
    }

    function renderDrilldownView(instruments) {
        dom.catalogTableHead.innerHTML = `<tr><th>Symbol</th><th>Asset Type</th></tr>`;
        if (!Array.isArray(instruments) || !instruments.length) {
            dom.catalogDialogRows.innerHTML = `<tr><td class="empty-row" colspan="2"><strong>No instruments found.</strong></td></tr>`;
            return;
        }
        dom.catalogDialogRows.innerHTML = instruments
            .map(inst => `<tr>
                <td class="number-cell">${escapeHtml(inst.symbol)}</td>
                <td>${escapeHtml(inst.assetTypeLabel)}</td>
            </tr>`)
            .join("");
    }

    function setCatalogLoadingState(colSpan) {
        dom.catalogDialogRows.innerHTML = `<tr><td class="empty-row" colspan="${colSpan}"><strong>Loading…</strong></td></tr>`;
    }

    function setCatalogError(message) {
        const cols = dom.catalogTableHead.querySelectorAll("th").length || 1;
        dom.catalogDialogRows.innerHTML = `<tr><td class="empty-row" colspan="${cols}"><strong>Error</strong><span>${escapeHtml(message || "Unable to load data.")}</span></td></tr>`;
    }

    function onInstrumentPickerRowClick(event) {
        const row = event.target.closest("tr[data-candidate-index]");
        if (!row) {
            return;
        }
        updateInstrumentSelection(parseInteger(row.dataset.candidateIndex), event);
        renderInstrumentDialog();
    }

    function onInstrumentDialogClick(event) {
        if (event.target.dataset.action === "close-picker") {
            closeInstrumentDialog();
        }
    }

    function onInstrumentPickerRowDoubleClick(event) {
        const row = event.target.closest("tr[data-candidate-index]");
        if (!row) {
            return;
        }
        const candidate = state.instrumentPicker.candidates[parseInteger(row.dataset.candidateIndex)];
        if (!candidate) {
            return;
        }
        state.instrumentPicker.selectedKeys = new Set([candidateKey(candidate)]);
        state.instrumentPicker.focusedIndex = parseInteger(row.dataset.candidateIndex);
        state.instrumentPicker.anchorIndex = state.instrumentPicker.focusedIndex;
        renderInstrumentDialog();
        confirmInstrumentSelection();
    }

    async function confirmInstrumentSelection() {
        const picker = state.instrumentPicker;
        const selectedCandidates = picker.candidates.filter(candidate => picker.selectedKeys.has(candidateKey(candidate)));
        if (!picker.open || !selectedCandidates.length) {
            showToast("Select at least one route before adding the row.");
            return;
        }

        const targetRows = planTargetRows(selectedCandidates.length, picker.rowIndex);
        if (targetRows.length < selectedCandidates.length) {
            showToast(`Not enough open watchlist rows for ${selectedCandidates.length} instruments.`);
            return;
        }

        state.instrumentPicker.submitting = true;
        renderInstrumentDialog();

        const createdMarkets = [];
        try {
            for (let index = 0; index < selectedCandidates.length; index += 1) {
                const candidate = selectedCandidates[index];
                const created = await api("/api/markets", {
                    method: "POST",
                    body: JSON.stringify({
                        symbol: candidate.symbol,
                        exchange: candidate.exchange,
                        assetType: candidate.assetType,
                        rowIndex: targetRows[index]
                    })
                });
                createdMarkets.push(created);
                state.markets = mergeById(state.markets, created);
                state.marketDrafts.delete(targetRows[index]);
            }

            state.instrumentPicker = defaultInstrumentPicker();
            if (createdMarkets.length) {
                selectMarket(createdMarkets[0].id, state.selectedSide, true);
            }
            renderAll();
            showToast(createdMarkets.length === 1
                ? `Added ${createdMarkets[0].symbol} on ${formatEnumLabel(createdMarkets[0].exchange)}.`
                : `Added ${createdMarkets.length} instruments to the watchlist.`);
        } catch (error) {
            if (createdMarkets.length) {
                state.instrumentPicker = defaultInstrumentPicker();
                renderAll();
                showToast(`Added ${createdMarkets.length} instruments before error: ${error.message || "Unable to add the remaining rows."}`);
                return;
            }
            state.instrumentPicker.submitting = false;
            state.instrumentPicker.error = error.message || "Unable to add the watchlist row.";
            renderInstrumentDialog();
            showToast(error.message || "Unable to add the watchlist row.");
        }
    }

    function renderInstrumentDialog() {
        const picker = state.instrumentPicker;
        dom.instrumentDialog.classList.toggle("hidden", !picker.open);
        dom.instrumentDialog.setAttribute("aria-hidden", String(!picker.open));

        if (!picker.open) {
            dom.instrumentDialogRows.innerHTML = "";
            dom.instrumentDialogTitle.textContent = "Resolve Symbol";
            dom.instrumentDialogSubtitle.textContent = "Choose the route and asset class for this watchlist row.";
            dom.confirmInstrumentButton.disabled = true;
            dom.confirmInstrumentButton.textContent = "Add Instrument";
            return;
        }

        dom.instrumentDialogTitle.textContent = `Resolve ${String(picker.query || "").toUpperCase()}`;
        dom.instrumentDialogSubtitle.textContent = `Watchlist row ${picker.rowIndex + 1}: choose the exchange and asset class to subscribe.`;
        const selectedCount = picker.selectedKeys.size;
        dom.confirmInstrumentButton.disabled = picker.loading || picker.submitting || selectedCount === 0;
        dom.confirmInstrumentButton.textContent = picker.submitting
            ? "Adding..."
            : selectedCount > 1
                ? `Add ${selectedCount} Instruments`
                : "Add Instrument";

        if (picker.loading) {
            dom.instrumentDialogRows.innerHTML = `
                <tr>
                    <td colspan="5" class="empty-row">
                        <strong>Searching routes</strong>
                        <span>Checking the supported registries for matches.</span>
                    </td>
                </tr>
            `;
            return;
        }

        if (picker.error) {
            dom.instrumentDialogRows.innerHTML = `
                <tr>
                    <td colspan="5" class="empty-row">
                        <strong>No route selected</strong>
                        <span>${escapeHtml(picker.error)}</span>
                    </td>
                </tr>
            `;
            return;
        }

        dom.instrumentDialogRows.innerHTML = picker.candidates.map((candidate, index) => `
            <tr
                data-candidate-index="${index}"
                tabindex="${picker.focusedIndex === index ? "0" : "-1"}"
                aria-selected="${picker.selectedKeys.has(candidateKey(candidate)) ? "true" : "false"}"
                class="${picker.selectedKeys.has(candidateKey(candidate)) ? "selected" : ""}${picker.focusedIndex === index ? " focused" : ""}">
                <td>
                    <div class="symbol-cell">
                        <strong>${escapeHtml(candidate.symbol || "--")}</strong>
                        <span>${escapeHtml(candidate.exchangeSymbol || "--")}</span>
                    </div>
                </td>
                <td>${escapeHtml(candidate.assetTypeLabel || formatEnumLabel(candidate.assetType))}</td>
                <td>${escapeHtml(candidate.exchangeLabel || formatEnumLabel(candidate.exchange))}</td>
                <td class="number-cell picker-symbol-cell">${escapeHtml(candidate.exchangeSymbol || "--")}</td>
                <td>${escapeHtml(candidate.description || "--")}</td>
            </tr>
        `).join("");
        restoreInstrumentPickerFocus();
    }

    function onDocumentKeyDown(event) {
        if (event.key === "Escape" && !dom.catalogDialog.classList.contains("hidden")) {
            event.preventDefault();
            closeCatalogDialog();
            return;
        }

        if (event.key === "Escape" && !dom.latencyDialog.classList.contains("hidden")) {
            event.preventDefault();
            closeLatencyDialog();
            return;
        }

        if (!state.instrumentPicker.open) {
            return;
        }

        if (event.key === "Escape") {
            event.preventDefault();
            closeInstrumentDialog();
            return;
        }

        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            moveInstrumentFocus(event.key === "ArrowDown" ? 1 : -1, event.shiftKey);
            return;
        }

        if (event.key === " " && !state.instrumentPicker.loading && !state.instrumentPicker.submitting) {
            event.preventDefault();
            if (Number.isInteger(state.instrumentPicker.focusedIndex)) {
                updateInstrumentSelection(state.instrumentPicker.focusedIndex, event);
                renderInstrumentDialog();
            }
            return;
        }

        if (event.key === "Enter"
            && !state.instrumentPicker.loading
            && !state.instrumentPicker.submitting
            && state.instrumentPicker.selectedKeys.size > 0) {
            event.preventDefault();
            confirmInstrumentSelection();
        }
    }

    function updateInstrumentSelection(candidateIndex, event = {}) {
        const candidate = state.instrumentPicker.candidates[candidateIndex];
        if (!candidate) {
            return;
        }

        const key = candidateKey(candidate);
        const metaSelection = Boolean(event.metaKey || event.ctrlKey);
        const rangeSelection = Boolean(event.shiftKey);

        if (rangeSelection && Number.isInteger(state.instrumentPicker.anchorIndex)) {
            const start = Math.min(state.instrumentPicker.anchorIndex, candidateIndex);
            const end = Math.max(state.instrumentPicker.anchorIndex, candidateIndex);
            const nextSelection = new Set();
            for (let index = start; index <= end; index += 1) {
                const rangedCandidate = state.instrumentPicker.candidates[index];
                if (rangedCandidate) {
                    nextSelection.add(candidateKey(rangedCandidate));
                }
            }
            state.instrumentPicker.selectedKeys = nextSelection;
        } else if (metaSelection) {
            if (state.instrumentPicker.selectedKeys.has(key)) {
                state.instrumentPicker.selectedKeys.delete(key);
            } else {
                state.instrumentPicker.selectedKeys.add(key);
            }
            state.instrumentPicker.selectedKeys = new Set(state.instrumentPicker.selectedKeys);
            state.instrumentPicker.anchorIndex = candidateIndex;
        } else {
            state.instrumentPicker.selectedKeys = new Set([key]);
            state.instrumentPicker.anchorIndex = candidateIndex;
        }

        state.instrumentPicker.focusedIndex = candidateIndex;
    }

    function moveInstrumentFocus(direction, extendSelection) {
        const picker = state.instrumentPicker;
        if (!picker.candidates.length) {
            return;
        }

        const currentIndex = Number.isInteger(picker.focusedIndex) ? picker.focusedIndex : 0;
        const nextIndex = Math.max(0, Math.min(picker.candidates.length - 1, currentIndex + direction));
        picker.focusedIndex = nextIndex;

        if (extendSelection) {
            updateInstrumentSelection(nextIndex, {shiftKey: true});
        }

        renderInstrumentDialog();
    }

    function restoreInstrumentPickerFocus() {
        const picker = state.instrumentPicker;
        if (!picker.open || !Number.isInteger(picker.focusedIndex)) {
            return;
        }
        const row = dom.instrumentDialogRows.querySelector(`tr[data-candidate-index="${picker.focusedIndex}"]`);
        row?.focus({preventScroll: true});
    }

    function candidateKey(candidate) {
        return [
            candidate?.exchange || "",
            candidate?.assetType || "",
            candidate?.exchangeSymbol || "",
            candidate?.symbol || ""
        ].join("|");
    }

    function findBrokerProfile(exchange) {
        return (state.brokerProfiles.profiles || []).find(profile => profile.exchange === exchange) || null;
    }

    function profileFieldInputId(key) {
        return `profile-field-${key}`;
    }

    function mergeProfileByExchange(existingProfiles, nextProfile) {
        const profiles = (existingProfiles || []).filter(profile => profile.exchange !== nextProfile.exchange);
        profiles.push(nextProfile);
        profiles.sort((left, right) => String(left.displayName || left.exchange).localeCompare(String(right.displayName || right.exchange)));
        return profiles;
    }

    function isActiveOrderStatus(status) {
        return ["NEW", "PARTIAL_FILL", "PENDING_CANCEL", "REPLACED"].includes(String(status || "").toUpperCase());
    }

    function canCancelOrder(order) {
        return isActiveOrderStatus(order?.status);
    }

    function canModifyOrder(order) {
        return ["NEW", "PARTIAL_FILL", "REPLACED"].includes(String(order?.status || "").toUpperCase());
    }

    function ticketNoteForMarket(market) {
        const connection = (state.brokerConnections || []).find(item => item.exchange === market.exchange);
        if (!connection || !connection.configured) {
            return `Save an encrypted broker profile for ${formatEnumLabel(market.exchange)} before submitting live orders.`;
        }
        if (!connection.connected) {
            return connection.error
                ? `${formatEnumLabel(market.exchange)} profile is saved but the broker is not connected: ${connection.error}`
                : `${formatEnumLabel(market.exchange)} profile is saved but the broker is not connected yet.`;
        }
        return `${connection.exchangeLabel || formatEnumLabel(connection.exchange)} is connected on ${formatEnumLabel(connection.environment)}. Orders route live to the exchange broker.`;
    }

    function loadOrderIntoTicket(order) {
        state.editingOrderId = order.id;
        state.selectedSide = order.side || "BUY";
        syncSideButtons();
        if (order.marketId) {
            state.selectedMarketId = order.marketId;
        } else {
            const matchingMarket = state.markets.find(market =>
                market.exchange === order.exchange && (
                    market.id === order.marketId
                    || market.exchangeSymbol === order.exchangeSymbol
                    || market.symbol === order.symbol
                ));
            if (matchingMarket) {
                state.selectedMarketId = matchingMarket.id;
            }
        }
        dom.orderTypeSelect.value = order.orderType || "LIMIT";
        dom.timeInForceSelect.value = order.timeInForce || "GTC";
        dom.clientOrderIdInput.value = order.clientOrderId || "";
        dom.quantityInput.value = order.quantity ? formatEditableNumber(order.quantity) : "";
        dom.limitPriceInput.value = order.limitPrice ? formatEditableNumber(order.limitPrice) : "";
        toggleLimitField();
        updateTicketSummary();
        renderTicket();
        dom.quantityInput.focus();
        showToast(`Editing ${order.symbol || "order"} for modification.`);
    }

    function clearOrderEdit() {
        state.editingOrderId = null;
        dom.clearOrderEditButton.classList.add("hidden");
        dom.submitOrderButton.textContent = "Transmit Order";
    }

    async function api(url, options = {}) {
        const response = await fetch(url, {
            headers: {
                "Content-Type": "application/json",
                ...(state.csrfToken ? {[state.csrfHeader]: state.csrfToken} : {}),
                ...(options.headers || {})
            },
            ...options
        });

        if (response.status === 204) {
            return null;
        }

        const contentType = response.headers.get("content-type") || "";
        const payload = contentType.includes("application/json")
            ? await response.json()
            : await response.text();

        if (!response.ok) {
            throw new Error(payload?.error || payload || "Request failed.");
        }

        return payload;
    }

    function mergeById(existingItems, nextItem) {
        const items = existingItems.filter(item => item.id !== nextItem.id);
        items.push(nextItem);
        return items;
    }

    function configuredMaxRows() {
        const maxRows = parseInteger(state.metadata.maxRows);
        return Number.isInteger(maxRows) && maxRows >= MIN_WATCHLIST_ROWS ? maxRows : Math.max(MIN_WATCHLIST_ROWS, maxRows || 50);
    }

    function normalizeRowIndex(value) {
        const rowIndex = parseInteger(value);
        return Number.isInteger(rowIndex) && rowIndex >= 0 ? rowIndex : null;
    }

    function parseInteger(value) {
        const parsed = Number.parseInt(value, 10);
        return Number.isFinite(parsed) ? parsed : null;
    }

    function focusDraftRow(rowIndex) {
        if (!Number.isInteger(rowIndex)) {
            return;
        }
        const input = dom.marketRows.querySelector(`.watchlist-symbol-input[data-row-index="${rowIndex}"]`);
        if (!input) {
            return;
        }
        input.focus({preventScroll: true});
        input.select();
    }

    function planTargetRows(count, startingRow) {
        const occupiedRows = new Set(
            state.markets
                .map(market => normalizeRowIndex(market.rowIndex))
                .filter(Number.isInteger)
        );
        const maxRows = configuredMaxRows();
        const safeStart = Number.isInteger(startingRow) ? startingRow : 0;
        const rows = [];

        for (let offset = 0; offset < maxRows && rows.length < count; offset += 1) {
            const rowIndex = (safeStart + offset) % maxRows;
            if (occupiedRows.has(rowIndex)) {
                continue;
            }
            rows.push(rowIndex);
            occupiedRows.add(rowIndex);
        }

        return rows;
    }

    function computeSpread(bid, ask) {
        const bidValue = toNumber(bid);
        const askValue = toNumber(ask);
        const mid = (bidValue + askValue) / 2;
        return Number.isFinite(bidValue) && Number.isFinite(askValue) && mid > 0
            ? ((askValue - bidValue) / mid) * 10000
            : null;
    }

    function midpoint(bid, ask) {
        const bidValue = toNumber(bid);
        const askValue = toNumber(ask);
        return Number.isFinite(bidValue) && Number.isFinite(askValue)
            ? (bidValue + askValue) / 2
            : null;
    }

    function firstNumber(...values) {
        for (const value of values) {
            const parsed = toNumber(value);
            if (Number.isFinite(parsed)) {
                return parsed;
            }
        }
        return null;
    }

    function toNumber(value) {
        if (value === null || value === undefined || value === "") {
            return NaN;
        }
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : NaN;
    }

    function formatPrice(value, minDecimals = 0) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "--";
        }
        const abs = Math.abs(number);
        const maxDigits = abs >= 1000 ? 2 : abs >= 1 ? 4 : 6;
        const minDigits = Math.min(minDecimals, maxDigits);
        return number.toLocaleString(undefined, {
            minimumFractionDigits: minDigits,
            maximumFractionDigits: maxDigits
        });
    }

    function formatNumber(value, maxDigits) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "--";
        }
        return number.toLocaleString(undefined, {
            minimumFractionDigits: 0,
            maximumFractionDigits: maxDigits
        });
    }

    function formatSignedNumber(value, maxDigits) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "--";
        }
        const formatted = formatNumber(number, maxDigits);
        return number > 0 ? `+${formatted}` : formatted;
    }

    function formatCompactNumber(value) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "--";
        }
        return new Intl.NumberFormat(undefined, {
            notation: "compact",
            maximumFractionDigits: 2
        }).format(number);
    }

    function formatCurrencyLike(value) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "--";
        }
        return number.toLocaleString(undefined, {
            style: "currency",
            currency: "USD",
            minimumFractionDigits: number >= 1000 ? 0 : 2,
            maximumFractionDigits: 2
        });
    }

    function formatLatency(value) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "--";
        }
        return `${number.toLocaleString(undefined, {
            minimumFractionDigits: 0,
            maximumFractionDigits: 0
        })} ms`;
    }

    function formatSampleCount(value) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "--";
        }
        return Math.round(number).toLocaleString();
    }

    function formatClock(value) {
        if (!value) {
            return "--:--:--";
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "--:--:--";
        }
        return date.toLocaleTimeString([], {
            timeZone: "UTC",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            hour12: false
        });
    }

    function formatTime(value) {
        if (!value) {
            return "--";
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "--";
        }
        return date.toLocaleTimeString([], {
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit"
        });
    }

    function formatAge(value) {
        if (!value) {
            return "--";
        }
        const updated = new Date(value).getTime();
        if (!Number.isFinite(updated)) {
            return "--";
        }
        const deltaSeconds = Math.max(0, Math.floor((Date.now() - updated) / 1000));
        if (deltaSeconds < 5) {
            return "now";
        }
        if (deltaSeconds < 60) {
            return `${deltaSeconds}s`;
        }
        const minutes = Math.floor(deltaSeconds / 60);
        if (minutes < 60) {
            return `${minutes}m`;
        }
        const hours = Math.floor(minutes / 60);
        return `${hours}h`;
    }

    function formatEditableNumber(value) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "";
        }
        return number.toLocaleString("en-US", {
            useGrouping: false,
            maximumFractionDigits: 12
        });
    }

    function formatEnumLabel(value) {
        if (!value) {
            return "--";
        }
        return String(value)
            .toLowerCase()
            .split("_")
            .map(token => token.length <= 3 ? token.toUpperCase() : token.charAt(0).toUpperCase() + token.slice(1))
            .join(" ");
    }

    function escapeHtml(value) {
        return String(value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    function escapeAttribute(value) {
        return escapeHtml(value);
    }

    function showToast(message) {
        clearTimeout(state.toastTimer);
        dom.toast.textContent = message;
        dom.toast.classList.remove("hidden");
        state.toastTimer = setTimeout(() => {
            dom.toast.classList.add("hidden");
        }, 2600);
    }
})();
