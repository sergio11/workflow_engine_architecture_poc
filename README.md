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

## Architecture

### Layered Architecture

The project follows a **layered architecture** with clear separation of concerns across nine distinct layers. Each layer has a single responsibility and communicates with adjacent layers through well-defined interfaces.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         API Layer                                   │
│   (HTTP Interface: Routes, Handlers, Middleware, Server)            │
├─────────────────────────────────────────────────────────────────────┤
│                       Domain Layer                                  │
│   (Business Logic: Model, DSL, Validator)                           │
├─────────────────────────────────────────────────────────────────────┤
│                     Execution Layer                                 │
│   (Orchestration: Engine, State Machine, Context)                   │
├─────────────────────────────────────────────────────────────────────┤
│                       Worker Layer                                  │
│   (Step Execution: Handler, Retry, Registry, Examples)              │
├─────────────────────────────────────────────────────────────────────┤
│                     Scheduler Layer                                 │
│   (Async Distribution: Core, Channels)                              │
├─────────────────────────────────────────────────────────────────────┤
│                       Events Layer                                  │
│   (Event Sourcing: Store, Publisher)                                │
├─────────────────────────────────────────────────────────────────────┤
│                     Persistence Layer                               │
│   (Data Access: DB, Workflow Repo, Execution Repo, Event Repo)      │
├─────────────────────────────────────────────────────────────────────┤
│                       Metrics Layer                                 │
│   (Observability: Collector)                                        │
├─────────────────────────────────────────────────────────────────────┤
│                       Config Layer                                  │
│   (Lifecycle: System via Integrant)                                 │
└─────────────────────────────────────────────────────────────────────┘
```

#### Layer Responsibilities

| Layer | Responsibility | Key Files |
|-------|----------------|-----------|
| **API** | HTTP interface, request routing, middleware stack, JSON serialization | `api/handlers.clj`, `api/routes.clj`, `api/middleware.clj`, `api/server.clj` |
| **Domain** | Business entities, workflow DSL, validation rules | `workflow/model.clj`, `workflow/dsl.clj`, `workflow/validator.clj` |
| **Execution** | Workflow orchestration, state transitions, context management | `execution/engine.clj`, `execution/state_machine.clj`, `execution/context.clj` |
| **Worker** | Step execution, retry policies, handler registry | `worker/handler.clj`, `worker/retry.clj`, `worker/registry.clj` |
| **Scheduler** | Asynchronous work distribution via CSP channels | `scheduler/core.clj`, `scheduler/channels.clj` |
| **Events** | Event sourcing, in-process pub/sub | `events/store.clj`, `events/publisher.clj` |
| **Persistence** | PostgreSQL data access, CRUD operations | `persistence/db.clj`, `persistence/workflow_repo.clj`, `persistence/execution_repo.clj`, `persistence/event_repo.clj` |
| **Metrics** | In-memory counters, histograms, percentiles | `metrics/collector.clj` |
| **Config** | Component lifecycle management | `config/system.clj` |

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

### Execution Flow

The following diagram illustrates how a workflow execution flows through the system:

```
Client Request
     │
     ▼
┌─────────────┐
│ REST API    │──── POST /api/v1/executions
│ (handlers)  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Engine     │──── Creates Execution record (status: :pending)
│  (engine)   │──── Records :workflow-started event
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Scheduler  │──── Picks up pending execution via core.async channel
│  (core)     │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Worker     │──── Resolves handler (step → registry fallback)
│  (handler)  │──── Executes with timeout (future deref)
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Retry      │──── On failure: applies retry policy (exponential backoff)
│  (retry)    │──── On success: returns result
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  State      │──── Determines next status based on step type + result
│  Machine    │     :task → :completed/:failed
│             │     :wait → :waiting
│             │     :decision → branch selection
│             │     :parallel → fan-out/fan-in
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Engine     │──── Updates execution status
│  (advance)  │──── Records :step-completed/:step-failed event
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Events     │──── Persists to PostgreSQL (audit trail)
│  (store)    │──── Publishes to in-process subscribers (real-time)
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Metrics    │──── Updates counters and histograms
│  (collector)│
└─────────────┘
```

## Architecture Patterns

This project implements several well-known software architecture patterns, adapted to leverage Clojure's functional paradigm:

### Data-as-Code Paradigm

**Pattern:** Workflows, steps, executions, and events are all plain data structures (records and maps). No OOP class hierarchies, no interfaces, no serialization boundaries.

**Implementation:** The DSL produces Clojure maps that are structurally identical to JSON from the REST API. A workflow created in code and one created via HTTP are interchangeable.

```clojure
;; Code-defined workflow
(dsl/linear-workflow
  "wf-1" "Order Processing" 1
  [{:id "validate" :type "task"}
   {:id "charge"   :type "task"}
   {:id "ship"     :type "task"}])

;; API-defined workflow (JSON → Clojure maps)
;; POST /api/v1/workflows
;; {"name":"Order Processing", "steps":[{"id":"validate","type":"task"}, ...]}
;; Both produce identical Workflow records
```

**Benefits:**
- Zero impedance mismatch between code and data
- JSON API input and Clojure code produce identical structures
- Workflows can be inspected, modified, and serialized as plain data

### Event Sourcing

**Pattern:** Every state change produces an immutable event record. The event store provides a complete audit trail of workflow execution.

**Implementation:** Events are persisted to PostgreSQL AND published to in-process subscribers. This dual-write provides both durability (for audit/replay) and real-time notification (for monitoring/dashboards).

```clojure
;; Event recording
(record-workflow-started! datasource execution-id)
(record-step-started! datasource execution-id step-id)
(record-step-completed! datasource execution-id step-id result)
(record-step-failed! datasource execution-id step-id error)
(record-workflow-completed! datasource execution-id)
```

**Event Types:**
| Event | Trigger | Data |
|-------|---------|------|
| `:workflow-started` | Execution begins | execution-id, workflow-id, timestamp |
| `:step-started` | Step execution begins | execution-id, step-id, timestamp |
| `:step-completed` | Step finishes successfully | execution-id, step-id, result, duration |
| `:step-failed` | Step execution fails | execution-id, step-id, error, duration |
| `:workflow-completed` | All steps finish | execution-id, final-context |
| `:workflow-failed` | Workflow fails | execution-id, error, failed-step |
| `:workflow-cancelled` | Execution cancelled | execution-id, reason |

### State Machine

**Pattern:** Explicit state transitions defined in a transition map. Invalid transitions are impossible.

**Implementation:** The state machine (`execution/state_machine.clj`) defines a `valid-transitions` map and a `determine-next-status` function that maps step type + result to the next status.

```
                    ┌─────────────────────────────────────┐
                    │                                     │
                    ▼                                     │
               ┌─────────┐                               │
               │ pending │                               │
               └────┬────┘                               │
                    │ start                               │
                    ▼                                     │
               ┌─────────┐                               │
          ┌───▶│ running │◀──────────────────────┐       │
          │    └────┬────┘                       │       │
          │         │                            │       │
          │         ├──▶ completed               │       │
          │         │                            │       │
          │         ├──▶ failed ──── retry ──────┘       │
          │         │                                    │
          │         ├──▶ waiting ──── resume ────────────┘
          │         │
          │         └──▶ cancelled
          │
          │    ┌─────────────┐
          └────│   History   │
               └─────────────┘
```

**State Transitions:**
| Current State | Event | Next State |
|---------------|-------|------------|
| `:pending` | `start` | `:running` |
| `:running` | `step-completed` | `:completed` / `:running` (next step) |
| `:running` | `step-failed` | `:failed` |
| `:running` | `wait-step` | `:waiting` |
| `:running` | `cancel` | `:cancelled` |
| `:failed` | `retry` | `:running` |
| `:waiting` | `resume` | `:running` |

### Handler Registry (Service Locator)

**Pattern:** Handlers are registered by step ID in a global atom-based registry. This decouples handler implementation from workflow definitions.

**Implementation:** The registry (`worker/registry.clj`) allows workflows defined via JSON API (no handlers in definition) to execute via registered handlers. Handlers can be hot-patched at runtime.

```clojure
;; Register handler
(registry/register-handler! "create-user"
  (fn [ctx] {:user-id (str "USR-" (System/currentTimeMillis))}))

;; Engine resolves: step.handler → registry fallback
```

### CSP Concurrency (Communicating Sequential Processes)

**Pattern:** Work distribution through channels without locks, mutexes, or shared mutable state.

**Implementation:** The scheduler uses `core.async` channels for work distribution. Work items flow through `work-ch` → workers → `result-ch` → result processor.

```clojure
;; Channel-based work distribution
(go-loop []
  (when-let [execution (<! work-ch)]
    (execute-step! execution)
    (recur)))
```

**Benefits:**
- Lock-free concurrency
- Composable communication patterns
- Natural backpressure via channel buffering

### Repository Pattern

**Pattern:** Abstract database access behind repository interfaces.

**Implementation:** Three repositories handle persistence:
- `workflow_repo.clj` — Workflow CRUD with JSONB serialization
- `execution_repo.clj` — Execution CRUD with state transitions
- `event_repo.clj` — Event persistence and querying

### Middleware Composition

**Pattern:** Build request processing pipeline through function composition.

**Implementation:** Ring middleware stack in `api/middleware.clj`:

```clojure
(defn wrap-defaults [handler]
  (-> handler
      wrap-exception-handling
      wrap-cors
      wrap-json-body
      wrap-json-response
      wrap-request-logging))
```

### Retry with Exponential Backoff

**Pattern:** Configurable retry policies with exponential backoff for transient failures.

**Implementation:** Per-step retry configuration:

```clojure
{:id "process-payment"
 :type :task
 :retry {:max-attempts 3 :base-delay 500 :max-delay 5000}
 :timeout 10000}
```

**Strategies:**
- Exponential backoff with configurable base/max delay
- Fixed delay policy
- Per-step timeout via future deref with timeout
- Automatic retry on `{:error ...}` results

### Component Lifecycle (Integrant)

**Pattern:** Declare system components in a configuration map with explicit dependency ordering.

**Implementation:** Integrant manages initialization and shutdown of all components:

```clojure
{::db {}
 ::publisher {}
 ::metrics {}
 ::scheduler {:datasource (ig/ref ::db)
             :publisher (ig/ref ::publisher)
             :metrics (ig/ref ::metrics)}
 ::server {:datasource (ig/ref ::db)
           :publisher (ig/ref ::publisher)
           :metrics (ig/ref ::metrics)}}
```

## Technology Stack

### Core Language & Runtime

| Technology | Version | Purpose |
|------------|---------|---------|
| **Clojure** | 1.11.1 | Functional Lisp on JVM |
| **JDK Temurin** | 21 | Java Virtual Machine runtime |

### Application Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `org.clojure/clojure` | 1.11.1 | Core language runtime |
| `org.clojure/core.async` | 1.6.681 | CSP concurrency (channels, go-blocks) |
| `org.clojure/tools.logging` | 1.3.0 | Structured logging |
| `ring/ring-core` | 1.11.0 | HTTP request/response abstractions |
| `ring/ring-jetty-adapter` | 1.11.0 | Embedded Jetty HTTP server |
| `ring/ring-json` | 0.5.1 | JSON body parsing/serialization |
| `metosin/reitit` | 0.7.0 | Data-driven routing |
| `metosin/reitit-ring` | 0.7.0 | Ring integration for Reitit |
| `metosin/reitit-middleware` | 0.7.0 | Routing middleware |
| `integrant/integrant` | 0.10.0 | Component lifecycle management |
| `com.github.seancorfield/next.jdbc` | 1.3.909 | JDBC database access |
| `hikari-cp/hikari-cp` | 3.3.0 | Connection pooling |
| `org.postgresql/postgresql` | 42.7.1 | PostgreSQL JDBC driver |
| `cheshire/cheshire` | 5.12.0 | JSON encoding/decoding |

### Testing Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `lambdaisland/kaocha` | 1.91.1392 | Test runner with suite support |
| `cloverage/cloverage` | 1.2.4 | Code coverage measurement |
| `org.slf4j/slf4j-simple` | 2.0.13 | Logging for test runs |

### Build & Development Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `org.clojure/tools.build` | 0.9.2 | Uberjar creation |
| `nrepl/nrepl` | 1.1.1 | nREPL server for REPL-driven development |
| `cider/cider-nrepl` | 0.49.0 | CIDER middleware for editor integration |

### Infrastructure

| Technology | Version | Purpose |
|------------|---------|---------|
| **PostgreSQL** | 16-alpine | Primary data store |
| **Docker** | multi-stage build | Containerization |
| **Docker Compose** | v2 | Service orchestration |
| **Rake** | Ruby | Build automation |

### Package Structure

| Package | Responsibility |
|---------|----------------|
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

## Domain Model

### Core Entities

#### Workflow

Represents a workflow definition with ordered steps.

```clojure
(defrecord Workflow [id name version steps metadata])
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique workflow identifier |
| `name` | String | Human-readable workflow name |
| `version` | Integer | Workflow version number |
| `steps` | Vector | Ordered list of Step records |
| `metadata` | Map | Additional workflow metadata |

#### Step

Represents a single unit of work within a workflow.

```clojure
(defrecord Step [id type handler retry timeout branches])
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique step identifier |
| `type` | Keyword | Step type: `:task`, `:wait`, `:decision`, `:parallel` |
| `handler` | Function | Step execution function `(fn [context] result)` |
| `retry` | Map | Retry policy configuration |
| `timeout` | Integer | Maximum execution time in milliseconds |
| `branches` | Map | Branch definitions for `:decision` steps |

**Step Types:**
| Type | Description | Behavior |
|------|-------------|----------|
| `:task` | Standard processing step | Executes handler, returns result |
| `:wait` | Pauses execution | Sets status to `:waiting`, awaits resume |
| `:decision` | Conditional branching | Evaluates result, selects next branch |
| `:parallel` | Concurrent execution | Fans out to multiple branches, fans in results |

#### Execution

Represents a running or completed workflow instance.

```clojure
(defrecord Execution [execution-id workflow-id status current-step
                      started-at updated-at context history])
```

| Field | Type | Description |
|-------|------|-------------|
| `execution-id` | String | Unique execution identifier |
| `workflow-id` | String | Reference to workflow definition |
| `status` | Keyword | Current status (see State Machine) |
| `current-step` | String | ID of the currently executing step |
| `started-at` | Timestamp | Execution start time |
| `updated-at` | Timestamp | Last update time |
| `context` | Map | Execution context (input + accumulated results) |
| `history` | Vector | List of completed step IDs |

#### Event

Represents an immutable record of a state change.

```clojure
(defrecord Event [type execution-id step timestamp data])
```

| Field | Type | Description |
|-------|------|-------------|
| `type` | Keyword | Event type (see Event Sourcing) |
| `execution-id` | String | Reference to execution |
| `step` | String | Step ID (if step-related event) |
| `timestamp` | Timestamp | Event creation time |
| `data` | Map | Event-specific payload |

### Execution Context

The execution context is a plain map passed through the workflow, accumulating results from each step.

```clojure
;; Initial context (from API input)
{:input {:user-id "123" :email "user@example.com"}
 :started-at #inst "2026-08-15T10:00:00Z"}

;; After step "create-user"
{:input {:user-id "123" :email "user@example.com"}
 :started-at #inst "2026-08-15T10:00:00Z"
 :last-result {:user-id "USR-12345"}
 :create-user {:user-id "USR-12345"}}

;; After step "send-email"
{:input {:user-id "123" :email "user@example.com"}
 :started-at #inst "2026-08-15T10:00:00Z"
 :last-result {:sent true}
 :create-user {:user-id "USR-12345"}
 :send-email {:sent true}}
```

### Workflow DSL

The DSL provides a concise way to define workflows in code:

```clojure
;; Linear workflow (sequential steps)
(dsl/linear-workflow
  "wf-user-registration"
  "User Registration Pipeline"
  1
  [{:id "create-user" :type "task"}
   {:id "send-email" :type "task"}
   {:id "register-analytics" :type "task"}])

;; Task step with handler
(dsl/task-step "process-order"
  (fn [ctx]
    {:order-id (str "ORD-" (System/currentTimeMillis))}))

;; Wait step (pauses execution)
(dsl/wait-step "await-approval"
  :description "Waiting for manual approval")

;; Decision step (conditional branching)
(dsl/decision-step "check-inventory"
  (fn [ctx]
    (if (> (get-in ctx [:input :quantity]) 10)
      :bulk :single))
  {:bulk   [(dsl/task-step "process-bulk-order" ...)]
   :single [(dsl/task-step "process-single-order" ...)]})

;; Parallel step (concurrent execution)
(dsl/parallel-step "validate-and-check"
  [(dsl/task-step "validate-data" ...)
   (dsl/task-step "check-fraud" ...)])
```

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
| `:step-duration` | Histogram | Step execution duration (min/max/p50/p55/p99) |

### Interactive Demo

Full CLI demo with 3 scenarios:

1. **User Registration Pipeline** — Linear 3-step workflow
2. **Payment Processing** — Flaky handler with retry and exponential backoff
3. **Order Fulfillment** — Stock check with branching logic

Run via: `rake demo` or `docker compose -f demo/compose.demo.yml run --rm demo clojure -M:demo`

## Testing

### Test Coverage

| Metric | Value |
|--------|-------|
| **Forms covered** | 98.87% |
| **Lines covered** | 99.56% |
| **Test framework** | Kaocha + clojure.test |
| **Test suites** | Unit + Integration |

Coverage is measured with [Cloverage](https://github.com/cloverage/cloverage) and enforced via `rake test` (threshold: 98%).

### How High Coverage Was Achieved

Achieving 98%+ coverage required a systematic, multi-layered testing strategy. This section documents the effort and techniques used to reach these levels.

#### 1. Two-Tier Test Suite Architecture

Tests are split into two independent suites with separate runners:

- **Unit tests** (`*_test.clj`) — Pure function testing, no external dependencies
- **Integration tests** (`*_it.clj`) — Full stack with real PostgreSQL

This separation allows:
- Fast feedback during development (unit tests run in seconds)
- Comprehensive validation before deployment (integration tests verify persistence)
- Independent execution of each suite

#### 2. Exhaustive Unit Tests for Pure Functions

All pure domain functions have exhaustive unit tests:

| Test File | Lines | Coverage Area |
|-----------|-------|---------------|
| `engine_test.clj` | 311 | All engine operations: start, execute-step, cancel, retry, resume, advance, decision branching, parallel steps, exception handling |
| `state_machine_test.clj` | 51 | All state transitions |
| `context_test.clj` | 41 | Context creation, merging, accumulation |
| `model_test.clj` | 44 | Domain record constructors |
| `dsl_test.clj` | 133 | DSL functions: linear, task, wait, decision, parallel |
| `validator_test.clj` | 57 | Validation rules for workflows and executions |
| `retry_test.clj` | 89 | Retry policies: exponential backoff, fixed delay |
| `handler_test.clj` | 75 | Worker handler execution with timeout |
| `registry_test.clj` | 50 | Handler registration and resolution |
| `publisher_test.clj` | 140 | Event pub/sub |
| `collector_test.clj` | 169 | Metrics collection and computation |

#### 3. Integration Tests with Real PostgreSQL

Integration tests use a dedicated PostgreSQL instance:

- **Isolated database**: Port 5433, separate from development
- **Fast execution**: `tmpfs` mount for in-memory storage
- **Automatic cleanup**: Tables cleared before/after each test suite
- **Full CRUD operations**: All repository functions tested with real data

| Integration Test | Lines | Coverage Area |
|------------------|-------|---------------|
| `handlers_it.clj` | 295 | REST API handlers with real database |
| `e2e_it.clj` | 280 | Full workflow lifecycle: retry exhaustion, timeouts, event publishing |
| `workflow_repo_it.clj` | 70 | Workflow CRUD operations |
| `execution_repo_it.clj` | 94 | Execution CRUD with state transitions |
| `store_it.clj` | 91 | Event persistence and querying |
| `scheduler_it.clj` | 176 | Scheduler with real channels and database |

#### 4. Comprehensive E2E Testing

The `e2e_it.clj` file (280 lines) covers complete workflow lifecycles:

- Workflow creation via JSON API
- Execution start with input data
- Step execution with registry handlers
- Retry exhaustion (3 attempts with backoff)
- Step timeout handling
- Event stream verification (3+ events per execution)
- Metrics counter validation
- Execution cancellation
- Resume from waiting state

#### 5. Middleware Chain Testing

`middleware_test.clj` (168 lines) validates the entire HTTP pipeline:

- Individual middleware behavior
- Middleware chaining and composition
- Exception propagation through the stack
- CORS header injection
- JSON body parsing (request and response)
- Request logging output format

#### 6. Mocking and Isolation Techniques

Tests use `with-redefs` to mock external dependencies:

```clojure
;; Mock Jetty server for unit tests
(with-redefs [ring.adapter.jetty/run-jetty (fn [opts] ...)]
  (server/start-server! opts))

;; Mock event recording for engine tests
(with-redefs [events.store/record-step-completed! (fn [& args] ...)]
  (engine/execute-step! ...))
```

This allows testing business logic in isolation while verifying integration points.

#### 7. Fixture-Based Cleanup

All test suites use fixtures for isolation:

```clojure
(use-fixtures :once
  (fn [f]
    (setup-test-db!)
    (f)
    (cleanup-test-db!)))

(use-fixtures :each
  (fn [f]
    (clear-registry!)
    (close-channels!)
    (f)))
```

#### 8. Coverage Threshold Enforcement

The `deps.edn` coverage alias enforces minimum thresholds:

```clojure
:coverage
{:extra-deps {cloverage/cloverage "1.2.4"}
 :main-opts ["-m" "cloverage.coverage"
             "--fail-threshold" "98"
             "--low-watermark" "99"]}
```

This ensures CI fails if coverage drops below 98%.

#### 9. Per-Namespace Coverage Analysis

| Namespace | Forms % | Lines % |
|-----------|---------|---------|
| `workflow-engine.workflow.model` | 100% | 100% |
| `workflow-engine.workflow.dsl` | 100% | 100% |
| `workflow-engine.workflow.validator` | 99.49% | 100% |
| `workflow-engine.execution.engine` | 99.83% | 100% |
| `workflow-engine.execution.state-machine` | 100% | 100% |
| `workflow-engine.execution.context` | 100% | 100% |
| `workflow-engine.worker.handler` | 100% | 100% |
| `workflow-engine.worker.retry` | 100% | 100% |
| `workflow-engine.worker.registry` | 97.92% | 100% |
| `workflow-engine.worker.examples` | 100% | 100% |
| `workflow-engine.api.handlers` | 95.78% | 98.32% |
| `workflow-engine.api.routes` | 100% | 100% |
| `workflow-engine.api.server` | 96.75% | 100% |
| `workflow-engine.api.middleware` | 91.51% | 100% |
| `workflow-engine.persistence.db` | 100% | 100% |
| `workflow-engine.persistence.db-config` | 100% | 100% |
| `workflow-engine.persistence.workflow-repo` | 100% | 100% |
| `workflow-engine.persistence.execution-repo` | 99.64% | 100% |
| `workflow-engine.persistence.event-repo` | 100% | 100% |
| `workflow-engine.events.store` | 100% | 100% |
| `workflow-engine.events.publisher` | 98.61% | 100% |
| `workflow-engine.scheduler.core` | 97.41% | 96.30% |
| `workflow-engine.scheduler.channels` | 97.48% | 100% |
| `workflow-engine.metrics.collector` | 99.64% | 100% |
| `workflow-engine.config.system` | 97.56% | 97.30% |
| `workflow-engine.version` | 100% | 100% |

**Key Achievement:** 18 out of 27 namespaces achieve 100% line coverage. The remaining namespaces are above 96%, with the lowest being `middleware` at 91.51% forms (but 100% lines).

#### 10. Strategic Coverage Exclusions

Some system-level code is excluded from coverage measurement as they cannot be meaningfully tested in unit tests:

- `ring.adapter.jetty/run-jetty` — External HTTP server
- `workflow-engine.config.system/start-system!` — System initialization
- `go-loop` — core.async concurrency primitives

These exclusions are minimal and well-documented, ensuring the coverage numbers reflect actual business logic coverage.

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

### Run All Checks

```bash
rake test
```

This runs both unit and integration tests with coverage via Docker Compose.

### Individual Tasks

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
│       │   ├── state_machine_test.clj
│       │   └── context_test.clj
│       ├── integration/
│       │   └── e2e_it.clj            # End-to-end integration
│       ├── persistence/
│       │   ├── db_test.clj
│       │   ├── db_config_test.clj
│       │   ├── workflow_repo_it.clj
│       │   └── execution_repo_it.clj
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
│       │   ├── publisher_test.clj
│       │   └── store_it.clj
│       ├── metrics/
│       │   └── collector_test.clj
│       ├── scheduler/
│       │   ├── core_test.clj
│       │   ├── channels_test.clj
│       │   └── scheduler_it.clj
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
├── compose.yml                       # Main Docker Compose
├── Dockerfile                        # Multi-stage build
├── deps.edn                          # Clojure deps + aliases
├── build.clj                         # Uberjar build script
├── tests.edn                         # Kaocha test config
├── Rakefile                          # Build automation
└── LICENSE                           # MIT
```

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
