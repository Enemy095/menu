package com.salamu.menu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.salamu.menu.tenancy.internal.application.TenantRegistry;
import com.salamu.menu.tenancy.api.TenantAssetService;

@ActiveProfiles("test")
@MockitoBean(types = {TenantRegistry.class, JdbcOperations.class, JdbcClient.class, TenantAssetService.class})
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
class MenuPlatformApplicationTests {

    @Test
    void contextLoads() {
    }
}
