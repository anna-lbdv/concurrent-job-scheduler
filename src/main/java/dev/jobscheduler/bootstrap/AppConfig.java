package dev.jobscheduler.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {

    private final Properties props = new Properties();

    public AppConfig() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    public int getInt(String key, int def) {
        String val = props.getProperty(key);
        return val != null ? Integer.parseInt(val.trim()) : def;
    }

    public long getLong(String key, long def) {
        String val = props.getProperty(key);
        return val != null ? Long.parseLong(val.trim()) : def;
    }

    public String getString(String key, String def) {
        String val = props.getProperty(key);
        return val != null ? val.trim() : def;
    }

    public int serverPort() {
        return getInt("server.port", 8080);
    }

    public String dbUrl() {
        return getString("db.url", "jdbc:h2:mem:jobscheduler;DB_CLOSE_DELAY=-1");
    }

    public String dbUser() {
        return getString("db.user", "sa");
    }

    public String dbPassword() {
        return getString("db.password", "");
    }

    public int dbPoolSize() {
        return getInt("db.pool.size", 10);
    }

    public int workerThreads() {
        return getInt("scheduler.worker.threads", 4);
    }

    public int maxConcurrentRuns() {
        return getInt("scheduler.max.concurrent.runs", 10);
    }

    public int queueCapacity() {
        return getInt("scheduler.queue.capacity", 50);
    }

    public long pollIntervalMs() {
        return getLong("scheduler.poll.interval.ms", 1000);
    }

    public long defaultTimeoutMs() {
        return getLong("scheduler.default.timeout.ms", 30000);
    }

    public int defaultMaxRetries() {
        return getInt("scheduler.default.max.retries", 3);
    }

    public long baseBackoffMs() {
        return getLong("scheduler.base.backoff.ms", 1000);
    }

    public long maxBackoffMs() {
        return getLong("scheduler.max.backoff.ms", 60000);
    }

    public long shutdownGraceMs() {
        return getLong("scheduler.shutdown.grace.ms", 10000);
    }
}
