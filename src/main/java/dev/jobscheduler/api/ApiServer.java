package dev.jobscheduler.api;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    private final HttpServer server;

    public ApiServer(int port, JobController jobController, MetricsController metricsController) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        server.createContext("/jobs", jobController);
        server.createContext("/metrics", metricsController);
    }

    public void start() {
        server.start();
        log.info("API server started on port {}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(2);
        log.info("API server stopped");
    }
}
