package com.salamu.menu.dataimport.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class StambulDataImportCommandTests {

    private static final DataImportRequest REQUEST = DataImportRequest.localTest(
            Path.of("bundle.json"), Path.of("."));

    @Test
    void authoritativeArgumentsDisableEveryImplicitWriteAndOverrideAllEndpoints() {
        String[] arguments = StambulDataImportCommand.authoritativeSpringArguments(environment(), REQUEST);

        assertThat(arguments).contains(
                "--spring.datasource.url=jdbc:postgresql://approved/import",
                "--spring.flyway.enabled=false",
                "--spring.sql.init.mode=never",
                "--spring.jpa.generate-ddl=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--app.data-import.enabled=true",
                "--spring.profiles.active=",
                "--spring.config.import=optional:",
                "--app.worker.enabled=false",
                "--app.assets.storage.endpoint=http://approved-storage:9000");
    }

    @Test
    void rejectsHostileInheritedEnvironmentWithoutEchoingValues() {
        var environment = new LinkedHashMap<>(environment());
        environment.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://attacker/redirect");
        environment.put("APP_WORKER_ENABLED", "true");

        assertThatThrownBy(() -> StambulDataImportCommand.rejectContaminatingEnvironment(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APP_WORKER_ENABLED")
                .hasMessageContaining("SPRING_DATASOURCE_URL")
                .hasMessageNotContaining("attacker")
                .hasMessageNotContaining("true");
    }

    @Test
    void rejectsHostileJvmSpringProperties() {
        var properties = new Properties();
        properties.setProperty("spring.datasource.url", "jdbc:postgresql://attacker/redirect");
        properties.setProperty("app.worker.enabled", "true");

        assertThatThrownBy(() -> StambulDataImportCommand.rejectContaminatingSystemProperties(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.worker.enabled")
                .hasMessageContaining("spring.datasource.url")
                .hasMessageNotContaining("attacker");
    }

    private static Map<String, String> environment() {
        return Map.of(
                "DATA_IMPORT_JDBC_URL", "jdbc:postgresql://approved/import",
                "DATA_IMPORT_DB_USER", "approved-user",
                "DATA_IMPORT_DB_PASSWORD", "approved-password",
                "DATA_IMPORT_S3_ENDPOINT", "http://approved-storage:9000",
                "DATA_IMPORT_S3_REGION", "us-east-1",
                "DATA_IMPORT_S3_BUCKET", "approved-bucket",
                "DATA_IMPORT_S3_ACCESS_KEY", "approved-access",
                "DATA_IMPORT_S3_SECRET_KEY", "approved-secret",
                "DATA_IMPORT_CDN_BASE_URL", "https://cdn.approved.test");
    }
}
