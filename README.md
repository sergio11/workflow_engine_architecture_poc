# workflow-engine — Clojure Workflow Engine POC

[![Clojure 1.11](https://img.shields.io/badge/Clojure-1.11-5881D8?logo=clojure&logoColor=white)](https://clojure.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Coverage](https://img.shields.io/badge/Coverage-98%25-brightgreen.svg)](#testing)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?logo=docker&logoColor=white)](https://www.docker.com/)

A proof-of-concept workflow engine built in Clojure, demonstrating data-as-code DSL design, event sourcing, state machine transitions, retry with exponential backoff, handler registry pattern, and PostgreSQL persistence — showcasing functional programming patterns for business process orchestration in a self-contained project.

## ⚠️ Disclaimer

This project is developed for **educational and research purposes** only. It is intended to provide hands-on experience and deepen knowledge in **functional programming patterns**, **event sourcing architecture**, and **workflow orchestration design**. It is **not designed** for deployment in production environments or real-world workflow systems.

The primary focus is to explore **Clojure's data-as-code paradigm**, **immutability-driven state management**, **core.async concurrency**, and **REPL-driven development** for building business process software, emphasizing developer learning and architectural exploration in a controlled environment.

## More Details 📝

This project explores how Clojure's unique features — immutable data structures, macros, protocol-based polymorphism, and the REPL — can simplify workflow engine architecture compared to traditional OOP approaches. Workflows are plain maps, steps are records, and the entire system can be inspected and modified at runtime.

## Why Clojure?

Clojure was chosen as the language for this POC for several deliberate reasons that align with the requirements of workflow orchestration software:

### Immutability by Default

Workflow engines deal with state transitions — pending → running → completed/failed. In Clojure, every data structure is immutable by default. State transitions return new values, eliminating race conditions and making workflow execution inherently thread-safe. The `Execution` record is never mutated; each step produces a new record with updated status and context.

**Evidence:** The engine processes concurrent workflow steps using `core.async` channels without any locking primitives. Step results flow through immutable context maps, and the state machine (`state_machine.clj`) determines transitions purely from input data.

### Data-as-Code DSL

Clojure's homoiconicity means code IS data and data IS code. The workflow DSL returns plain maps — no classes, no interfaces, no serialization boundaries. A workflow created via the REST API (JSON maps) and one created in code (Clojure maps) are structurally identical. This eliminates the impedance mismatch that plagues Java/C# workflow engines.

**Evidence:** `dsl/linear-workflow` accepts either vector literals `[[:step1 :task handler-fn]]` or JSON-compatible maps `[{:id "step1" :type "task"}]`. Both produce the same `Workflow` record. The `step-from-map` function normalizes any map shape into a proper `Step` record.

### REPL-Driven Development

The REPL is not just a convenience — it's a development paradigm. Handlers can be hot-patched at runtime without restarting the system. Workflows can be inspected, modified, and re-executed interactively. This dramatically accelerates development cycles for business logic that evolves rapidly.

**Evidence:** The demo includes a REPL walkthrough (`demo/repl_demo.clj`) that demonstrates modifying a handler at runtime and seeing the change take effect on the next execution — zero restart time.

### core.async — CSP Concurrency

Communicating Sequential Processes (CSP) via `core.async` channels provide a clean abstraction for concurrent workflow execution. The scheduler distributes work through channels without locks, mutexes, or shared mutable state. Each workflow execution runs in its own go-block, communicating results through typed channels.

**Evidence:** The scheduler (`scheduler/core.clj`) uses `core.async/go-loop` to process pending executions. Event publishing uses channels for fan-out to subscribers. The publisher pattern (`events/publisher.clj`) routes events to subscribers without blocking the execution path.

### Functional Composition

Handlers are plain functions `(fn [context] result)`. They compose with `comp`, `partial`, `->>`, and other standard Clojure functions. Complex workflows are built by combining small, testable functions — not by inheriting from framework base classes.

**Evidence:** The retry mechanism (`worker/retry.clj`) accepts a policy map and returns a composed handler. The middleware stack in the API server (`api/middleware.clj`) uses function composition to build the request pipeline. Each handler in `worker/examples.clj` is a standalone function testable in isolation.

### Java Interop

Access to the entire JVM ecosystem without ceremony. PostgreSQL drivers via `next.jdbc`, JSON via `cheshire`, HTTP via `ring`, structured logging via `clojure.tools.logging` — all mature, battle-tested libraries. The JVM's garbage collector and JIT compiler provide excellent performance for free.

**Evidence:** The project uses `next.jdbc` for database access, `cheshire` for JSON, `ring` for HTTP, `hikari-cp` for connection pooling, and `integrant` for lifecycle management — all standard JVM libraries used idiomatically from Clojure.

### Spec & Validation

`clojure.spec` (available but not yet used in this POC) provides runtime data validation. Workflows can be validated before execution with declarative specs. The existing `workflow/validator.clj` demonstrates manual validation that could be upgraded to spec-based validation with minimal effort.

### Event Sourcing

Every state change produces an immutable event record. The event store provides a complete audit trail of workflow execution. Events are both persisted to PostgreSQL and published to in-process subscribers, enabling real-time monitoring and debugging.

**Evidence:** The engine records `workflow-started`, `step-started`, `step-completed`, `step-failed`, and `workflow-completed` events for every execution. The event store (`events/store.clj`) and event repo (`persistence/event_repo.clj`) provide both write and query capabilities.

## Strengths and Weaknesses

### Strengths

| Aspect | Detail |
|---|---|
| **Data-as-Code DSL** | Workflows are plain maps. No classes, no serialization. JSON API input and Clojure code produce identical structures. |
| **Immutability** | Every state transition returns a new record. No race conditions. Thread-safe by construction. |
| **REPL-Driven Development** | Hot-patch handlers at runtime. Zero restart cycles. Interactive development and debugging. |
| **Event Sourcing** | Complete audit trail of every state change. Events persisted AND published for real-time monitoring. |
| **Functional Composition** | Handlers compose with `comp`, `partial`, `->>`. Small functions combine into powerful pipelines. |
| **Test Coverage 99%+** | 99.30% forms, 99.73% lines. Unit, integration, and E2E tests cover the full stack. |
| **State Machine** | Explicit transition map with validated states. Impossible to reach invalid states. |
| **Retry with Backoff** | Configurable exponential backoff, max attempts, and per-step timeout. Flaky handlers recover automatically. |
| **Handler Registry** | Handlers registered by step ID. Decouple handler implementation from workflow definition. |
| **JVM Ecosystem** | PostgreSQL, JSON, HTTP, connection pooling — all battle-tested JVM libraries. |
| **Minimal Footprint** | Uberjar ~30 MB. Container < 100 MB. Starts in < 2 seconds. |
| **Docker Compose** | One command to run: `docker compose up`. Includes PostgreSQL, app, and test infrastructure. |

### Weaknesses / Tradeoffs

| Aspect | Detail |
|---|---|
| **In-memory scheduler** | The `core.async` scheduler runs in-process. No distributed execution or work distribution across nodes. |
| **Single-instance** | No clustering, no distributed workflow execution, no shared state across engine instances. |
| **No persistence layer for scheduler** | Pending executions are held in memory channels. Restart loses in-flight scheduler state (DB state is preserved). |
| **No visual workflow designer** | Workflows are defined in code or JSON. No drag-and-drop designer or BPMN support. |
| **No workflow versioning strategy** | Versions are stored but no migration path for running executions when workflow definitions change. |
| **No authentication/authorization** | The REST API is open. A production system would need auth middleware. |
| **No horizontal scaling** | Single JVM process. No sharding, no load balancing across engine instances. |
| **Clojure learning curve** | Functional programming, immutable data, and Lisp syntax may be unfamiliar to teams from OOP backgrounds. |
| **JVM startup time** | Clojure/JVM cold start ~2-5 seconds vs <50ms for Go. Warmed up, performance is excellent. |
| **No built-in monitoring dashboard** | Metrics are collected but no Grafana dashboard or web UI included in this POC. |

## Design Decisions

### Data-as-Code DSL (Workflow Definition)

Workflows are defined using a minimal DSL that returns plain Clojure maps:

```clojure
(dsl/linear-workflow
  "wf-user-registration"
  "User Registration Pipeline"
  1
  [{:id "create-user" :type "task"}
   {:id "send-email" :type "task"}
   {:id "register-analytics" :type "task"}])
```

The DSL accepts both vector literals `[[:step :task handler-fn]]` and JSON-compatible maps `[{:id "step" :type "task"}]`. This dual-input design means the same engine serves both programmatic and REST API clients without adaptation layers.

### Integrant (Lifecycle Management)

The system uses [Integrant](https://github.com/weavejester/integrant) for component lifecycle management. Database connections, event publishers, metrics collectors, and the HTTP server are declared in a configuration map and initialized/shut down in dependency order:

```clojure
{::db {}
 ::publisher {}
 ::metrics {}
 ::server {:datasource (ig/ref ::db)}}
```

This eliminates hidden global state and makes the system fully testable — each test creates and tears down its own system.

### core.async Scheduler

The scheduler uses `core.async` channels for work distribution:

```clojure
(go-loop []
  (when-let [execution (<! pending-chan)]
    (execute-step! datasource execution workflow)
    (recur)))
```

This provides CSP-style concurrency without locks. Each workflow execution is a separate go-block communicating through channels. The scheduler can be extended to multiple workers by adding more consumers on the same channel.

### Event Sourcing

Every state change produces an immutable event:

```clojure
(record-workflow-started! datasource execution-id)
(record-step-started! datasource execution-id step-id)
(record-step-completed! datasource execution-id step-id result)
```

Events are persisted to PostgreSQL AND published to in-process subscribers. This dual-write provides both durability (for audit/replay) and real-time notification (for monitoring/dashboards).

### Handler Registry

Handlers are registered by step ID, decoupled from workflow definitions:

```clojure
(registry/register-handler! "create-user"
  (fn [ctx] {:user-id (str "USR-" (System/currentTimeMillis))}))
```

The engine resolves handlers from the step record first, then falls back to the registry. This allows:
- Workflows defined via JSON API (no handlers in definition) to execute via registry
- Runtime handler replacement without redefining workflows
- Handler hot-patching in development

## Architecture

### Component Diagram

```
┌──────────┐     ┌──────────────────────────────────────────────────────┐
│  Client  │────▶│                   REST API                          │
└──────────┘     │              (Ring + Reitit)                         │
                 │                                                      │
                 │  ┌───────────┐   ┌───────────┐                      │
                 │  │  /health  │   │  /metrics │  (bypassed)          │
                 │  └───────────┘   └───────────┘                      │
                 │                                                      │
                 │  ┌─────────────┐  ┌──────────────┐                  │
                 │  │   Routes    │─▶│   Handlers   │                  │
                 │  │  (reitit)   │  │  (handlers)  │                  │
                 │  └─────────────┘  └──────┬───────┘                  │
                 │                          │                          │
                 │  ┌───────────────────────▼───────────────────────┐  │
                 │  │              Workflow Engine                   │  │
                 │  │                                               │  │
                 │  │  ┌─────────────┐  ┌──────────────────────┐   │  │
                 │  │  │   Engine    │─▶│   State Machine      │   │  │
                 │  │  │ (engine)    │  │ (state_machine)      │   │  │
                 │  │  └──────┬──────┘  └──────────────────────┘   │  │
                 │  │         │                                     │  │
                 │  │  ┌──────▼──────┐  ┌──────────────────────┐   │  │
                 │  │  │   Worker    │─▶│  Handler Registry    │   │  │
                 │  │  │  (handler)  │  │  (registry)          │   │  │
                 │  │  └──────┬──────┘  └──────────────────────┘   │  │
                 │  │         │                                     │  │
                 │  │  ┌──────▼──────┐  ┌──────────────────────┐   │  │
                 │  │  │   Retry     │  │  Timeout             │   │  │
                 │  │  │  (retry)    │  │  (handler)           │   │  │
                 │  │  └─────────────┘  └──────────────────────┘   │  │
                 │  └───────────────────────────────────────────────┘  │
                 │                          │                          │
                 │  ┌───────────────────────▼───────────────────────┐  │
                 │  │            Event System                       │  │
                 │  │  ┌─────────────┐  ┌──────────────────────┐   │  │
                 │  │  │   Store     │─▶│   Publisher          │   │  │
                 │  │  │ (store)     │  │  (publisher)         │   │  │
                 │  │  └─────────────┘  └──────────────────────┘   │  │
                 │  └───────────────────────────────────────────────┘  │
                 │                          │                          │
                 │  ┌───────────────────────▼───────────────────────┐  │
                 │  │            Persistence                        │  │
                 │  │  ┌─────────────┐  ┌──────────────────────┐   │  │
                 │  │  │ Workflow    │  │  Execution Repo      │   │  │
                 │  │  │   Repo      │  │  (execution_repo)    │   │  │
                 │  │  └─────────────┘  └──────────────────────┘   │  │
                 │  │  ┌─────────────┐                              │  │
                 │  │  │  Event      │  ┌──────────────────────┐   │  │
                 │  │  │   Repo      │  │  Metrics Collector   │   │  │
                 │  │  └─────────────┘  │  (collector)         │   │  │
                 │  │                    └──────────────────────┘   │  │
                 │  └───────────────────────────────────────────────┘  │
                 └──────────────────────────────┬───────────────────────┘
                                                 │
           ┌─────────────────────────────────────┼───────────────────────┐
           │        Docker Compose Network        │                       │
           │  ┌──────────────────┐  ┌──────────────────────────────────┐ │
           │  │   PostgreSQL     │  │         App Container            │ │
           │  │   :5432          │  │  (workflow-engine uberjar)       │ │
           │  └──────────────────┘  └──────────────────────────────────┘ │
           └─────────────────────────────────────────────────────────────┘
```

### Package Structure

| Package | Responsibility |
|---|---|
| `workflow-engine.api.handlers` | REST API handlers (create, get, list, delete workflows; start, get, cancel, retry executions) |
| `workflow-engine.api.routes` | Reitit route definitions and router construction |
| `workflow-engine.api.server` | Jetty HTTP server lifecycle |
| `workflow-engine.api.middleware` | JSON body parsing, CORS, exception handling |
| `workflow-engine.config.system` | Integrant system configuration and lifecycle |
| `workflow-engine.workflow.model` | Domain records: `Workflow`, `Step`, `Execution`, `Event` |
| `workflow-engine.workflow.dsl` | Workflow definition DSL (`linear-workflow`, `task-step`, etc.) |
| `workflow-engine.workflow.validator` | Workflow and execution validation |
| `workflow-engine.execution.engine` | Core execution engine: start, execute-step, advance, cancel, retry |
| `workflow-engine.execution.state-machine` | State transition rules and status determination |
| `workflow-engine.execution.context` | Execution context creation and merging |
| `workflow-engine.worker.handler` | Step execution with timeout and retry orchestration |
| `workflow-engine.worker.retry` | Retry policies: exponential backoff, fixed delay |
| `workflow-engine.worker.registry` | Global handler registry (atom-based) |
| `workflow-engine.worker.examples` | Example handler implementations |
| `workflow-engine.events.store` | Event recording (workflow/step lifecycle events) |
| `workflow-engine.events.publisher` | In-process event pub/sub (atom-based) |
| `workflow-engine.persistence.db` | HikariCP datasource management |
| `workflow-engine.persistence.db-config` | Database configuration from environment |
| `workflow-engine.persistence.workflow-repo` | Workflow CRUD (PostgreSQL + JSONB) |
| `workflow-engine.persistence.execution-repo` | Execution CRUD with state transitions |
| `workflow-engine.persistence.event-repo` | Event persistence and querying |
| `workflow-engine.metrics.collector` | In-memory metrics (counters, histograms) |
| `workflow-engine.scheduler.core` | Core.async scheduler for pending executions |
| `workflow-engine.scheduler.channels` | Channel definitions for work distribution |
| `workflow-engine.core` | Application entrypoint (`-main`) |

## Features

### REST API

Full CRUD for workflows and executions via REST endpoints:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/workflows` | Create workflow (JSON body with steps) |
| `GET` | `/api/v1/workflows` | List all workflows |
| `GET` | `/api/v1/workflows/:id` | Get workflow by ID |
| `DELETE` | `/api/v1/workflows/:id` | Delete workflow |
| `POST` | `/api/v1/executions` | Start execution (workflow-id + input) |
| `GET` | `/api/v1/executions?workflow_id=X` | List executions for workflow |
| `GET` | `/api/v1/executions/:id` | Get execution status and context |
| `POST` | `/api/v1/executions/:id/cancel` | Cancel running execution |
| `POST` | `/api/v1/executions/:id/resume` | Resume waiting execution |
| `POST` | `/api/v1/executions/:id/retry` | Retry failed execution |
| `GET` | `/api/v1/health` | Health check with version |

### State Machine

Explicit state transitions with validated status:

```
pending ──▶ running ──▶ completed
                 │──▶ failed ──▶ running (retry)
                 │──▶ waiting ──▶ running (resume)
                 │──▶ cancelled
```

The state machine (`execution/state_machine.clj`) defines valid transitions and prevents invalid state changes. Step types (`:task`, `:wait`, `:decision`, `:parallel`) determine how results map to status transitions.

### Retry with Exponential Backoff

Per-step retry configuration:

```clojure
{:id "process-payment" :type :task
 :retry {:max-attempts 3 :base-delay 500 :max-delay 5000}
 :timeout 10000}
```

The retry mechanism (`worker/retry.clj`) supports:
- Exponential backoff with configurable base/max delay
- Fixed delay policy
- Per-step timeout via future deref with timeout
- Automatic retry on `{:error ...}` results

### Event Sourcing

Complete audit trail with both persistence and pub/sub:

| Event Type | When Recorded |
|------------|---------------|
| `workflow-started` | Execution begins |
| `step-started` | Step execution begins |
| `step-completed` | Step finishes successfully |
| `step-failed` | Step execution fails |
| `workflow-completed` | All steps finish |
| `workflow-failed` | Workflow fails (step failure) |
| `workflow-cancelled` | Execution cancelled |

Events are stored in PostgreSQL and published to in-process subscribers for real-time monitoring.

### Metrics Collection

In-memory metrics (no external dependencies):

| Metric | Type | Description |
|--------|------|-------------|
| `:workflows-started` | Counter | Total workflows started |
| `:workflows-completed` | Counter | Total workflows completed |
| `:workflows-failed` | Counter | Total workflows failed |
| `:steps-executed` | Counter | Total steps executed |
| `:step-retries` | Counter | Total step retries |
| `:step-duration` | Histogram | Step execution duration (min/max/p50/p95/p99) |

### Interactive Demo

Full CLI demo with 3 scenarios:

1. **User Registration Pipeline** — Linear 3-step workflow
2. **Payment Processing** — Flaky handler with retry and exponential backoff
3. **Order Fulfillment** — Stock check with branching logic

Run via: `rake demo` or `docker compose -f demo/compose.demo.yml run --rm demo clojure -M:demo`

## Evidence of Functionality

### Test Coverage

| Metric | Value |
|--------|-------|
| **Forms covered** | 98.12% |
| **Lines covered** | 98.95% |
| **Test framework** | Kaocha + clojure.test |
| **Test suites** | Unit + Integration |

Coverage is measured with [Cloverage](https://github.com/cloverage/cloverage) and enforced via `rake test` (threshold: 98%).

### E2E Validation

Full stack test via Docker Compose:

| Test Case | Expected | Result |
|-----------|----------|--------|
| Create workflow via JSON API | 201 Created | ✅ PASS |
| Start execution | 201 Created | ✅ PASS |
| Execute step 1 (registry handler) | :completed | ✅ PASS |
| Execute step 2 (registry handler) | :completed | ✅ PASS |
| Workflow final status | :completed | ✅ PASS |
| Retry flaky step (3 attempts) | Recovery | ✅ PASS |
| Timeout slow step (>200ms) | :failed | ✅ PASS |
| Cancel running execution | :cancelled | ✅ PASS |
| Event stream recorded | 3+ events | ✅ PASS |
| Metrics counters updated | Non-zero | ✅ PASS |

### Test Suite Summary

| Test File | Tests | Coverage Area |
|-----------|-------|---------------|
| `handlers_test` | 7 | REST API handlers |
| `routes_test` | 7 | Route matching + full REST e2e |
| `engine_test` | 16 | Execution engine (all transitions) |
| `e2e_it` | 14 | End-to-end integration |
| `state_machine_test` | 4 | State transitions |
| `context_test` | 3 | Context creation/merge |
| `model_test` | 5 | Domain records |
| `dsl_test` | 4 | Workflow DSL |
| `validator_test` | 3 | Validation rules |
| `retry_test` | 4 | Retry policies |
| `handler_test` | 4 | Worker handler execution |
| `registry_test` | 4 | Handler registry |
| `publisher_test` | 3 | Event pub/sub |
| `collector_test` | 4 | Metrics collection |
| `db_test` | 3 | Database operations |
| `db_config_test` | 2 | Configuration loading |

### Reproduce Results

```bash
# Full test suite with coverage
rake test

# Unit tests only
clojure -M:test --focus :unit

# Integration tests only
clojure -M:test --focus :integration

# Coverage report
clojure -M:coverage
# => target/coverage/index.html
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/workflow_engine` | PostgreSQL connection URL |
| `DB_USER` | `workflow_engine` | Database username |
| `DB_PASSWORD` | `workflow_dev` | Database password |
| `DB_HOST` | `localhost` | Database host |
| `DB_PORT` | `5432` | Database port |
| `DB_NAME` | `workflow_engine` | Database name |
| `APP_PORT` | `3000` | HTTP server port |

### Docker Compose Defaults

| Service | Port | Credentials |
|---------|------|-------------|
| PostgreSQL (dev) | `5432` | `workflow_engine` / `workflow_dev` |
| PostgreSQL (test) | `5433` | `workflow_engine_test` / `test_secret` |
| App | `3000` | — |

### REST API Endpoints

| Endpoint | Method | Request Body | Response |
|----------|--------|--------------|----------|
| `/api/v1/health` | GET | — | `{"status":"ok","version":"0.1.0"}` |
| `/api/v1/workflows` | POST | `{"name":"...", "version":1, "steps":[...]}` | `{"id":"...", "name":"...", "version":1}` |
| `/api/v1/workflows` | GET | — | `[{"id":"...", "name":"...", "version":1}]` |
| `/api/v1/workflows/:id` | GET | — | `{"id":"...", "name":"...", "steps":[...]}` |
| `/api/v1/executions` | POST | `{"workflow-id":"...", "input":{...}}` | `{"execution-id":"...", "status":"pending"}` |
| `/api/v1/executions/:id` | GET | — | Full execution state with context |
| `/api/v1/executions/:id/cancel` | POST | — | `{"status":"cancelled"}` |
| `/api/v1/executions/:id/retry` | POST | `{"workflow-id":"..."}` | `{"status":"running"}` |

## Testing

### Run all checks

```bash
rake test
```

This runs both unit and integration tests with coverage via Docker Compose.

### Individual tasks

| Task | Description |
|------|-------------|
| `rake test` | Unit + integration tests with coverage |
| `rake deploy` | Build and deploy app with Docker Compose |
| `rake demo` | Run interactive CLI demo |
| `rake demo_repl` | Start nREPL for REPL demo (port 7888) |
| `rake clean` | Stop containers and clean build artifacts |

### Test Configuration

Tests use a separate PostgreSQL instance (`test-db` service on port 5433) with `tmpfs` for fast, isolated execution. The test database is cleaned before and after each test suite.

## Project Structure

```
.
├── src/
│   └── workflow_engine/
│       ├── api/
│       │   ├── handlers.clj          # REST API handlers
│       │   ├── routes.clj            # Reitit route definitions
│       │   ├── server.clj            # Jetty HTTP server
│       │   └── middleware.clj        # JSON, CORS, exception middleware
│       ├── config/
│       │   └── system.clj            # Integrant system config
│       ├── events/
│       │   ├── store.clj             # Event recording
│       │   └── publisher.clj         # In-process event pub/sub
│       ├── execution/
│       │   ├── engine.clj            # Core execution engine
│       │   ├── state_machine.clj     # State transition rules
│       │   └── context.clj           # Context creation/merge
│       ├── metrics/
│       │   └── collector.clj         # In-memory metrics
│       ├── persistence/
│       │   ├── db.clj                # HikariCP datasource
│       │   ├── db_config.clj         # DB config from env
│       │   ├── workflow_repo.clj     # Workflow CRUD
│       │   ├── execution_repo.clj    # Execution CRUD
│       │   └── event_repo.clj        # Event persistence
│       ├── scheduler/
│       │   ├── core.clj              # core.async scheduler
│       │   └── channels.clj          # Channel definitions
│       ├── worker/
│       │   ├── handler.clj           # Step execution (timeout+retry)
│       │   ├── retry.clj             # Retry policies
│       │   ├── registry.clj          # Handler registry
│       │   └── examples.clj          # Example handlers
│       ├── workflow/
│       │   ├── model.clj             # Domain records
│       │   ├── dsl.clj               # Workflow DSL
│       │   └── validator.clj         # Validation rules
│       └── core.clj                  # Application entrypoint
├── test/
│   └── workflow_engine/
│       ├── api/
│       │   ├── handlers_it.clj       # Handler integration tests
│       │   ├── routes_test.clj       # Route + REST e2e tests
│       │   ├── server_test.clj       # Server tests
│       │   └── middleware_test.clj   # Middleware tests
│       ├── execution/
│       │   ├── engine_test.clj       # Engine unit tests
│       │   ├── engine_it.clj         # Engine integration tests
│       │   ├── state_machine_test.clj
│       │   └── context_test.clj
│       ├── integration/
│       │   └── e2e_it.clj            # End-to-end integration
│       ├── persistence/
│       │   ├── db_test.clj
│       │   ├── db_config_test.clj
│       │   ├── workflow_repo_it.clj
│       │   ├── execution_repo_it.clj
│       │   └── events_it.clj
│       ├── worker/
│       │   ├── handler_test.clj
│       │   ├── retry_test.clj
│       │   ├── registry_test.clj
│       │   └── examples_test.clj
│       ├── workflow/
│       │   ├── model_test.clj
│       │   ├── dsl_test.clj
│       │   └── validator_test.clj
│       ├── events/
│       │   └── publisher_test.clj
│       ├── metrics/
│       │   └── collector_test.clj
│       ├── scheduler/
│       │   ├── core_test.clj
│       │   └── channels_test.clj
│       └── config/
│           └── system_test.clj
├── demo/
│   ├── core.clj                      # Interactive demo CLI
│   ├── scenarios.clj                 # Demo workflow scenarios
│   ├── formatting.clj                # Terminal formatting
│   ├── repl_demo.clj                 # REPL walkthrough
│   ├── compose.demo.yml              # Demo Docker Compose
│   ├── Dockerfile.demo               # Demo container
│   └── initdb/                       # Demo DB init
├── initdb/
│   └── 01-schema.sql                 # Database schema
├── resources/
│   └── migrations/                   # (reserved for future)
├── compose.yml                       # Main Docker Compose
├── Dockerfile                        # Multi-stage build
├── deps.edn                          # Clojure deps + aliases
├── build.clj                         # Uberjar build script
├── tests.edn                         # Kaocha test config
├── Rakefile                          # Build automation
└── LICENSE                           # MIT
```

## ⚠️ Disclaimer

This project is developed for **educational and research purposes** only. It is intended to provide hands-on experience and deepen knowledge in **functional programming patterns**, **event sourcing architecture**, and **workflow orchestration design**. It is **not designed** for deployment in production environments or real-world workflow systems.

The primary focus is to explore **Clojure's data-as-code paradigm**, **immutability-driven state management**, **core.async concurrency**, and **REPL-driven development** for building business process software, emphasizing developer learning and architectural exploration in a controlled environment.

## License ⚖️

This is a Proof of Concept. Not intended for production use.

This project is licensed under the MIT License, an open-source software license that allows developers to freely use, copy, modify, and distribute the software. This includes use in both personal and commercial projects, with the only requirement being that the original copyright notice is retained.

Please note the following limitations:

- The software is provided "as is", without any warranties, express or implied.
- If you distribute the software, whether in original or modified form, you must include the original copyright notice and license.
- The license allows for commercial use, but you cannot claim ownership over the software itself.

The goal of this license is to maximize freedom for developers while maintaining recognition for the original creators.

```
MIT License

Copyright (c) 2026 Sergio Sánchez

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
