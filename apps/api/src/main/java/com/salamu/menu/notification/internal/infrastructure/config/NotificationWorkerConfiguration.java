package com.salamu.menu.notification.internal.infrastructure.config;

import java.time.Clock;

import com.salamu.menu.notification.internal.application.RetryPolicy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true")
class NotificationWorkerConfiguration {

    @Bean
    @Qualifier("notificationWorkerClock")
    Clock notificationWorkerClock() {
        return Clock.systemUTC();
    }

    @Bean
    RetryPolicy notificationRetryPolicy(WorkerProperties properties) {
        return RetryPolicy.production(
                properties.initialBackoff(),
                properties.maxBackoff(),
                properties.jitterRatio());
    }

    @Bean(name = "notificationWorkerExecutor")
    ThreadPoolTaskExecutor notificationWorkerExecutor(WorkerProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("notification-worker-");
        executor.setCorePoolSize(properties.concurrency());
        executor.setMaxPoolSize(properties.concurrency());
        executor.setQueueCapacity(properties.concurrency());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setAwaitTerminationSeconds(Math.toIntExact(Math.min(
                Integer.MAX_VALUE,
                Math.max(1, properties.shutdownTimeout().toSeconds()))));
        executor.setAcceptTasksAfterContextClose(false);
        return executor;
    }
}
