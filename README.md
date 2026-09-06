# Car Booking Service

A Spring Boot microservice for **Velocity Motors** that manages car rental bookings, built as part of a take-home assignment. It confirms bookings based on payment method (digital wallet/cash, credit card, or bank transfer), integrates with an external credit-card validation service, consumes bank-transfer payment events from Kafka, and automatically cancels unpaid bank-transfer bookings 48 hours before rental start.

## Tech Stack

- **Java 21**, **Spring Boot 4.1.1** (Spring Framework 7 / Jackson 3)
- **Spring Web MVC** — REST API
- **Spring Data JPA** + **H2** (in-memory) — persistence
- **Spring for Apache Kafka** — consumes `bank-transfer-payment-events`
- **Spring WebFlux's `WebClient`** — calls the external credit-card-validation-service (no reactive server is run; `WebClient` is used purely as an HTTP client)
- **Lombok** — reduces entity boilerplate
- **Maven** — build

## Architecture

```
controller/     BookingController              — REST endpoint
dto/            BookingRequest, BookingResponse, ErrorResponse
enums/          VehicleCategory, PaymentMode, BookingStatus
entity/         Booking (JPA)
repository/     BookingRepository               — atomic conditional updates, see below
service/        BookingService, VehicleValidationService, BookingIdGenerator
payment/        PaymentStrategy + one implementation per payment mode (Strategy pattern)
client/         CreditCardValidationClient (+ impl), client/dto/*
kafka/          KafkaConsumerConfig, BankTransferPaymentEvent, BankTransferPaymentEventListener
scheduler/      BookingCancellationScheduler
config/         ClockConfig, WebClientConfig
exception/      GlobalExceptionHandler + one exception per failure case
```

**Payment-mode branching uses the Strategy pattern** (`payment/PaymentStrategy` + `DigitalWalletPaymentStrategy`, `CreditCardPaymentStrategy`, `BankTransferPaymentStrategy`), rather than an if/else chain in the service. `BookingService` resolves the correct strategy from a `Map<PaymentMode, PaymentStrategy>` built automatically from every `PaymentStrategy` bean Spring discovers — adding a new payment mode later means adding one class, not editing existing logic (open/closed principle).

## API

### `POST /booking`

**Request body:**
```json
{
  "customerName": "Shrikant",
  "vehicleId": "VEH12345",
  "rentalStartDate": "2026-09-20",
  "rentalEndDate": "2026-09-22",
  "vehicleCategory": "SUV",
  "paymentMode": "CASH",
  "paymentReference": ""
}
```
- `vehicleCategory`: `COMPACT` | `SEDAN` | `SUV` | `LUXURY`
- `paymentMode`: `CASH` | `DIGITAL_WALLET` | `CREDIT_CARD` | `BANK_TRANSFER`
- `paymentReference`: required only when `paymentMode = CREDIT_CARD` (see Assumptions)

**Response (`201 Created`):**
```json
{ "bookingId": "BKG0000001", "status": "CONFIRMED" }
```
`status` is one of `PENDING_PAYMENT` / `CONFIRMED` / `CANCELLED`.

**Validation & error responses** — every error returns `{ "message": "...", "timestamp": "..." }`:

| Condition | Status |
|---|---|
| Bean validation failure (blank/missing required field) | 400 |
| Vehicle ID fails validation | 400 |
| Rental period > 21 days, or end date not after start date | 400 |
| `paymentReference` missing for `CREDIT_CARD` | 400 |
| Bank transfer requested with rental start already inside the 48h cancellation window | 400 |
| Credit card validation returned `REJECTED` (or any non-`APPROVED` status) | 422 |
| credit-card-validation-service unreachable or returned an error | 502 |
| Anything unexpected | 500 |

### Payment flows

- **Cash / Digital Wallet** → booking confirmed immediately, no external call.
- **Credit Card** → `paymentReference` is sent to `credit-card-validation-service` (`POST /payment-status`, per the given OpenAPI spec). `APPROVED` confirms the booking; anything else raises `PaymentDeclinedException` (422). Nothing is persisted on rejection.
- **Bank Transfer** → booking is created as `PENDING_PAYMENT` (unless the rental start is already inside the 48h cancellation window — rejected upfront instead, see Assumptions). Confirmation happens later via the Kafka consumer described below.

### Event-driven confirmation (`bank-transfer-payment-events`)

The service consumes JSON messages shaped:
```json
{"paymentId":"PAY001","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TXN987654321 BKG0012345"}
```
`transactionDetails` packs a 12-character transaction reference and a 10-character Booking ID together. The listener extracts the **trailing 10 characters** of the (trimmed) string as the Booking ID — robust to extra whitespace between the two fields — and atomically confirms that booking if (and only if) it's still `PENDING_PAYMENT`. Unknown/already-resolved booking IDs are logged and skipped, not treated as errors (this also handles duplicate/replayed messages safely).

### Automatic cancellation

A scheduled job (`BookingCancellationScheduler`, interval configurable) checks every `PENDING_PAYMENT` bank-transfer booking and cancels it once the current time reaches `rentalStartDate` (at midnight) minus the configured cancellation window (default 48h). Cancellation uses the same atomic-conditional-update mechanism as Kafka confirmation, so the two can never race into an inconsistent state (see Assumptions).

## Assumptions & Design Decisions

The assignment states *"all details provided are sufficient; you may make additional logical assumptions when needed."* The brief itself contains some internal gaps/inconsistencies; here's every non-obvious decision made to resolve them, and why:

1. **`CASH` and `DIGITAL_WALLET` are both treated as instant-confirm.** The Functional Requirements section says "digital wallet"; the Request Data section's payment-mode list says "Cash" instead, with no logic given for Cash at all. Both enum values exist and share one `DigitalWalletPaymentStrategy`.
2. **`credit-card-validation-service` is external — we only integrate a client, not the service.** The given OpenAPI YAML documents a contract we call, not something we implement.
3. **We evaluated generating that client via `openapi-generator-maven-plugin` (contract-first codegen) instead of hand-writing it**, since the assignment says the service "must integrate and use" the spec file. Concretely tested this: the plugin's `webclient` Java library (as of v7.10.0) unconditionally generates `com.fasterxml.jackson.*` (Jackson 2) imports, which don't exist on this project's classpath at all (Spring Boot 4.1.1 here runs on Jackson 3, `tools.jackson.*`) — confirmed via a real build attempt, not assumption. Rather than pull in a second, unrelated major version of Jackson just to satisfy the generated code, we kept the hand-written `WebClient` client and instead verified spec compliance with a dedicated test suite (`CreditCardValidationClientImplTest`) covering every response shape the spec documents (`APPROVED`, `REJECTED`, `404`, `500`, unreachable).
4. **Booking IDs are exactly 10 characters** (`"BKG"` + 7-digit zero-padded sequence, e.g. `BKG0012345`), matching the shape embedded in `transactionDetails`. This is the only correlation key the Kafka event carries back to a specific booking — if the ID format ever drifted from 10 characters, bank-transfer confirmations would silently stop matching, and paid bookings would be auto-cancelled anyway.
5. **`paymentReference` is functionally required only for `CREDIT_CARD`.** For bank transfer, correlation happens via the Booking ID embedded in the Kafka event, not via this field — so it's optional/unused for other payment modes.
6. **No pricing/amount schema is given anywhere in the assignment** (no per-category rate, no total-cost field). Any bank-transfer-payment-event that resolves to a given `PENDING_PAYMENT` booking is treated as full payment received — partial-payment accumulation isn't attempted since there's no defined total to compare against.
7. **`rentalStartDate`/`rentalEndDate` are dates with no time component.** The 48-hour cancellation deadline is computed as `rentalStartDate.atStartOfDay().minusHours(48)`.
8. **Bank-transfer bookings are rejected upfront (400) if the rental start is already inside the 48-hour window at creation time**, rather than being accepted as `PENDING_PAYMENT` only to be auto-cancelled moments later. Nothing in the brief asks for this explicitly, but accepting a booking that's already guaranteed to be cancelled is misleading to the customer and contradicts the spirit of the rule.
9. **Race safety**: the Kafka listener and the cancellation scheduler both transition booking status independently, on different threads. Both use an atomic, conditional SQL `UPDATE ... WHERE status = 'PENDING_PAYMENT'` (`confirmIfPending` / `cancelIfPending`), never a read-then-save — this guarantees a booking can't be confirmed and cancelled out of order regardless of timing.
10. **Vehicle ID validation is mocked**, per the assignment's own suggestion ("mock or assume validation logic") — a simple pattern check (`^[A-Z0-9]{5,10}$`), swappable later for a real vehicle-service call.
11. **The credit-card-validation-service's sample server URL in the given YAML is malformed** (`http//:localhost:9090//host/credit-card-payment-api`). A corrected, sane default is used, configurable via `credit-card-validation-service.base-url`.
12. **Kafka message consumption uses plain `String` + manual JSON parsing (via Jackson's `ObjectMapper`), not a typed Kafka `JsonDeserializer`.** Spring Kafka 4.0's `JsonDeserializer` is deprecated-for-removal in favor of a Jackson-3-only replacement whose exact API wasn't something we could verify reliably; the `String`-based approach uses only stable, well-understood APIs and gives explicit control over malformed-message handling in one place.
13. **Avro + Schema Registry were deliberately not used** for the Kafka event, even though this is a payments-adjacent scenario where that's common in real systems. The assignment gives a plain descriptive JSON structure (not a schema file, unlike the OpenAPI spec it did provide for the other integration) and no schema registry endpoint — introducing one would be unrequested infrastructure the grader can't run.
14. **A Testcontainers-based Kafka integration test exists but is excluded from the default build**, since it requires Docker and the assignment's hard requirement is that the code "must build successfully." The default suite uses Spring Kafka's embedded (in-process) broker instead; see Testing below for how to run the Testcontainers version explicitly.

## Running Locally

**Prerequisites:** JDK 21, Maven (or use the included `./mvnw`).

```bash
./mvnw spring-boot:run
```
The app starts on **port 8081** (`server.port` in `application.yaml`) against an in-memory H2 database — no external setup required for the REST API itself. The H2 console is available at `http://localhost:8081/h2-console` (the JDBC URL is printed in the startup logs, since it's not pinned to a fixed name).

### Running Kafka locally (to exercise the bank-transfer flow)

```bash
docker compose up -d
```
Starts a single-node Kafka broker (KRaft mode, no Zookeeper container needed) on `localhost:9092`. Create the topic once if it doesn't already exist:
```bash
docker exec car-booking-kafka /opt/kafka/bin/kafka-topics.sh --create --topic bank-transfer-payment-events --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --if-not-exists
```
Then publish a test event (replace `BKG0000001` with a real booking ID returned from a prior `POST /booking` call):
```bash
docker exec -it car-booking-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic bank-transfer-payment-events
```
```json
{"paymentId":"PAY001","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TXN987654321 BKG0000001"}
```

## Testing

```bash
mvn clean verify
```
Runs the full default suite — no Docker required. Covers:

- **Unit**: `BookingIdGeneratorTest`, `DigitalWalletPaymentStrategyTest`, `BankTransferPaymentStrategyTest`, `CreditCardPaymentStrategyTest`, `BookingServiceTest`, `BankTransferPaymentEventListenerTest`, `BookingCancellationSchedulerTest` (deterministic 48h-boundary testing via an injectable `Clock` — no real-time waiting needed)
- **HTTP layer**: `BookingControllerTest` (MockMvc — success path, Bean Validation wiring, and every exception→status mapping through `GlobalExceptionHandler`)
- **External client**: `CreditCardValidationClientImplTest` (OkHttp `MockWebServer` — verifies every response shape the OpenAPI spec documents)
- **Kafka integration**: `BankTransferKafkaIntegrationTest` (`@EmbeddedKafka` — full Spring context, real JSON over an in-process broker, real H2 write)

**Optional — Testcontainers-based Kafka integration test** (`BankTransferKafkaTestcontainersTest`), which runs the same scenario against a real Kafka broker in Docker instead of the embedded one. Excluded from the default build; run explicitly (Docker must be running):
```bash
mvn test -Dtest=BankTransferKafkaTestcontainersTest -Dexcluded.test.groups=
```

## Configuration Reference (`application.yaml`)

| Property | Meaning | Default |
|---|---|---|
| `server.port` | HTTP port | `8081` |
| `spring.kafka.bootstrap-servers` | Kafka broker address | `localhost:9092` (env override: `KAFKA_BOOTSTRAP_SERVERS`) |
| `app.kafka.topics.bank-transfer-payment-events` | Topic name | `bank-transfer-payment-events` |
| `app.booking.cancellation.check-interval-ms` | How often the cancellation scheduler runs | `300000` (5 min) |
| `app.booking.cancellation.window-hours` | Cancellation/rejection deadline before rental start | `48` |
| `credit-card-validation-service.base-url` | Base URL for the external validation service | `http://localhost:9090/host/credit-card-payment-api` |
