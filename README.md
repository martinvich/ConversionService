# Exchange Rate Conversion Service

Sample interview app implementing `POST /api/v1/conversion/trade`.

## Stack

- Scala 2.13
- sbt
- Pekko Typed + pekko-http
- spray-json

## SDKMAN

- This project includes `.sdkmanrc` to pin local tool versions.
- In the project directory run:

```bash
sdk env
```

## API

Full API specification is in [`openapi.yaml`](openapi.yaml) (OpenAPI 3.0).

### Endpoint

`POST /api/v1/conversion/trade`

Request:

```json
{
  "marketId": 123456,
  "selectionId": 987654,
  "odds": 2.2,
  "stake": 253.67,
  "currency": "USD",
  "date": "2021-05-18T21:32:42.324Z"
}
```

Response has the same shape, with `stake` converted to EUR and `currency` set to `EUR`.

### Error codes

All error responses use a stable `code` + human-readable `message`:

| Code | HTTP | Meaning |
|------|------|---------|
| `MALFORMED_BODY` | 400 | Unparseable JSON or missing fields |
| `INVALID_REQUEST` | 400 | Validation failure (negative stake, future date, empty currency) |
| `RATE_UNAVAILABLE` | 500 | All FX providers failed |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

## Behavior

- Conversion day is derived in **UTC** from the request `date`.
- If source currency is already EUR, no provider call is made.
- Weekend dates normalize to the preceding Friday (standard FX convention).
- Stake rounding: scale 2, `HALF_UP`.

## Providers and failover

- Primary: CNB API (`/cnbapi/exrates/daily`)
- Fallback: exchangerate.host (`/{date}?base=...&symbols=...`)
- Fallback is attempted only when primary has an outage (`ProviderUnavailable`).
- If both providers are unavailable, endpoint returns `500`.

## Cache

- In-memory actor-based cache (`FxRateCacheActor`), no caching library used.
- Cache key: `(fromCurrency, toCurrency, date)`.
- TTL: 2 hours (configurable).
- Concurrent requests for the same key are deduplicated while provider fetch is in flight.

## Project structure

```
src/main/scala/com/ematiq/cs/demo/
├── Main.scala                          Application entry point
├── api/
│   ├── JsonSupport.scala               Spray JSON formats
│   └── TradeRoutes.scala               HTTP routes and error handling
├── config/
│   └── AppConfig.scala                 Typesafe Config loader
├── domain/
│   └── TradePayload.scala              TradeRequest / TradeResponse DTOs
├── service/
│   └── TradeConversionService.scala    Conversion business logic
└── fx/
    ├── ExchangeRateLookup.scala        Lookup trait (public interface)
    ├── ExchangeRateErrors.scala        Exception hierarchy
    ├── provider/
    │   ├── ExchangeRateProvider.scala  Provider trait
    │   ├── CnbExchangeRateProvider.scala
    │   ├── CnbApiJson.scala
    │   ├── ExchangeRateHostProvider.scala
    │   ├── FailoverExchangeRateProvider.scala
    │   └── UrlEncoding.scala
    └── cache/
        ├── FxRateCacheActor.scala      Pekko typed actor with TTL eviction
        └── CachedExchangeRateLookup.scala
```

## Configuration

`src/main/resources/application.conf`

| Key | Description |
|-----|-------------|
| `app.http.host`, `app.http.port` | Server bind address |
| `app.cache.ttl` | Rate cache time-to-live |
| `app.cache.ask-timeout` | Cache actor ask timeout |
| `app.providers.cnb.base-url`, `timeout`, `lang` | CNB provider settings |
| `app.providers.exchangerate-host.base-url`, `timeout`, `access-key` | ExchangeRate-Host settings |

`access-key` for exchangerate.host is required for full access; empty by default.

## Known Limitations

- Target currency is hardcoded to EUR.
- Weekend dates normalize to Friday. Public holidays are not handled.
- Backup provider has no API key configured by default.
- No currency code validation -- invalid codes propagate to the external API before failing.

## Run

```bash
sbt run
```

See `requests.http` for ready-to-use examples (IntelliJ HTTP Client).

## Test

```bash
sbt test
```

Tests include unit tests per layer and a full-stack integration test (`TradeConversionIT`) that exercises the entire pipeline from HTTP request through cache actor to stubbed providers.
