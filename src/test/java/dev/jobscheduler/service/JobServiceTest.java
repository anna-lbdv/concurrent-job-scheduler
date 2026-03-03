package dev.jobscheduler.service;

import dev.jobscheduler.domain.Job;
import dev.jobscheduler.domain.JobStatus;
import dev.jobscheduler.domain.ScheduleType;
import dev.jobscheduler.engine.WorkerPool;
import dev.jobscheduler.persistence.JobRepository;
import dev.jobscheduler.persistence.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobServiceTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private RunRepository runRepository;
    @Mock
    private WorkerPool workerPool;

    private JobService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new JobService(jobRepository, runRepository, workerPool, 30000, 3, 1000);
    }

    @Test
    void createJob_requiresNameTypeAndSchedule() {
        Job job = new Job();
        assertThatThrownBy(() -> service.createJob(job))
                .isInstanceOf(JobService.ValidationException.class);

        job.setName("test");
        job.setType("http_call");
        job.setScheduleType(ScheduleType.FIXED_DELAY);
        assertThatThrownBy(() -> service.createJob(job))
                .isInstanceOf(JobService.ValidationException.class)
                .hasMessageContaining("intervalMs is required for periodic jobs");

        job.setIntervalMs(5000L);
        Job created = service.createJob(job);

        assertThat(created.getTimeoutMs()).isEqualTo(30000);
        assertThat(created.getMaxRetries()).isEqualTo(3);
        verify(jobRepository).save(job);
    }

    @Test
    void pauseJob_shouldUpdateStatusToPaused() {
        Job job = new Job();
        job.setId("j1");
        job.setStatus(JobStatus.SCHEDULED);
        when(jobRepository.findById("j1")).thenReturn(Optional.of(job));

        service.pauseJob("j1");

        assertThat(job.getStatus()).isEqualTo(JobStatus.PAUSED);
        verify(jobRepository).update(job);
    }

    @Test
    void resumeJob_shouldUpdateStatusToScheduled() {
        Job job = new Job();
        job.setId("j1");
        job.setStatus(JobStatus.PAUSED);
        job.setScheduleType(ScheduleType.ONCE);
        when(jobRepository.findById("j1")).thenReturn(Optional.of(job));

        service.resumeJob("j1");

        assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        verify(jobRepository).update(job);
    }

    @Test
    void cancelJob_shouldSetRunningJobCancelRequested() {
        Job job = new Job();
        job.setId("j1");
        job.setStatus(JobStatus.RUNNING);
        when(jobRepository.findById("j1")).thenReturn(Optional.of(job));

        service.cancelJob("j1");

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.isCancelRequested()).isTrue();
        verify(jobRepository).update(job);
    }

    @Test
    void cancelJob_shouldSetScheduledJobCancelled() {
        Job job = new Job();
        job.setId("j1");
        job.setStatus(JobStatus.SCHEDULED);
        when(jobRepository.findById("j1")).thenReturn(Optional.of(job));

        service.cancelJob("j1");

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.isCancelRequested()).isFalse();
        verify(jobRepository).update(job);
    }
}
