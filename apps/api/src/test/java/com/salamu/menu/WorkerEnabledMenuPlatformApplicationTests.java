package com.salamu.menu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

@ActiveProfiles("test")
@MockitoBean(types = {JdbcTemplate.class, JdbcClient.class, PlatformTransactionManager.class})
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "app.worker.enabled=true",
        "app.notification.telegram.enabled=true",
        "app.notification.telegram.api-base-url=https://api.telegram.org",
        "app.notification.telegram.bot-token=123456:test-token",
        "app.notification.telegram.chat-id=-1001234567890",
        "app.notification.telegram.destination-secret-reference=secret://application/telegram/orders"
})
class WorkerEnabledMenuPlatformApplicationTests {

    @Test
    void workerAndTelegramEnabledContextStarts() {
    }
}
