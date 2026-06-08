# Event Ticket Booking Platform

A production-grade backend REST API for booking event tickets, built with **Spring Boot**.
The headline feature is **safe concurrent seat booking that never oversells**, even under
heavy simultaneous load — backed by a real payment lifecycle with idempotent charging and
a database-owned pricing model.

## Tech Stack

| Concern         | Choice                                   |
|-----------------|------------------------------------------|
| Language        | Java 17 (built/run on JDK 21)            |
| Framework       | Spring Boot 3.3                          |
| Persistence     | PostgreSQL + Spring Data JPA             |
| Migrations      | Flyway                                   |
| Cache / Locking | Redis                                    |
| Auth            | Spring Security + JWT (HS256)            |
| Docs            | OpenAPI / Swagger UI                     |
| Testing         | JUnit 5, Mockito, Testcontainers         |
| Build / Run     | Maven (wrapper included), Docker Compose  |
| Web UI          | Zero-build vanilla SPA (served from `static/`) |

## Quick start

### Option A — everything in Docker
```bash
docker compose up --build
```
Web UI: http://localhost:8080 · Swagger UI: http://localhost:8080/swagger-ui.html

> **Try it in the browser:** open the web UI, register an account, pick seats and run the
> full hold → pay → confirm flow with live prices and a 5-minute hold countdown. The
> payment dialog lets you trigger the decline (`FAIL_CARD`) and timeout (`TIMEOUT_CARD`)
> paths to see seats *not* get stuck. An admin account (for creating events) is seeded
> from `ADMIN_*` env vars — see `.env.example`.

### Option B — backing services in Docker, app from IDE/jar
```bash
docker compose up -d postgres redis
./mvnw spring-boot:run                       # or run TicketingApplication from your IDE
# Windows note: port 8080 may be taken (e.g. local Jenkins) — use SERVER_PORT=8081
```

> The project ships the Maven Wrapper (`mvnw` / `mvnw.cmd`); a global Maven install is
> not required. The first invocation downloads Maven 3.9.9 automatically.

## Architecture

Strict layering — controllers never touch repositories; entities never cross the API
boundary (DTOs only); constructor injection; one `@RestControllerAdvice` → one `ApiError`
JSON shape.

```
                 HTTP (JSON, JWT bearer)
                         │
             ┌───────────▼───────────┐   @RestControllerAdvice → ApiError
             │      Controllers      │   Auth · Events · Booking
             └───────────┬───────────┘
                         │ DTOs in/out
   ┌──────────────────────▼───────────────────────────────┐
   │                     Services                          │
   │  AuthService   EventService (pricing + seat gen)      │
   │  BookingService ── orchestrates hold / pay / cancel   │
   │     │  (charge happens OUTSIDE any DB txn/lock)        │
   │     ▼ (proxy boundary = fresh txn per call)            │
   │  SeatReservationService   (pessimistic / optimistic)  │
   │  PaymentService           (idempotent pay lifecycle)  │
   │  HoldReleaseService       (@Scheduled expiry sweep)   │
   │  RateLimiterService · RedisLockService · CacheInvalidator
   └───────┬───────────────────────────────┬───────────────┘
           │ Spring Data JPA                │ Redis (locks · limits · cache)
   ┌───────▼────────┐              ┌────────▼────────┐
   │   PostgreSQL   │              │      Redis      │
   │ Flyway schema  │              │ cached reads    │
   │ row locks +    │              │ rate limits     │
   │ @Version + FKs │              │ optional locks  │
   └────────────────┘              └─────────────────┘
```

## Concurrency design (the core)

The system **never oversells**, proven under concurrent load. The hold path supports two
interchangeable strategies (`app.booking.locking-strategy`):

- **Pessimistic (default)** — `SELECT … FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`), rows
  locked in deterministic `id` order (deadlock-free for multi-seat holds), 3s lock
  timeout. Exactly one writer wins; the rest see the seat taken — no wasted retries.
- **Optimistic** — JPA `@Version` on `Seat`; the version-checked `UPDATE` fails the loser
  at flush, retried with jittered backoff in the service. Readers never block.
- **Optional Redis distributed lock** (`app.booking.redis-lock-enabled=true`) — a per-seat
  `SET NX PX` lock (Lua compare-and-del release) in front of the DB to shed contention at
  the app tier across instances.

**Transactions:** `READ_COMMITTED`. **The external payment call happens outside any DB
transaction or lock** — validate, charge over the "network", then confirm in a short
locked transaction. A declined/timed-out payment leaves the booking `PENDING` (retryable),
so seats are never stuck; the scheduled job releases abandoned holds.

**Proven:** unit + Testcontainers tests fire 24 concurrent threads at one seat and assert
exactly one winner (both strategies); a live HTTP run showed 30 concurrent holds on one
seat → exactly 1×201 + 29×409.

## Payment lifecycle & idempotency

Payments are a first-class, auditable model (`payments` table) with a strict lifecycle:
`INITIATED → APPROVED | DECLINED | FAILED`.

The `POST /api/v1/bookings/{id}/pay` request carries a client-generated **`idempotencyKey`**.
The flow is three steps around the external charge:

1. **claim** (short txn) — insert an `INITIATED` payment row. The unique constraint
   `payments(booking_id, idempotency_key)` is the serialization point: a repeated request
   with the same key replays the prior result, and two *concurrent* same-key requests race
   on the INSERT — exactly one wins and charges; the loser replays the winner's result.
2. **charge** — call the gateway with the booking's **frozen amount** (never a hardcoded
   constant), outside any DB transaction or lock.
3. **finalize** (short txn) — persist the gateway outcome and, on approval, confirm the
   booking's seats **exactly once** (re-locked + re-validated in `SeatReservationService`).

Guarantees and edge cases:
- **Duplicate `/pay` with the same key never double-charges** — it returns the first result.
- **Concurrent `/pay` with the same key confirms at most once** (DB unique constraint +
  exactly-once confirm).
- **Declined payments are persisted** (`DECLINED` with a failure reason) before the API
  surfaces `402` — finalize never rolls back the record it must keep.
- **Hold expired after approval** is handled safely: the booking is no longer confirmable,
  so the payment is marked `FAILED` with a refund-required reason instead of confirming.

The pay response includes a `payment` block (`status`, `providerReference`, `failureReason`)
plus the booking's `amountMinor` / `currency`.

## Pricing model

Pricing lives in the **backend/database**, not the frontend. Money is stored in **minor
units** (paise/cents) as `long` to avoid floating-point money bugs.

- **Events** carry `currency`, `basePriceMinor` (default seat price), and
  `convenienceFeeMinor` (flat per-seat fee).
- **Seats** may carry an optional `tierName` + `priceMinor`; a null seat price resolves to
  the event base price (`Seat.effectivePriceMinor()`). On create, optional `tiers` assign
  name+price to the front rows of a grid event, front-to-back.
- **Bookings freeze the payable amount at hold time** (`amountMinor`, `feeMinor`,
  `currency`), so the price cannot drift between checkout and payment. The gateway charges
  this stored amount.
- The **booking response** exposes `currency`, `amountMinor`, `feeMinor`, and a per-seat
  price breakdown; the **seat response** exposes `tierName` + `priceMinor`. The SPA renders
  all prices from these API fields.

Defaults keep the original create-event flow working: omit pricing and you get
`currency=INR`, base/fee `0`.

## Expired-hold release job

A `@Scheduled` job (`HoldExpiryScheduler` → `HoldReleaseService`) drains lapsed holds back
to `AVAILABLE` and marks their bookings `EXPIRED`. It is **multi-instance-safe** (selects
expired `HELD` seats with `FOR UPDATE SKIP LOCKED`, so instances take disjoint batches and
a seat mid-confirm is skipped), **idempotent** (only matches still-`HELD`, past-expiry
rows; re-checks under lock), and **bounded** (fixed batch size; loops until drained).
Configurable via `app.booking.release-*`.

## Caching & rate limiting

**Redis cache** (`CacheConfig`) on the hot read paths — event detail (`events`, 60s),
search listings (`eventSearch`, 30s), seat map (`eventSeats`, 20s). Invalidation is correct
on *every* availability change: event writes evict declaratively; booking hold/confirm/
cancel and the expiry job evict via `CacheInvalidator` deferred to **after-commit**.
(Cached DTOs are Java records, so Jackson default typing uses `EVERYTHING` to round-trip the
`@class` id — see `CacheConfig`.)

**Per-user rate limiting** (`RateLimiterService` + interceptor) on `POST /bookings/hold`:
a Redis fixed-window counter via an atomic Lua `INCR`+`EXPIRE`, shared across instances.
Exceeding it returns `429` with a `Retry-After` header. Configurable via
`app.booking.rate-limit.*`.

## Security

- **Stateless JWT** (HS256). `JwtAuthenticationFilter` authenticates each request from the
  bearer token; method-level `@PreAuthorize("hasRole('ADMIN')")` guards admin writes.
- **No secrets in code or UI.** The bootstrap admin password comes from `ADMIN_PASSWORD`
  (env), and the login screen no longer displays demo credentials. `JWT_SECRET` and all DB/
  Redis creds are environment-based; the dev defaults must be overridden in production.
- **Security headers** (`SecurityConfig`): a Content-Security-Policy compatible with the
  SPA and Swagger UI, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  and frame protections (`X-Frame-Options: DENY` + CSP `frame-ancestors 'none'`).
- **JWT-in-localStorage tradeoff (documented):** the SPA stores the JWT in `localStorage`,
  which is simple and XSS-exposed. The CSP reduces XSS risk, and tokens are short-lived
  (60 min default). For higher assurance, move the token to an `HttpOnly`, `Secure`,
  `SameSite` cookie with CSRF protection, or adopt a BFF pattern — a deliberate
  simplicity-vs-hardening tradeoff for this portfolio build.

## API

| Method | Path                          | Auth   | Description |
|--------|-------------------------------|--------|-------------|
| POST   | `/api/v1/auth/register`       | public | Register a USER, returns a JWT |
| POST   | `/api/v1/auth/login`          | public | Log in, returns a JWT |
| GET    | `/api/v1/events`              | public | Browse/search events (paginated): `keyword`, `venue`, `fromDate`, `page`, `size`, `sort` |
| GET    | `/api/v1/events/{id}`         | public | Event detail + live availability + pricing |
| GET    | `/api/v1/events/{id}/seats`   | public | Seats (optional `?status=`), each with tier + price |
| POST   | `/api/v1/events`              | ADMIN  | Create event + seats + pricing/tiers |
| PUT    | `/api/v1/events/{id}`         | ADMIN  | Update event metadata (seating immutable) |
| DELETE | `/api/v1/events/{id}`         | ADMIN  | Delete event (409 if it has bookings) |
| POST   | `/api/v1/bookings/hold`       | USER   | Hold seats → PENDING booking with frozen amount; **rate-limited** |
| POST   | `/api/v1/bookings/{id}/pay`   | USER   | Pay (simulated, **idempotent** via `idempotencyKey`) → CONFIRMED |
| POST   | `/api/v1/bookings/{id}/cancel`| USER   | Cancel a pending hold, release seats |
| GET    | `/api/v1/bookings/{id}`       | USER   | Get one of my bookings |
| GET    | `/api/v1/bookings`            | USER   | List my bookings (paginated) |
| GET    | `/swagger-ui.html`            | public | Interactive API docs |
| GET    | `/actuator/health`            | public | Liveness/readiness |

**Example — hold then pay (idempotent):**
```bash
# 1) Hold seats (response includes amountMinor/currency/feeMinor + seat breakdown)
curl -X POST http://localhost:8080/api/v1/bookings/hold \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  --data-raw '{"eventId":1,"seatIds":[1,2,3]}'

# 2) Pay — repeat with the SAME idempotencyKey and it returns the first result, never re-charges
curl -X POST http://localhost:8080/api/v1/bookings/1/pay \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  --data-raw '{"paymentMethod":"CARD","idempotencyKey":"a1b2c3d4-..."}'
```

## Testing

```bash
./mvnw test       # fast: Mockito unit tests only (no Docker)
./mvnw verify     # unit + Testcontainers integration/concurrency tests (needs Docker)
```

The suite is split so the inner loop stays fast (Surefire runs `*Test`) while Docker-backed
tests run under Failsafe (`*IT`) on `verify` and in CI:

- **Unit (Mockito)** — service logic in isolation, including the payment additions:
  `PaymentServiceTest` (declined persisted, exactly-once confirm, expired-hold-after-approval,
  replay of approved/declined attempts, frozen-amount claim), `PaymentIdempotencyConcurrencyTest`
  (24 concurrent same-key `/pay` → charged once, confirmed once, one row),
  `BookingServiceTest` (claim → charge frozen amount → finalize; duplicate/raced replays),
  `EventServiceTest` (tier pricing), plus Auth/Event/HoldRelease/Jwt tests.
- **Integration (Testcontainers)** — real Postgres + Redis; Flyway runs the production
  migrations and Hibernate validates the mapping against that schema.
- **Concurrency oversell-proof** (`SeatBookingConcurrencyIT`) — 24 threads / 1 seat → exactly
  one winner, for both pessimistic and optimistic strategies.

### Local Testcontainers troubleshooting (Windows)

`./mvnw verify` needs a running Docker daemon. Two known **local-only** gotchas (neither is
a project test failure — CI on Linux is unaffected):

- **Invalid `PATH` entry / `search-ms:` URI** — the most common local blocker. A malformed
  entry such as one beginning with `search-ms:` (a leftover Windows Explorer *saved search*
  URI that got pasted into `PATH`) makes tools that enumerate `PATH` — including the
  Docker/Testcontainers executable probe — choke, so `verify` fails before any container
  starts. **This is a local environment problem, not a project bug.** The project never
  modifies your global environment. Fix it yourself:

  1. **Inspect** your `PATH` for suspicious entries (read-only, changes nothing):
     ```powershell
     $env:Path -split ';' | Where-Object { $_ -notmatch '^[A-Za-z]:\\' -and $_ -ne '' }
     ```
     Anything printed (e.g. a `search-ms:...` line) is malformed.
  2. **Remove it** via the GUI: press `Win`, type *“Edit environment variables for your
     account”*, open **Environment Variables…**, select **Path** → **Edit…**, delete the
     bad row, click **OK** on all dialogs.
  3. **Open a new terminal** (so it picks up the corrected `PATH`) and re-run:
     ```powershell
     .\mvnw.cmd verify
     ```
  > A read-only helper, `scripts/check-path.ps1`, prints suspicious entries for you. It
  > **does not change anything** — it only reports.

- **Docker Desktop API handshake:** on very new Docker Desktop builds the bundled
  docker-java client may need `DOCKER_API_VERSION` pinned to the daemon's min API; the
  Failsafe config already sets `DOCKER_API_VERSION=1.44` for the test JVM.

CI always runs `./mvnw -B -ntp verify` on `ubuntu-latest`, where Docker is present and these
local issues do not occur.

## Deployment

Single self-contained jar configured entirely via environment variables (see
`.env.example`), so the same artifact runs locally, in Compose, and on a PaaS.

```bash
docker build -t ticketing .
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://<host>:5432/ticketing -e DB_USERNAME=... -e DB_PASSWORD=... \
  -e REDIS_HOST=<host> -e REDIS_PORT=6379 \
  -e JWT_SECRET=<32+ char secret> -e ADMIN_PASSWORD=<strong password> \
  ticketing
```

**Free-tier (Railway / Render):** provision PostgreSQL + Redis add-ons, deploy this repo
(both detect the multi-stage `Dockerfile`), set the env vars above, and point the health
check at `/actuator/health`. Flyway runs the migrations automatically on first boot.

> **Production checklist:** override `JWT_SECRET` and `ADMIN_PASSWORD`; keep
> `ddl-auto=validate` (Flyway owns the schema); size `DB_POOL_SIZE` with the pessimistic
> lock path in mind (each in-flight hold holds a connection for its transaction).

## Configuration

All settings are environment-overridable; see [`.env.example`](.env.example). Pricing is set
per-event at creation time (currency / base price / convenience fee / optional tiers) — there
are no global price env vars. Default local port is `8080`; set `SERVER_PORT=8081` if another
service (e.g. a local Jenkins) owns 8080.

## Database migrations

Forward-only Flyway migrations under `src/main/resources/db/migration`:
- `V1__baseline.sql` — Flyway baseline + `pgcrypto`.
- `V2__core_domain.sql` — users, events, seats, bookings + indexes.
- `V3__payments_and_pricing.sql` — pricing columns on events/seats/bookings, the `payments`
  table with the `(booking_id, idempotency_key)` unique constraint + lookup indexes, and an
  index on `seats(booking_id)`.
- `V4__payment_serialization.sql` — partial unique index
  `uq_payments_one_initiated_per_booking ON payments(booking_id) WHERE status='INITIATED'`,
  the DB backstop guaranteeing at most one in-flight payment attempt per booking.
