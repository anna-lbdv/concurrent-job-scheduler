package dev.jobscheduler.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;

public class HttpCallHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpCallHandler.class);
    private static final int MAX_RESPONSE_SIZE = 4096;
    private static final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public HttpCallHandler() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean supports(String type) {
        return "http_call".equals(type);
    }

    @Override
    public String execute(String payload) throws Exception {
        JsonNode node = mapper.readTree(payload);

        String url = node.get("url").asText();
        String method = node.has("method") ? node.get("method").asText("GET") : "GET";
        String body = node.has("body") ? node.get("body").asText() : null;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30));

        if (node.has("headers")) {
            JsonNode headers = node.get("headers");
            Iterator<Map.Entry<String, JsonNode>> it = headers.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                builder.header(entry.getKey(), entry.getValue().asText());
            }
        }

        builder.method(method, body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseBody = response.body();
        if (responseBody != null && responseBody.length() > MAX_RESPONSE_SIZE) {
            responseBody = responseBody.substring(0, MAX_RESPONSE_SIZE) + "...(truncated)";
        }

        log.debug("HTTP {} {} -> {} ({} chars)", method, url, statusCode,
                responseBody == null ? 0 : responseBody.length());

        if (statusCode >= 400) {
            throw new RuntimeException("HTTP " + statusCode + ": " + responseBody);
        }
        return statusCode + " " + responseBody;
    }
}
