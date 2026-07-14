package com.salamu.menu.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@SpringBootTest
class JpaMappingIntegrationTests {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgreSqlTestContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", PostgreSqlTestContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", PostgreSqlTestContainer.INSTANCE::getPassword);
    }

    @Autowired
    private Flyway flyway;

    @Test
    void flywayAndHibernateValidateTheProductionMappings() {
        var validation = flyway.validateWithResult();

        assertThat(validation.validationSuccessful).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
    }
}
