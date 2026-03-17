(function () {
    const TICK_FLASH_MS = 1400;
    const MIN_WATCHLIST_ROWS = 20;

    const state = {
        metadata: defaultMetadata(),
        markets: [],
        orders: [],
        selectedMarketId: null,
        selectedSide: "BUY",
        lastValueByField: new Map(),
        tickDirectionByField: new Map(),
        marketDrafts: new Map(),
        serverTime: null,
        ws: null,
        reconnectTimer: null,
        toastTimer: null,
        instrumentPicker: defaultInstrumentPicker()
    };

    const dom = {
        connectionBadge: document.getElementById("connectionBadge"),
        serverClock: document.getElementById("serverClock"),
        watchlistCount: document.getElementById("watchlistCount"),
        openOrderCount: document.getElementById("openOrderCount"),
        inactiveOrderCount: document.getElementById("inactiveOrderCount"),
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
        quantityInput: document.getElementById("quantityInput"),
        limitPriceField: document.getElementById("limitPriceField"),
        limitPriceInput: document.getElementById("limitPriceInput"),
        ticketReferencePrice: document.getElementById("ticketReferencePrice"),
        ticketEstimatedNotional: document.getElementById("ticketEstimatedNotional"),
        ticketMode: document.getElementById("ticketMode"),
        ticketNote: document.getElementById("ticketNote"),
        submitOrderButton: document.getElementById("submitOrderButton"),
        sideButtons: Array.from(document.querySelectorAll(".side-btn")),
        orderRows: document.getElementById("orderRows"),
        instrumentDialog: document.getElementById("instrumentDialog"),
        instrumentDialogPanel: document.getElementById("instrumentDialogPanel"),
        instrumentDialogTitle: document.getElementById("instrumentDialogTitle"),
        instrumentDialogSubtitle: document.getElementById("instrumentDialogSubtitle"),
        instrumentDialogRows: document.getElementById("instrumentDialogRows"),
        closeInstrumentDialogButton: document.getElementById("closeInstrumentDialogButton"),
        cancelInstrumentButton: document.getElementById("cancelInstrumentButton"),
        confirmInstrumentButton: document.getElementById("confirmInstrumentButton")
    };

    init();

    function init() {
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
            orderMode: "PAPER",
            maxRows: 50
        };
    }

    function defaultInstrumentPicker() {
        return {
            open: false,
            rowIndex: null,
            query: "",
            candidates: [],
            selectedIndex: -1,
            loading: false,
            submitting: false,
            error: ""
        };
    }

    function bindEvents() {
        dom.marketRows.addEventListener("click", onMarketTableClick);
        dom.marketRows.addEventListener("input", onMarketDraftInput);
        dom.marketRows.addEventListener("keydown", onMarketDraftKeyDown);
        dom.orderForm.addEventListener("submit", onOrderSubmit);
        dom.orderRows.addEventListener("click", onOrderTableClick);
        dom.instrumentDialog.addEventListener("click", onInstrumentDialogClick);
        dom.closeInstrumentDialogButton.addEventListener("click", closeInstrumentDialog);
        dom.cancelInstrumentButton.addEventListener("click", closeInstrumentDialog);
        dom.confirmInstrumentButton.addEventListener("click", confirmInstrumentSelection);
        dom.instrumentDialogRows.addEventListener("click", onInstrumentPickerRowClick);
        dom.instrumentDialogRows.addEventListener("dblclick", onInstrumentPickerRowDoubleClick);
        document.addEventListener("keydown", onDocumentKeyDown);

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
            const [metadata, snapshot] = await Promise.all([
                api("/api/metadata"),
                api("/api/snapshot")
            ]);
            state.metadata = {...defaultMetadata(), ...(metadata || {})};
            hydrateMetadata();
            applySnapshot(snapshot);
        } catch (error) {
            showToast(error.message || "Unable to initialize the workstation.");
        }
    }

    function hydrateMetadata() {
        populateSelect(dom.orderTypeSelect, state.metadata.orderTypes || [], dom.orderTypeSelect.value || "LIMIT");
        populateSelect(dom.timeInForceSelect, state.metadata.timeInForce || [], dom.timeInForceSelect.value || "GTC");
        dom.orderModePill.textContent = `${formatEnumLabel(state.metadata.orderMode || "PAPER")} Gateway`;
        dom.ticketMode.textContent = formatEnumLabel(state.metadata.orderMode || "PAPER");
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
        if (!market) {
            showToast("Select a market before transmitting an order.");
            return;
        }

        const payload = {
            marketId: market.id,
            side: state.selectedSide,
            orderType: dom.orderTypeSelect.value,
            timeInForce: dom.timeInForceSelect.value,
            quantity: dom.quantityInput.value,
            limitPrice: dom.orderTypeSelect.value === "LIMIT" ? dom.limitPriceInput.value : null
        };

        try {
            const created = await api("/api/orders", {
                method: "POST",
                body: JSON.stringify(payload)
            });
            state.orders = mergeById(state.orders, created);
            dom.quantityInput.value = "";
            if (dom.orderTypeSelect.value === "LIMIT") {
                applySuggestedLimitPrice(true);
            }
            updateTicketSummary();
            renderSummary();
            renderOrders();
            showToast(`Order staged: ${formatEnumLabel(created.side)} ${created.symbol} on ${formatEnumLabel(created.exchange)}.`);
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

        try {
            await api(`/api/orders/${button.dataset.orderId}`, {method: "DELETE"});
            const now = new Date().toISOString();
            state.orders = state.orders.map(order => order.id === button.dataset.orderId
                ? {...order, status: "CANCELED", updatedAt: now, canceledAt: now}
                : order);
            renderSummary();
            renderOrders();
            showToast("Order canceled.");
        } catch (error) {
            showToast(error.message);
        }
    }

    function connectWebSocket() {
        clearTimeout(state.reconnectTimer);
        setConnection(false);

        const protocol = window.location.protocol === "https:" ? "wss" : "ws";
        state.ws = new WebSocket(`${protocol}://${window.location.host}/ws/dashboard`);

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
        state.serverTime = payload?.serverTime || state.serverTime;
        syncSelectedMarket();
        renderAll();
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
        renderSummary();
        renderMarkets();
        renderTicket();
        renderOrders();
        renderInstrumentDialog();
    }

    function renderSummary() {
        const openOrders = state.orders.filter(order => order.status === "OPEN");
        const inactiveOrders = state.orders.filter(order => order.status !== "OPEN");

        dom.watchlistCount.textContent = String(state.markets.length);
        dom.openOrderCount.textContent = String(openOrders.length);
        dom.inactiveOrderCount.textContent = String(inactiveOrders.length);
        dom.serverClock.textContent = formatClock(state.serverTime);
    }

    function renderMarkets() {
        const focusState = captureDraftFocus();
        const slotMap = buildMarketSlotMap();
        const visibleRows = visibleWatchlistRowCount(slotMap);

        dom.marketRows.innerHTML = Array.from({length: visibleRows}, (_, rowIndex) => {
            const market = slotMap.get(rowIndex);
            return market ? renderActiveMarketRow(market, rowIndex) : renderDraftRow(rowIndex);
        }).join("");

        restoreDraftFocus(focusState);
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

    function renderActiveMarketRow(market, rowIndex) {
        const spread = computeSpread(market.bid, market.ask);
        const isSelected = market.id === state.selectedMarketId;
        return `
            <tr data-market-id="${escapeAttribute(market.id)}" data-row-index="${rowIndex}" class="${isSelected ? "selected" : ""}">
                <td class="row-index-cell">${rowIndex + 1}</td>
                <td>
                    <div class="symbol-cell">
                        <strong>${escapeHtml(market.symbol || "--")}</strong>
                        <span>${escapeHtml(market.exchangeSymbol || "--")}</span>
                    </div>
                </td>
                <td>${escapeHtml(formatEnumLabel(market.assetType))}</td>
                <td>${escapeHtml(formatEnumLabel(market.exchange))}</td>
                <td class="number-cell">${renderQuoteValue(market.id, "bid", market.bid)}</td>
                <td class="number-cell">${renderQuoteValue(market.id, "ask", market.ask)}</td>
                <td class="number-cell">${formatSignedNumber(spread, 6)}</td>
                <td class="number-cell">${renderQuoteValue(market.id, "last", market.last)}</td>
                <td class="number-cell">${formatCompactNumber(market.volume)}</td>
                <td class="number-cell">${formatNumber(market.fundingRateApr, 2)}</td>
                <td>${formatAge(market.updatedAt)}</td>
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
                <td class="row-index-cell">${rowIndex + 1}</td>
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
                <td class="placeholder-cell">${draftValue ? "Return" : ""}</td>
                <td class="placeholder-cell row-hint-cell">${draftValue ? "Resolve" : ""}</td>
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
            return;
        }

        dom.selectedTicker.textContent = market.symbol || "--";
        dom.selectedExchangeSymbol.textContent = market.exchangeSymbol || "--";
        dom.selectedRoute.textContent = formatEnumLabel(market.exchange);
        dom.selectedAsset.textContent = formatEnumLabel(market.assetType);
        dom.selectedBid.textContent = formatPrice(market.bid);
        dom.selectedAsk.textContent = formatPrice(market.ask);
        dom.selectedLast.textContent = formatPrice(market.last);
        dom.ticketNote.textContent = "Paper-mode order store only. Replace the order service with live routing when execution adapters are ready.";

        syncSideButtons();
        toggleLimitField();
        updateTicketSummary();
    }

    function renderOrders() {
        const openOrders = state.orders.filter(order => order.status === "OPEN");

        if (!openOrders.length) {
            dom.orderRows.innerHTML = `
                <tr>
                    <td colspan="11" class="empty-row">
                        <strong>No open orders</strong>
                        <span>Transmit from the ticket to stage an order in the blotter.</span>
                    </td>
                </tr>
            `;
            return;
        }

        dom.orderRows.innerHTML = openOrders.map(order => `
            <tr>
                <td>${formatTime(order.createdAt)}</td>
                <td>
                    <div class="symbol-cell">
                        <strong>${escapeHtml(order.symbol || "--")}</strong>
                        <span>${escapeHtml(order.exchangeSymbol || "--")}</span>
                    </div>
                </td>
                <td>${escapeHtml(formatEnumLabel(order.exchange))}</td>
                <td><span class="side-pill ${order.side === "BUY" ? "buy" : "sell"}">${escapeHtml(formatEnumLabel(order.side))}</span></td>
                <td>${escapeHtml(formatEnumLabel(order.orderType))}</td>
                <td>${escapeHtml(formatEnumLabel(order.timeInForce))}</td>
                <td class="number-cell">${formatNumber(order.quantity, 6)}</td>
                <td class="number-cell">${formatPrice(order.limitPrice)}</td>
                <td class="number-cell">${formatPrice(order.referencePrice)}</td>
                <td><span class="status-pill">${escapeHtml(formatEnumLabel(order.status))}</span></td>
                <td>
                    <button type="button" class="cancel-btn" data-order-id="${escapeAttribute(order.id)}">Cancel</button>
                </td>
            </tr>
        `).join("");
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
        return `<span class="${cssClass}">${escapeHtml(formatPrice(value))}</span>`;
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
            selectedIndex: -1,
            loading: true,
            submitting: false,
            error: ""
        };
        renderInstrumentDialog();

        try {
            const candidates = await api(`/api/instruments?symbol=${encodeURIComponent(query)}`);
            state.instrumentPicker.candidates = Array.isArray(candidates) ? candidates : [];
            state.instrumentPicker.selectedIndex = state.instrumentPicker.candidates.length ? 0 : -1;
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

    function onInstrumentPickerRowClick(event) {
        const row = event.target.closest("tr[data-candidate-index]");
        if (!row) {
            return;
        }
        state.instrumentPicker.selectedIndex = parseInteger(row.dataset.candidateIndex);
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
        state.instrumentPicker.selectedIndex = parseInteger(row.dataset.candidateIndex);
        renderInstrumentDialog();
        confirmInstrumentSelection();
    }

    async function confirmInstrumentSelection() {
        const picker = state.instrumentPicker;
        const candidate = picker.candidates[picker.selectedIndex];
        if (!picker.open || !candidate) {
            showToast("Select a route before adding the row.");
            return;
        }

        state.instrumentPicker.submitting = true;
        renderInstrumentDialog();

        try {
            const created = await api("/api/markets", {
                method: "POST",
                body: JSON.stringify({
                    symbol: candidate.symbol,
                    exchange: candidate.exchange,
                    assetType: candidate.assetType,
                    rowIndex: picker.rowIndex
                })
            });

            state.markets = mergeById(state.markets, created);
            state.marketDrafts.delete(picker.rowIndex);
            state.instrumentPicker = defaultInstrumentPicker();
            selectMarket(created.id, state.selectedSide, true);
            renderAll();
            showToast(`Added ${created.symbol} on ${formatEnumLabel(created.exchange)}.`);
        } catch (error) {
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
        dom.confirmInstrumentButton.disabled = picker.loading || picker.submitting || picker.selectedIndex < 0;
        dom.confirmInstrumentButton.textContent = picker.submitting ? "Adding..." : "Add Instrument";

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
            <tr data-candidate-index="${index}" class="${index === picker.selectedIndex ? "selected" : ""}">
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
    }

    function onDocumentKeyDown(event) {
        if (!state.instrumentPicker.open) {
            return;
        }

        if (event.key === "Escape") {
            event.preventDefault();
            closeInstrumentDialog();
            return;
        }

        if (event.key === "ArrowDown") {
            event.preventDefault();
            moveInstrumentSelection(1);
            return;
        }

        if (event.key === "ArrowUp") {
            event.preventDefault();
            moveInstrumentSelection(-1);
            return;
        }

        if (event.key === "Enter" && !state.instrumentPicker.loading && !state.instrumentPicker.submitting) {
            event.preventDefault();
            confirmInstrumentSelection();
        }
    }

    function moveInstrumentSelection(direction) {
        if (!state.instrumentPicker.candidates.length) {
            return;
        }
        const nextIndex = state.instrumentPicker.selectedIndex < 0
            ? 0
            : (state.instrumentPicker.selectedIndex + direction + state.instrumentPicker.candidates.length) % state.instrumentPicker.candidates.length;
        state.instrumentPicker.selectedIndex = nextIndex;
        renderInstrumentDialog();
    }

    async function api(url, options = {}) {
        const response = await fetch(url, {
            headers: {
                "Content-Type": "application/json",
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

    function computeSpread(bid, ask) {
        const bidValue = toNumber(bid);
        const askValue = toNumber(ask);
        return Number.isFinite(bidValue) && Number.isFinite(askValue)
            ? askValue - bidValue
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

    function formatPrice(value) {
        const number = toNumber(value);
        if (!Number.isFinite(number)) {
            return "--";
        }
        const digits = Math.abs(number) >= 1000 ? 2 : Math.abs(number) >= 1 ? 4 : 6;
        return number.toLocaleString(undefined, {
            minimumFractionDigits: 0,
            maximumFractionDigits: digits
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

    function formatClock(value) {
        if (!value) {
            return "--:--:--";
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "--:--:--";
        }
        return date.toLocaleTimeString([], {
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit"
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
