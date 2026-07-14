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
        "app.worker.enabled=true"
})
class WorkerEnabledMenuPlatformApplicationTests {

    @Test
    void workerEnabledContextKeepsModuleSpecificClockInjectionUnambiguous() {
    }
}
