package dev.jobscheduler.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.jobscheduler.domain.Job;
import dev.jobscheduler.domain.Run;
import dev.jobscheduler.domain.ScheduleType;
import dev.jobscheduler.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JobController implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);
    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/jobs") && method.equals("POST")) {
                handleCreate(exchange);
            } else if (path.equals("/jobs") && method.equals("GET")) {
                handleList(exchange);
            } else if (path.matches("/jobs/[^/]+") && method.equals("GET")) {
                handleGet(exchange);
            } else if (path.matches("/jobs/[^/]+") && method.equals("DELETE")) {
                handleDelete(exchange);
            } else if (path.matches("/jobs/[^/]+/pause") && method.equals("POST")) {
                handlePause(exchange);
            } else if (path.matches("/jobs/[^/]+/resume") && method.equals("POST")) {
                handleResume(exchange);
            } else if (path.matches("/jobs/[^/]+/cancel") && method.equals("POST")) {
                handleCancel(exchange);
            } else if (path.matches("/jobs/[^/]+/run-now") && method.equals("POST")) {
                handleRunNow(exchange);
            } else {
                ApiResponse.error(exchange, 404, "Not found");
            }
        } catch (JobService.ValidationException e) {
            ApiResponse.error(exchange, 400, e.getMessage());
        } catch (JobService.NotFoundException e) {
            ApiResponse.error(exchange, 404, e.getMessage());
        } catch (JobService.StateConflictException e) {
            ApiResponse.error(exchange, 409, e.getMessage());
        } catch (JobService.QueueFullException e) {
            ApiResponse.error(exchange, 503, e.getMessage());
        } catch (Exception e) {
            log.error("Unhandled error in JobController", e);
            ApiResponse.error(exchange, 500, "Internal server error");
        }
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        JsonNode node = readBody(exchange);

        Job job = new Job();
        job.setName(textOrNull(node, "name"));
        job.setType(textOrNull(node, "type"));
        job.setPayload(textOrNull(node, "payload"));

        String schedType = textOrNull(node, "scheduleType");
        if (schedType != null) {
            job.setScheduleType(ScheduleType.valueOf(schedType.toUpperCase()));
        }

        if (node.has("startAt")) {
            job.setStartAt(Instant.parse(node.get("startAt").asText()));
        }
        if (node.has("intervalMs")) {
            job.setIntervalMs(node.get("intervalMs").asLong());
        }
        if (node.has("maxRetries")) {
            job.setMaxRetries(node.get("maxRetries").asInt());
        }
        if (node.has("retryBackoffMs")) {
            job.setRetryBackoffMs(node.get("retryBackoffMs").asLong());
        }
        if (node.has("timeoutMs")) {
            job.setTimeoutMs(node.get("timeoutMs").asLong());
        }

        Job created = jobService.createJob(job);
        ApiResponse.json(exchange, 201, jobToMap(created));
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        String id = extractId(exchange.getRequestURI().getPath());
        Job job = jobService.getJob(id);
        if (job == null) {
            ApiResponse.error(exchange, 404, "Job not found");
            return;
        }
        List<Run> runs = jobService.getJobRuns(id, 10);
        Map<String, Object> response = jobToMap(job);
        response.put("runs", runs.stream().map(this::runToMap).toList());
        ApiResponse.json(exchange, 200, response);
    }

    private void handleList(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String status = params.get("status");
        String type = params.get("type");
        String name = params.get("name");
        int limit = parseInt(params.get("limit"), 20);
        int offset = parseInt(params.get("offset"), 0);

        List<Job> jobs = jobService.listJobs(status, type, name, limit, offset);
        List<Map<String, Object>> list = jobs.stream().map(this::jobToMap).toList();
        ApiResponse.json(exchange, 200, Map.of("jobs", list, "count", list.size()));
    }

    private void handlePause(HttpExchange exchange) throws IOException {
        String id = extractActionId(exchange.getRequestURI().getPath());
        Job job = jobService.pauseJob(id);
        ApiResponse.json(exchange, 200, jobToMap(job));
    }

    private void handleResume(HttpExchange exchange) throws IOException {
        String id = extractActionId(exchange.getRequestURI().getPath());
        Job job = jobService.resumeJob(id);
        ApiResponse.json(exchange, 200, jobToMap(job));
    }

    private void handleCancel(HttpExchange exchange) throws IOException {
        String id = extractActionId(exchange.getRequestURI().getPath());
        Job job = jobService.cancelJob(id);
        ApiResponse.json(exchange, 200, jobToMap(job));
    }

    private void handleRunNow(HttpExchange exchange) throws IOException {
        String id = extractActionId(exchange.getRequestURI().getPath());
        Run run = jobService.runNow(id);
        if (run != null) {
            ApiResponse.json(exchange, 200, runToMap(run));
        } else {
            ApiResponse.json(exchange, 200, Map.of("status", "submitted"));
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        String id = extractId(exchange.getRequestURI().getPath());
        jobService.deleteJob(id);
        ApiResponse.json(exchange, 200, Map.of("deleted", true));
    }

    private String extractId(String path) {
        String[] parts = path.split("/");
        return parts[parts.length - 1];
    }

    private String extractActionId(String path) {
        String[] parts = path.split("/");
        return parts[parts.length - 2];
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return ApiResponse.mapper().readTree(is);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null)
            return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null)
            return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Map<String, Object> jobToMap(Job job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.getId());
        m.put("name", job.getName());
        m.put("type", job.getType());
        m.put("payload", job.getPayload());
        m.put("scheduleType", job.getScheduleType());
        m.put("startAt", job.getStartAt());
        m.put("intervalMs", job.getIntervalMs());
        m.put("maxRetries", job.getMaxRetries());
        m.put("retryBackoffMs", job.getRetryBackoffMs());
        m.put("timeoutMs", job.getTimeoutMs());
        m.put("status", job.getStatus());
        m.put("createdAt", job.getCreatedAt());
        m.put("updatedAt", job.getUpdatedAt());
        m.put("nextRunAt", job.getNextRunAt());
        m.put("attempts", job.getAttempts());
        m.put("lastError", job.getLastError());
        m.put("lastRunAt", job.getLastRunAt());
        m.put("lastFinishAt", job.getLastFinishAt());
        m.put("cancelRequested", job.isCancelRequested());
        return m;
    }

    private Map<String, Object> runToMap(Run run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.getRunId());
        m.put("jobId", run.getJobId());
        m.put("startedAt", run.getStartedAt());
        m.put("finishedAt", run.getFinishedAt());
        m.put("status", run.getStatus());
        m.put("attemptNumber", run.getAttemptNumber());
        m.put("errorMessage", run.getErrorMessage());
        m.put("durationMs", run.getDurationMs());
        return m;
    }
}
