# Concurrent Job Scheduler

A lightweight, concurrent job scheduling service written in pure Java (no Spring). It accepts jobs via a REST API, executes them based on defined schedules, and guarantees resilience with features like exponential backoff retries, timeouts, and backpressure handling.

## Build and Run

To build the application without running tests:

```bash
mvn clean package -DskipTests
```

Run the compiled executable jar:

```bash
java -jar target/job-scheduler.jar
```

The server starts by default on `http://localhost:8080`. It uses an in-memory H2 database out of the box.

## API Usage Examples

### 1. Create a One-Time Job

```bash
curl -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-sleep",
    "type": "noop_sleep",
    "payload": "{\"sleep_ms\": 500}",
    "scheduleType": "ONCE",
    "maxRetries": 2,
    "timeoutMs": 5000
  }'
```

### 2. Create a Periodic Job (Fixed Delay)

```bash
curl -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "periodic-sleep",
    "type": "noop_sleep",
    "payload": "{\"sleep_ms\": 100}",
    "scheduleType": "FIXED_DELAY",
    "intervalMs": 5000,
    "maxRetries": 1,
    "timeoutMs": 3000
  }'
```

### 3. Create an HTTP Call Job

```bash
curl -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "http-check",
    "type": "http_call",
    "payload": "{\"url\": \"https://httpbin.org/get\", \"method\": \"GET\"}",
    "scheduleType": "ONCE",
    "timeoutMs": 10000
  }'
```

---

### Endpoints Overview

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/jobs` | List jobs (supports filters: `status`, `type`, `name`, `limit`, `offset`) |
| `GET` | `/jobs/{id}` | Get job details and recent execution runs |
| `POST` | `/jobs/{id}/pause` | Pause a scheduled or queued job |
| `POST` | `/jobs/{id}/resume` | Resume a paused job |
| `POST` | `/jobs/{id}/cancel` | Cancel a scheduled or running job |
| `POST` | `/jobs/{id}/run-now` | Force immediate execution of a job |
| `DELETE` | `/jobs/{id}` | Delete a job (only allowed in terminal statuses) |
| `GET` | `/metrics` | Retrieve operational metrics (queue size, total runs, active workers) |

## Configuration

The application is configured via `src/main/resources/application.properties`.

| Property | Default Value | Description |
|---|---|---|
| `server.port` | `8080` | The port the HTTP server binds to |
| `db.url` | `jdbc:h2:mem:jobscheduler;DB_CLOSE_DELAY=-1` | JDBC URL for the database |
| `scheduler.worker.threads` | `4` | Number of concurrent execution threads |
| `scheduler.max.concurrent.runs` | `10` | Maximum jobs polled from DB at once |
| `scheduler.queue.capacity` | `50` | Maximum capacity of the worker queue |
| `scheduler.poll.interval.ms` | `1000` | Polling frequency for fetching due jobs |
| `scheduler.base.backoff.ms` | `1000` | Base delay for exponential backoff retries |
| `scheduler.max.backoff.ms` | `60000` | Maximum delay cap for retries |

## Running Tests

To run the unit and integration testing suite:

```bash
mvn test
```
