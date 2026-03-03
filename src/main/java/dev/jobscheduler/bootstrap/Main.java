package dev.jobscheduler.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jobscheduler.api.ApiServer;
import dev.jobscheduler.api.JobController;
import dev.jobscheduler.api.MetricsController;
import dev.jobscheduler.engine.*;
import dev.jobscheduler.handler.HttpCallHandler;
import dev.jobscheduler.handler.JobHandler;
import dev.jobscheduler.handler.NoopSleepHandler;
import dev.jobscheduler.metrics.MetricsCollector;
import dev.jobscheduler.persistence.JobRepository;
import dev.jobscheduler.persistence.RunRepository;
import dev.jobscheduler.service.JobService;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AppConfig config = new AppConfig();

        DataSource dataSource = createDataSource(config);
        runMigrations(dataSource);

        JobRepository jobRepository = new JobRepository(dataSource);
        RunRepository runRepository = new RunRepository(dataSource);

        MetricsCollector metrics = new MetricsCollector();

        List<JobHandler> handlers = List.of(
                new HttpCallHandler(),
                new NoopSleepHandler());

        WorkerPool workerPool = new WorkerPool(
                config.workerThreads(), config.queueCapacity(),
                jobRepository, runRepository, handlers, metrics,
                config.baseBackoffMs(), config.maxBackoffMs());

        RecoveryService recovery = new RecoveryService(jobRepository, runRepository);
        recovery.recover();

        SchedulerEngine scheduler = new SchedulerEngine(
                jobRepository, workerPool,
                config.pollIntervalMs(), config.maxConcurrentRuns());
        scheduler.start();

        TimeoutWatcher timeoutWatcher = new TimeoutWatcher(
                runRepository, jobRepository, metrics,
                config.baseBackoffMs(), config.maxBackoffMs());
        timeoutWatcher.start(config.pollIntervalMs());

        JobService jobService = new JobService(
                jobRepository, runRepository, workerPool,
                config.defaultTimeoutMs(), config.defaultMaxRetries(), config.baseBackoffMs());

        JobController jobController = new JobController(jobService);
        MetricsController metricsController = new MetricsController(metrics);
        ApiServer apiServer = new ApiServer(config.serverPort(), jobController, metricsController);
        apiServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown initiated...");
            apiServer.stop();
            scheduler.shutdown();
            timeoutWatcher.shutdown();
            workerPool.shutdown(config.shutdownGraceMs());
            if (dataSource instanceof HikariDataSource hds) {
                hds.close();
            }
            log.info("Shutdown complete");
        }));

        log.info("Job Scheduler ready on port {}", config.serverPort());
    }

    private static DataSource createDataSource(AppConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.dbUrl());
        hc.setUsername(config.dbUser());
        hc.setPassword(config.dbPassword());
        hc.setMaximumPoolSize(config.dbPoolSize());
        hc.setMinimumIdle(2);
        return new HikariDataSource(hc);
    }

    private static void runMigrations(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        log.info("Database migrations applied");
    }
}
