package dev.jobscheduler.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.jobscheduler.metrics.MetricsCollector;

import java.io.IOException;

public class MetricsController implements HttpHandler {

    private final MetricsCollector metrics;

    public MetricsController(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            ApiResponse.error(exchange, 405, "Method not allowed");
            return;
        }
        String json = metrics.toJson();
        byte[] bytes = json.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
