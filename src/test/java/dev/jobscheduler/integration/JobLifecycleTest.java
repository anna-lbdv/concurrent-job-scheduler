package dev.jobscheduler.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jobscheduler.domain.Job;
import dev.jobscheduler.domain.JobStatus;
import dev.jobscheduler.domain.Run;
import dev.jobscheduler.domain.RunStatus;
import dev.jobscheduler.domain.ScheduleType;
import dev.jobscheduler.engine.*;
import dev.jobscheduler.handler.HttpCallHandler;
import dev.jobscheduler.handler.JobHandler;
import dev.jobscheduler.handler.NoopSleepHandler;
import dev.jobscheduler.metrics.MetricsCollector;
import dev.jobscheduler.persistence.JobRepository;
import dev.jobscheduler.persistence.RunRepository;
import dev.jobscheduler.service.JobService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

class JobLifecycleTest {

    private HikariDataSource dataSource;
    private JobRepository jobRepository;
    private RunRepository runRepository;
    private WorkerPool workerPool;
    private SchedulerEngine scheduler;
    private TimeoutWatcher timeoutWatcher;
    private JobService jobService;
    private MetricsCollector metrics;

    @BeforeEach
    void setUp() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:testdb_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1");
        hc.setUsername("sa");
        hc.setPassword("");
        dataSource = new HikariDataSource(hc);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        jobRepository = new JobRepository(dataSource);
        runRepository = new RunRepository(dataSource);
        metrics = new MetricsCollector();

        List<JobHandler> handlers = List.of(new NoopSleepHandler(), new HttpCallHandler());

        workerPool = new WorkerPool(4, 50, jobRepository, runRepository, handlers, metrics, 100, 5000);

        RecoveryService recovery = new RecoveryService(jobRepository, runRepository);
        recovery.recover();

        scheduler = new SchedulerEngine(jobRepository, workerPool, 100, 10);
        scheduler.start();

        timeoutWatcher = new TimeoutWatcher(runRepository, jobRepository, metrics, 100, 5000);
        timeoutWatcher.start(100);

        jobService = new JobService(jobRepository, runRepository, workerPool, 30000, 3, 100);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null)
            scheduler.shutdown();
        if (timeoutWatcher != null)
            timeoutWatcher.shutdown();
        if (workerPool != null)
            workerPool.shutdown(1000);
        if (dataSource != null)
            dataSource.close();
    }

    @Test
    void executeOnceJobSuccessfully() {
        Job job = new Job();
        job.setName("once-test");
        job.setType("noop_sleep");
        job.setScheduleType(ScheduleType.ONCE);
        job.setPayload("{\"sleep_ms\": 10}");
        job = jobService.createJob(job);

        final String jId = job.getId();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Job j = jobService.getJob(jId);
            assertThat(j.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

            List<Run> runs = jobService.getJobRuns(jId, 10);
            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        });
    }

    @Test
    void executeFixedDelayJobMultipleTimes() {
        Job job = new Job();
        job.setName("delay-test");
        job.setType("noop_sleep");
        job.setScheduleType(ScheduleType.FIXED_DELAY);
        job.setIntervalMs(200L);
        job.setPayload("{\"sleep_ms\": 10}");
        job = jobService.createJob(job);

        final String jId = job.getId();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<Run> runs = jobService.getJobRuns(jId, 10);
            assertThat(runs).hasSizeGreaterThanOrEqualTo(2);
            for (Run run : runs) {
                assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
            }
        });
    }

    @Test
    void runNowOverridesScheduleAndExecutesImmediately() {
        Job job = new Job();
        job.setName("delay-test");
        job.setType("noop_sleep");
        job.setScheduleType(ScheduleType.FIXED_DELAY);
        job.setIntervalMs(50000L);
        job.setPayload("{\"sleep_ms\": 10}");
        job = jobService.createJob(job);

        final String jId = job.getId();

        assertThat(jobService.getJobRuns(jId, 10)).isEmpty();

        Run forcedRun = jobService.runNow(jId);
        assertThat(forcedRun).isNotNull();

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<Run> runs = jobService.getJobRuns(jId, 10);
            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        });
    }

    @Test
    void triggerRetryOnFailure() {
        Job job = new Job();
        job.setName("fail-test");
        job.setType("noop_sleep");
        job.setScheduleType(ScheduleType.ONCE);
        job.setPayload("invalid_json_to_force_failure");
        job.setMaxRetries(2);
        job = jobService.createJob(job);

        final String jId = job.getId();
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Job j = jobService.getJob(jId);
            assertThat(j.getStatus()).isEqualTo(JobStatus.FAILED);

            List<Run> runs = jobService.getJobRuns(jId, 10);
            assertThat(runs).hasSize(2);
            assertThat(runs.get(0).getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(runs.get(1).getStatus()).isEqualTo(RunStatus.FAILED);
        });
    }
}
