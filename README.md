# FueledByChai Workstation

Spring Boot + Thymeleaf trading workstation for live crypto market data using FueledByChai `QuoteEngine` implementations.

## Current Focus

The UI is now organized like a compact desktop trading terminal:

- Multi-venue watchlist rows by:
  - `symbol` (common symbol, ex: `BTC/USDC`)
  - `assetType` (`PERP` or `SPOT`)
  - `exchange` (`LIGHTER`, `HYPERLIQUID`, `PARADEX`, `BINANCE_SPOT`)
- Dense market monitor columns:
  - bid / ask / spread / last / volume
  - funding annualized %
  - funding bps per hour
  - route and update age
- Routed order ticket:
  - select a watchlist row
  - choose side / order type / quantity / time in force
  - submit a staged order into the blotter
- Open-order blotter:
  - see current open orders
  - cancel them from the UI
- WebSocket push updates on `/ws/dashboard`

## Important Note

Order entry is currently `PAPER` mode only. Orders are stored in-memory inside this app. The UI and API are shaped for real broker/exchange routing later, but this repo does not yet transmit live orders to an exchange.

## Requirements

- Java 21+
- Maven
- Local Maven artifacts from FueledByChai (version `0.2.0-SNAPSHOT`)

## Run

```bash
mvn spring-boot:run
```

Open `http://localhost:8080`

## Config

`src/main/resources/application.yml`

```yaml
dashboard:
  broadcast-interval-ms: 100
  history-minutes: 10
  max-rows: 50
  proxy:
    enabled: false
    host: ""
    port: 1080
```

## API

- `GET /api/snapshot`
- `GET /api/markets`
- `POST /api/markets`
- `DELETE /api/markets/{marketId}`
- `GET /api/history/{marketId}`
- `GET /api/orders`
- `POST /api/orders`
- `DELETE /api/orders/{orderId}`
- `GET /api/metadata`
- `POST /api/pairs`
- `DELETE /api/pairs/{pairId}`

## Notes

- Pair APIs still exist on the backend, but the current UI no longer centers the arb workflow.
- dYdX is intentionally excluded for now.
