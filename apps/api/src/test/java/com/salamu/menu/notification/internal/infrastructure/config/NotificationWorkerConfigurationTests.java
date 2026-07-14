package com.salamu.menu.notification.internal.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import com.salamu.menu.notification.internal.application.RetryPolicy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class NotificationWorkerConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class, NotificationWorkerConfiguration.class)
            .withPropertyValues(
                    "app.worker.worker-id=test-worker",
                    "app.worker.poll-interval=1s",
                    "app.worker.batch-size=4",
                    "app.worker.concurrency=2",
                    "app.worker.max-destinations=2",
                    "app.worker.lease-duration=30s",
                    "app.worker.provider-timeout=5s",
                    "app.worker.max-attempts=4",
                    "app.worker.initial-backoff=1s",
                    "app.worker.max-backoff=1m",
                    "app.worker.jitter-ratio=0.2",
                    "app.worker.shutdown-timeout=5s");

    @Test
    void workerInfrastructureIsOptInAndAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(WorkerProperties.class);
            assertThat(context).doesNotHaveBean(RetryPolicy.class);
            assertThat(context).doesNotHaveBean("notificationWorkerExecutor");
        });
    }

    @Test
    void explicitEnableCreatesBoundedWorkerInfrastructure() {
        contextRunner.withPropertyValues("app.worker.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RetryPolicy.class);
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context).getBean("notificationWorkerExecutor", ThreadPoolTaskExecutor.class)
                    .satisfies(executor -> {
                        assertThat(executor.getCorePoolSize()).isEqualTo(2);
                        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
                    });
        });
    }

    @Test
    void invalidBoundConfigurationFailsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "app.worker.enabled=true",
                        "app.worker.provider-timeout=30s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkerProperties.class)
    static class PropertiesConfiguration {
    }
}
