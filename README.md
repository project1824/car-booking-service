# Car Booking Service

A Spring Boot microservice for **Velocity Motors** that manages car rental bookings, built as part of a take-home assignment. It confirms bookings based on payment method (digital wallet/cash, credit card, or bank transfer), integrates with an external credit-card validation service, consumes bank-transfer payment events from Kafka, and automatically cancels unpaid bank-transfer bookings 48 hours before rental start.

## Tech Stack

- **Java 21**, **Spring Boot 4.1.1** (Spring Framework 7 / Jackson 3)
- **Spring Web MVC** — REST API
- **Spring Data JPA** + **PostgreSQL** — persistence
- **Spring for Apache Kafka** — consumes `bank-transfer-payment-events`
- **Spring WebFlux's `WebClient`** — calls the external credit-card-validation-service (no reactive server is run; `WebClient` is used purely as an HTTP client)
- **Spring Boot Actuator** — health, liveness, and readiness endpoints for Kubernetes
- **Spring Framework 7 native API versioning** — see API Versioning below
- **Lombok** — reduces entity boilerplate (including `@Slf4j` for every class that logs)
- **Maven** — build
- **Docker** — multi-stage build for deployment

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
config/         ClockConfig, WebClientConfig, WebConfig (API versioning)
web/            CorrelationIdFilter               — MDC request-id tagging
logging/        MethodTraceLoggingAspect (AOP), MdcContext
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
| Unrecognized `X-API-Version` (see API Versioning below) | 400 |
| Missing/invalid/expired bearer token (see Authentication below) | 401 |
| Anything unexpected | 500 |

### API Versioning

Uses Spring Framework 7's native API versioning support (`WebMvcConfigurer.configureApiVersioning`), not a hand-rolled URL-prefix or header scheme. Clients specify a version via the `X-API-Version` header; the current (and only) version is `1.0`, which is also the configured default — so omitting the header entirely (as every existing client and test does) still resolves correctly. This is purely additive groundwork: a `2.0` handler can be added to `BookingController` later without breaking whatever is still calling the `1.0` contract.

```bash
curl -X POST http://localhost:8082/booking -H "X-API-Version: 1.0" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{...}'
```

An unrecognized version (e.g. `X-API-Version: 2.0`, which doesn't exist yet) returns a clean `400` with a clear message, rather than a generic `500` — handled by a dedicated `ResponseStatusException` mapping in `GlobalExceptionHandler`, since Spring's own `InvalidApiVersionException` already carries the correct status and just needs to not be swallowed by the catch-all handler.

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

## Authentication

`POST /booking` requires a bearer JWT; `POST /auth/login` and everything under `/actuator/*` don't.

**This is a self-issued JWT setup**: `car-booking-service` is both the issuer and the sole validator of its own tokens (HMAC-signed, `NimbusJwtEncoder`/`NimbusJwtDecoder` from Spring Security, no external library needed). There's no separate Identity Provider and no real user store — a single demo account (`app.security.demo-user.*`, defaults `demo`/`demo-password`) stands in for one, which is the honest scope for this assignment. This was a deliberate middle ground between two other options considered: a bare shared-secret API key (simpler, but doesn't demonstrate real JWT mechanics - signing, expiry, claims) and standing up a real external Identity Provider like Keycloak (closer to a genuine production setup, but disproportionate infrastructure for a take-home).

```bash
TOKEN=$(curl -s -X POST http://localhost:8082/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo-password"}' | jq -r .token)

curl -X POST http://localhost:8082/booking \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"customerName":"Jane Doe","vehicleId":"VEH12345","rentalStartDate":"2026-09-20","rentalEndDate":"2026-09-22","vehicleCategory":"SUV","paymentMode":"CASH"}'
```

**How this relates to a real multi-service production setup** (the honest gap between this and "real"): in a fleet of many services, only *one* place — a central Identity Provider (Keycloak, Auth0, Okta, Cognito) — actually authenticates users (password/MFA check, token issuance). Every other service that exposes an API is a "resource server": it doesn't authenticate anyone, it just validates an already-issued token's signature against the IdP's published public key (a JWKS endpoint) and trusts the claims inside — a few lines of standard config, not custom-built per service, and exactly the role `car-booking-service` plays here except it's *also* acting as its own tiny IdP for demo purposes. Swapping in a real external IdP later means replacing the `jwtEncoder`/`jwtDecoder`/`userDetailsService` beans in `SecurityConfig` with Boot's OAuth2-resource-server auto-configuration pointed at the IdP's `issuer-uri` — the rest of the security filter chain (`SecurityFilterChain`, `AuthController`) doesn't need to change.

**Deliberate simplifications, stated plainly:**
- `/actuator/**` is fully open, not just the liveness/readiness paths that Kubernetes' kubelet and Prometheus actually need unauthenticated (neither sends credentials). A stricter real deployment would move actuator to a separate management port instead of relaxing the same filter chain.
- No refresh tokens, no fine-grained roles/permissions beyond "authenticated or not," no token revocation - a lost/stolen token stays valid until it naturally expires (`app.security.jwt.expiration-minutes`, default 60).
- The JWT secret and demo password are dev-only defaults committed to `application.yaml` for local runs (`JWT_SECRET`/`DEMO_USER_PASSWORD` env vars override them) - `k8s/secret-example.yaml` shows how they'd be injected as real Kubernetes secrets instead, same pattern as the database password.

## Health Checks (`/actuator/*`)

- `GET /actuator/health` — overall status, aggregating every registered health indicator (DB, Kafka, disk space, etc.)
- `GET /actuator/health/liveness` — **process-health only**, no external dependency checks. Wired to a Kubernetes `livenessProbe`. Kept deliberately minimal: liveness failures cause Kubernetes to *restart* the pod, and restarting a healthy process won't fix a downed database or broker — mixing dependency checks into liveness just causes pointless restart churn during an outage.
- `GET /actuator/health/readiness` — includes the app's own readiness state **plus the database check**, but *not* Kafka. Readiness failures pull the pod out of the Service's load-balancer rotation. A downed database means the app genuinely can't serve any request, so that should fail readiness. A downed Kafka broker only affects the asynchronous bank-transfer confirmation path — `POST /booking` for `CASH`/`DIGITAL_WALLET`/`CREDIT_CARD` still works fine — so it deliberately isn't wired into readiness, to avoid taking a still-mostly-functional pod out of rotation.

## Observability (Prometheus)

- `GET /actuator/prometheus` — Prometheus-format scrape endpoint (`management.endpoints.web.exposure.include` includes `prometheus`). Exposes Micrometer's usual auto-instrumented metrics (JVM memory/GC/threads, `http_server_requests` with percentile histograms enabled, HikariCP connection-pool stats, Kafka consumer/producer client metrics, disk space, etc.) plus five custom business counters registered via an injected `MeterRegistry`:

  | Metric | Tags | Incremented when |
  |---|---|---|
  | `bookings_total` | `paymentMode`, `status` | Every booking is persisted (`BookingService`) |
  | `bookings_autocancelled_total` | — | The 48h scheduler cancels a `PENDING_PAYMENT` bank-transfer booking |
  | `credit_card_validation_calls_total` | `outcome` (`success`/`error`) | Every call to the external credit-card-validation-service, regardless of APPROVED/REJECTED |
  | `credit_card_payment_result_total` | `result` (`approved`/`declined`) | A credit-card validation response is interpreted into a booking outcome |
  | `bank_transfer_events_total` | `outcome` (`confirmed`/`no_matching_booking`/`malformed`/`unparseable`) | Every Kafka `bank-transfer-payment-events` message is processed |

  Every metric also carries an `application=car-booking-service` tag (`management.metrics.tags.application`), so multiple services can share one Prometheus/Grafana instance without name collisions.

- **Naming gotcha found during verification, not assumed:** the business counter for booking creation was originally named `bookings_created_total`. Live-checking `/actuator/prometheus` showed it was actually exported as `bookings_total` — Prometheus/OpenMetrics treats a trailing `_created` as a reserved suffix (used for a counter's creation-timestamp series), so Micrometer's Prometheus naming convention silently strips it before re-appending `_total`. Renamed the metric to `bookings_total` directly so the code, the tests, and the actual scraped output all agree — a name that only *looks* right in a `SimpleMeterRegistry`-based unit test can still be silently rewritten by the real Prometheus registry.
- In Kubernetes, the pod template in [`k8s/deployment.yaml`](k8s/deployment.yaml) carries `prometheus.io/scrape`, `prometheus.io/port`, and `prometheus.io/path` annotations for annotation-based Prometheus service discovery.

## Resilience (Retry & Circuit Breaker)

The only synchronous external HTTP dependency in the booking path is the credit-card-validation-service call in [`CreditCardValidationClientImpl`](src/main/java/com/velocitymotors/carbooking/client/CreditCardValidationClientImpl.java). It's wrapped with Resilience4j (`resilience4j-spring-boot4`), configured under `resilience4j.retry.instances.creditCardValidation` / `resilience4j.circuitbreaker.instances.creditCardValidation` in `application.yaml`:

- **Retry** (max 3 attempts, 300ms wait) - kept deliberately small. For `CREDIT_CARD` bookings this call happens *inside* an open DB transaction while holding the vehicle/payment-reference advisory lock (see decision below), so every retry attempt directly extends how long that lock is held - a generous retry policy would turn a slow upstream into DB lock contention.
- **Circuit breaker** (count-based, window of 10, opens at ≥50% failure rate over ≥5 calls, 30s open state) - once the upstream is clearly unhealthy, further calls fail immediately (`CallNotPermittedException`, mapped to the same 502 as any other unavailability) instead of piling up threads waiting on a struggling dependency.
- **A transient failure (network error, upstream 5xx) is treated differently from a definitive one (upstream 4xx)** via `CreditCardTransientFailurePredicate`: a 4xx fails on the first attempt (retrying the same bad request changes nothing) and doesn't count against the circuit breaker's failure rate (a bad request on our side, or a genuine "payment not found," isn't evidence the upstream itself is unhealthy). Only network failures and 5xx responses are retried and count as circuit-breaker failures.
- Retry and circuit-breaker state is visible at `GET /actuator/circuitbreakers` and as Prometheus metrics (`resilience4j_circuitbreaker_*`, `resilience4j_retry_*`) - both verified live: a real run against an unreachable upstream showed exactly 3 attempts ~300ms apart, the circuit opening after 5 buffered failures, and the next call failing in ~76ms instead of the usual ~600-900ms.
- **Decorator order matters**: retry wraps the circuit breaker (not the reverse), so once the breaker opens partway through a retry sequence, the remaining attempts in that sequence fail fast instead of still hitting the network - the standard Resilience4j composition for this scenario.

**Considered and rejected: OpenFeign as the client.** Two variants were evaluated for this same call: a hand-written `@FeignClient` interface (Spring Cloud OpenFeign) compiled and ran cleanly against this stack, but was set aside since it doesn't add real value for a single external, non-load-balanced third-party endpoint - Feign's main advantage is declarative load-balancing across sibling microservice instances via service discovery, which doesn't apply here. Generating the client from `Assignment03_creditcardpayment_api.yaml` via `openapi-generator-maven-plugin`'s `feign` library target was also tried and confirmed (via a real build attempt) to hard-fail: it hardcodes both Jackson 2 (`com.fasterxml.jackson.*`, incompatible with this project's Jackson 3-only classpath) and the pre-Jakarta `javax.annotation` namespace. `WebClient` was kept as-is.

## Database Migrations (Flyway)

Schema is owned by Flyway, not Hibernate. `spring.jpa.hibernate.ddl-auto` is `validate` - on startup, Hibernate checks its entity mappings against whatever Flyway has already created and fails fast on drift, instead of silently auto-altering the schema the way `ddl-auto: update` did before.

- Migrations live in [`src/main/resources/db/migration`](src/main/resources/db/migration); `V1__create_bookings_table.sql` creates the `bookings` table plus three indexes matching the repository's actual query patterns (`vehicle_id, status` for the double-booking check, `payment_reference, status` for the payment-reference-reuse check, `payment_mode, status` for the cancellation scheduler's lookup) - none of these existed under Hibernate's auto-DDL, since it never generates indexes beyond the primary key.
- The `Booking` entity now carries explicit `@Column(nullable = false, length = ...)` annotations matching the migration, tightening constraints Hibernate's auto-DDL never actually enforced (every field but `paymentReference` is genuinely required).
- **Found a real Boot 4.1.1 gap while wiring this up**: adding just `flyway-core` + `flyway-database-postgresql` compiled fine but Flyway silently never ran (no log output, no `flyway_schema_history` table) - confirmed via `unzip -l` on the actual jar that `spring-boot-autoconfigure:4.1.1` no longer bundles Flyway's autoconfiguration at all. Like several other Boot 4 modules already documented above, it moved into its own dedicated `spring-boot-starter-flyway` artifact. Adding that starter (alongside `flyway-database-postgresql`, which still isn't bundled by the starter) fixed it - verified via a real app boot showing Flyway's migration log output and the resulting schema.
- Local dev/Testcontainers Postgres instances start empty, so migrations always run from `V1` on a fresh database - no separate baseline step needed for this project.

## Docker & Kubernetes

Build and run the container directly:
```bash
docker build -t car-booking-service:latest .
docker run -p 8082:8082 car-booking-service:latest
```
The `Dockerfile` is a multi-stage build (JDK 21 to compile, JRE 21 to run) so the resulting image doesn't carry a full JDK or the Maven build cache, and runs as a non-root user.

**Important when containerized or deployed to Kubernetes:** `KAFKA_BOOTSTRAP_SERVERS` and `CREDIT_CARD_SERVICE_BASE_URL` both default to `localhost:...`, which only resolves correctly for a non-containerized local run — inside a container, `localhost` refers to the container itself, not the host or a sibling service. Override both env vars to point at the real Kafka broker and credit-card service addresses in your environment.

Example manifests are in [`k8s/`](k8s/) (`deployment.yaml`, `service.yaml`, `secret-example.yaml`), wiring the liveness/readiness endpoints above into real Kubernetes probes. Since state lives in PostgreSQL rather than in-process, the deployment runs `replicas: 2` to demonstrate real horizontal scaling.

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
14. **An additional Testcontainers-based Kafka integration test exists** (`BankTransferKafkaTestcontainersTest`), tagged and excluded from the default build so it doesn't add to the Docker dependency below beyond what's already required — it verifies the same scenario against a real Kafka broker instead of the embedded one.
15. **The service uses PostgreSQL (via Testcontainers) for every `@SpringBootTest`, not H2.** This was a deliberate choice for full test/production parity over the alternative (H2 for speed, real Postgres only in production). The consequence, stated plainly: the *entire* test suite now requires Docker to run, not just the opt-in Testcontainers Kafka test — `mvn clean verify` will fail without Docker available. If building in a Docker-less environment, `mvn clean package -DskipTests` still compiles and packages the application; running the real test suite requires Docker to be running, same as `docker compose up` already does for the local Kafka/Postgres dev setup.
16. **API versioning uses a header (`X-API-Version`), not a URL path prefix (`/v1/booking`).** Nothing in the assignment asks for versioning at all — this is added as a production-readiness demonstration. A header keeps the URL stable across versions and doesn't disturb the existing `/booking` path any of the 42 tests or the assignment's own examples reference; a path-based scheme would have meant renaming the endpoint everywhere for no functional benefit. The default version (`1.0`) means this is purely additive — no existing caller needs to change anything.
17. **Authentication is a self-issued JWT, not a real external Identity Provider.** Nothing in the assignment asks for authentication either — added as a further production-readiness demonstration. Two alternatives were weighed and set aside: a bare API key (simpler, but doesn't demonstrate real JWT mechanics) and a real Keycloak-backed setup (closer to genuine production, but disproportionate infrastructure for a take-home). See the dedicated Authentication section above for the full reasoning and how this would evolve into a real multi-service setup.

## Running Locally

**Prerequisites:** JDK 21, Maven (or use the included `./mvnw`), Docker (for PostgreSQL and Kafka).

```bash
docker compose up -d
./mvnw spring-boot:run
```
The app starts on **port 8082** (`server.port` in `application.yaml`) against the PostgreSQL instance started by `docker-compose.yml`. Both PostgreSQL and Kafka connection settings are fully overridable via environment variables — see Configuration Reference below.

### Running Kafka locally (to exercise the bank-transfer flow)

`docker compose up -d` (above) already starts both PostgreSQL and a single-node Kafka broker (KRaft mode, no Zookeeper container needed) on `localhost:9092`. Create the Kafka topic once if it doesn't already exist:
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

**Troubleshooting: `password authentication failed for user "car_booking"` on local run.** If you have PostgreSQL already installed and running natively on your machine (outside Docker), it's likely already bound to the default port 5432. `docker-compose.yml` deliberately maps this project's Postgres container to host port **5433** (not 5432) for exactly this reason, matched by `application.yaml`'s `DB_PORT` default — but if you've overridden `DB_PORT` back to `5432` for any reason and see this error, that's almost certainly a collision with a pre-existing local Postgres instance, not a real credentials problem. Confirm with `Get-Process -Name postgres` (Windows) or `lsof -i :5432` (macOS/Linux) before assuming the container's config is wrong.

## Testing

```bash
mvn clean verify
```
**Requires Docker to be running** — every `@SpringBootTest` in this suite uses a real PostgreSQL instance via Testcontainers (see Assumptions #15). Covers:

- **Unit** (no Spring context, no Docker): `BookingIdGeneratorTest`, `DigitalWalletPaymentStrategyTest`, `BankTransferPaymentStrategyTest`, `CreditCardPaymentStrategyTest`, `BookingServiceTest`, `BankTransferPaymentEventListenerTest`, `BookingCancellationSchedulerTest` (deterministic 48h-boundary testing via an injectable `Clock` — no real-time waiting needed), `JwtServiceTest` (token claims/expiry, rejects a token signed with a different key, rejects a tampered token)
- **HTTP layer** (no Docker): `BookingControllerTest` (MockMvc against the real `SecurityConfig`, `@WithMockUser` standing in for a bearer token — success path, Bean Validation wiring, and every exception→status mapping through `GlobalExceptionHandler`), `AuthControllerTest` (real login flow against the real `AuthenticationManager`/`UserDetailsService` — correct credentials, wrong password, unknown user, blank username)
- **External client** (no Docker): `CreditCardValidationClientImplTest` (OkHttp `MockWebServer` — verifies every response shape the OpenAPI spec documents)
- **True end-to-end** (`@SpringBootTest`, `WebEnvironment.RANDOM_PORT`, real `TestRestTemplate` calls over a real embedded server, real PostgreSQL via Testcontainers — no mocks anywhere in the request path except the external credit-card service):
  - `BookingCreationIntegrationTest` — all four payment-mode outcomes via a real `POST /booking`, each verified against the real database row it produced, plus a full round trip where a booking created via the real endpoint is then confirmed by a real Kafka event
  - `BookingSchedulerIntegrationTest` — a booking created via the real endpoint, then auto-cancelled by the real `BookingCancellationScheduler` bean (time is fast-forwarded via an isolated, per-test controllable `Clock` rather than waiting real hours)
  - `AuthenticationIntegrationTest` — proves `/booking` actually rejects a missing/garbage bearer token over real HTTP (not just that a directly-generated test token happens to work), plus a full real `/auth/login` → `/booking` round trip
- **Kafka integration**: `BankTransferKafkaIntegrationTest` (`@EmbeddedKafka` — full Spring context, real JSON over an in-process broker, real PostgreSQL write)

All `@SpringBootTest` classes share one PostgreSQL Testcontainer (via `AbstractPostgresIntegrationTest`, Testcontainers' singleton-container pattern) — started once per test run, not once per test class.

**Optional — Testcontainers-based Kafka integration test** (`BankTransferKafkaTestcontainersTest`), which runs the same Kafka scenario against a real broker in Docker instead of the embedded one. Excluded from the default build; run explicitly:
```bash
mvn test -Dtest=BankTransferKafkaTestcontainersTest -Dexcluded.test.groups=
```

## Configuration Reference (`application.yaml`)

| Property | Meaning | Default |
|---|---|---|
| `server.port` | HTTP port | `8082` |
| `spring.datasource.url` | PostgreSQL connection | built from `DB_HOST` (`127.0.0.1`), `DB_PORT` (`5433` — deliberately not 5432, see Troubleshooting below), `DB_NAME` (`car_booking`) |
| `X-API-Version` (request header, not an `application.yaml` property) | API version selector | `1.0` (also the default if omitted) — see API Versioning above |
| `spring.datasource.username` / `.password` | PostgreSQL credentials | env override: `DB_USERNAME` / `DB_PASSWORD` (both default to `car_booking`, matching `docker-compose.yml`) |
| `spring.kafka.bootstrap-servers` | Kafka broker address | `localhost:9092` (env override: `KAFKA_BOOTSTRAP_SERVERS`) |
| `app.kafka.topics.bank-transfer-payment-events` | Topic name | `bank-transfer-payment-events` |
| `app.booking.cancellation.check-interval-ms` | How often the cancellation scheduler runs | `300000` (5 min) |
| `app.booking.cancellation.window-hours` | Cancellation/rejection deadline before rental start | `48` |
| `credit-card-validation-service.base-url` | Base URL for the external validation service | `http://localhost:9090/host/credit-card-payment-api` (env override: `CREDIT_CARD_SERVICE_BASE_URL`) |
| `management.endpoint.health.group.readiness.include` | Indicators contributing to the readiness probe | `readinessState,db` (Kafka deliberately excluded — see Health Checks) |
| `spring.jpa.hibernate.ddl-auto` | Schema management mode | `validate` — Flyway owns the schema, Hibernate only checks its mappings match (see Database Migrations) |
| `spring.flyway.locations` | Where Flyway looks for migration scripts | `classpath:db/migration` (the default — set explicitly for documentation) |
| `app.security.jwt.secret` | Base64 HMAC key signing/validating this service's own JWTs | dev-only default in `application.yaml` (env override: `JWT_SECRET`) — see Authentication |
| `app.security.jwt.expiration-minutes` | How long an issued token stays valid | `60` (env override: `JWT_EXPIRATION_MINUTES`) |
| `app.security.demo-user.username` / `.password` | The one demo account standing in for a real user store | `demo` / `demo-password` (env override: `DEMO_USER_USERNAME` / `DEMO_USER_PASSWORD`) |
