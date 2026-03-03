package dev.jobscheduler.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopSleepHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(NoopSleepHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String type) {
        return "noop_sleep".equals(type);
    }

    @Override
    public String execute(String payload) throws Exception {
        long sleepMs = 0;
        if (payload != null && !payload.isBlank()) {
            JsonNode node = mapper.readTree(payload);
            if (node.has("sleep_ms")) {
                sleepMs = node.get("sleep_ms").asLong(0);
            }
        }
        if (sleepMs > 0) {
            log.debug("Sleeping for {} ms", sleepMs);
            Thread.sleep(sleepMs);
        }
        if (Thread.interrupted()) {
            throw new InterruptedException("Cancelled during sleep");
        }
        return "ok";
    }
}
