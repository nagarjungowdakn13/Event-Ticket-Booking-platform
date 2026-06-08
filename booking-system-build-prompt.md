# Build Prompt — Event Ticket Booking Platform (Spring Boot)

> Copy everything below the line into your AI coding assistant (Claude Code, Cursor, etc.), or use it as your own master spec. Build it **phase by phase** — do not try to generate everything at once.

---

## Role & Goal

You are a senior backend engineer. Help me build a **production-grade Event Ticket Booking Platform** as a backend REST API in **Java with Spring Boot**. This is a portfolio project, so prioritize clean architecture, real engineering depth, and interview-defensible design decisions over flashy features. The headline feature is **safe concurrent seat booking that never oversells**, even under heavy simultaneous load.

Explain your design choices as you go, especially around concurrency, transactions, and data modeling, so I can defend them in interviews.

## Tech Stack (use exactly this)

- **Java 17+**, **Spring Boot 3.x**
- **Spring Web** (REST), **Spring Data JPA**, **Spring Security**
- **PostgreSQL** as the primary database
- **Redis** for caching and for distributed locking / rate limiting
- **JWT** for stateless authentication
- **JUnit 5 + Mockito** for testing, **Testcontainers** for integration tests
- **Docker + docker-compose** for local orchestration
- **Maven** for build
- **Flyway** for database migrations
- **OpenAPI/Swagger** for API documentation

## Architecture Requirements

- Strict layered architecture: `controller` → `service` → `repository`. Controllers never touch repositories directly.
- DTOs for all request/response bodies. Never expose JPA entities directly over the API.
- Centralized exception handling via `@ControllerAdvice` with consistent error response shape.
- Use constructor injection, not field injection.
- Bean validation (`@Valid`, `jakarta.validation`) on all inputs.
- Meaningful logging (SLF4J) at appropriate levels.

## Core Domain & Features

### Users & Auth
- Registration and login returning a JWT.
- Two roles: `USER` and `ADMIN`. Passwords hashed with BCrypt.
- Role-based authorization: only `ADMIN` can create/update/delete events.

### Events (admin-managed)
- An event has: title, description, venue, datetime, total capacity, and a set of seats.
- Seats have a status: `AVAILABLE`, `HELD`, `BOOKED`.
- Admin CRUD for events; users can browse/search events with pagination and filtering.

### Booking Flow (the core of the project)
1. User requests to book one or more specific seats for an event.
2. System places a temporary **hold** on those seats (status `HELD`) tied to that user, with an expiry (e.g., 5 minutes).
3. User "pays" via a **simulated payment** step. On success, seats become `BOOKED` and a confirmed booking is created.
4. If payment isn't completed before the hold expires, a **scheduled job** releases the seats back to `AVAILABLE`.

## THE CRITICAL CHALLENGE — Concurrency (do this carefully)

This is the most important part of the entire project. The system **must never oversell a seat**, even if hundreds of requests hit the same seat at the same millisecond.

- Implement seat reservation with proper concurrency control. Use one (or compare both) of:
  - **Pessimistic locking** (`SELECT ... FOR UPDATE` via JPA `@Lock(PESSIMISTIC_WRITE)`), and/or
  - **Optimistic locking** (JPA `@Version`) with retry-on-conflict.
- Wrap the hold/book operation in a proper **database transaction** with the correct isolation level, and explain why you chose it.
- Discuss the tradeoffs between pessimistic and optimistic locking for this use case in comments.
- Bonus: also implement a **Redis-based distributed lock** path so the design works across multiple app instances, and explain when DB locking alone is insufficient.
- **Prove it works:** write a concurrency test that fires N simultaneous threads at the same seat and asserts exactly one succeeds and the rest fail cleanly. This test is non-negotiable.

## Caching, Performance & Resilience
- Cache event listings and seat-availability reads in Redis; invalidate correctly on writes.
- Add per-user rate limiting on the booking endpoint (Redis-backed).
- Handle external/payment timeouts gracefully without leaving seats stuck in `HELD`.

## Scheduled Jobs
- A background job (`@Scheduled`) that periodically releases expired holds back to `AVAILABLE`, safely (must itself be concurrency-safe and idempotent).

## Testing (this is what makes you stand out — most candidates skip it)
- Unit tests for service-layer business logic with Mockito.
- Integration tests with Testcontainers (real Postgres + Redis in containers).
- The concurrency test described above.
- Aim for meaningful coverage of the booking logic specifically, not vanity coverage.

## DevOps & Deliverables
- `docker-compose.yml` that spins up the app + Postgres + Redis with one command.
- Flyway migrations for all schema.
- Swagger UI available and documented.
- A polished **README** containing: project overview, architecture diagram, the concurrency design explanation, setup instructions, API endpoint list, and example requests. The README is what a recruiter actually reads — make it excellent.
- Bonus: a `Dockerfile` and notes for deploying to a free tier (Railway/Render).

## How to Proceed (build in this order — confirm each phase with me before moving on)

1. **Project skeleton:** Maven setup, dependencies, package structure, docker-compose for Postgres + Redis, Flyway baseline.
2. **Domain & persistence:** entities (User, Event, Seat, Booking), migrations, repositories.
3. **Auth:** registration, login, JWT, role-based security.
4. **Event management:** admin CRUD + public browse/search with pagination.
5. **Booking core:** hold → pay → confirm flow with full concurrency control. *Spend the most time here.*
6. **Scheduled release job** for expired holds.
7. **Caching + rate limiting** with Redis.
8. **Testing:** unit, integration (Testcontainers), and the concurrency test.
9. **Polish:** Swagger, README, Dockerfile, deployment notes.

At each phase, show me the code, explain the key decisions, and wait for my confirmation before continuing. Start with **Phase 1** now.

---

## Resume bullet you'll be able to write afterward (the real payoff)

> Built a Spring Boot ticket-booking platform handling concurrent seat reservations under load using pessimistic/optimistic locking and DB transactions to guarantee zero overselling; backed by PostgreSQL, Redis caching, JWT auth, and Testcontainers-based integration tests, fully Dockerized and deployed.
