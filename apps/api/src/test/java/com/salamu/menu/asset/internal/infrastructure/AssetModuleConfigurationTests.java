package com.salamu.menu.asset.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.salamu.menu.asset.api.AssetCatalog;
import com.salamu.menu.asset.internal.application.AssetMetadataRepository;
import com.salamu.menu.asset.internal.application.AssetObjectStorage;
import com.salamu.menu.asset.internal.application.AssetObjectWriter;

import software.amazon.awssdk.services.s3.S3Client;

class AssetModuleConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AssetModuleConfiguration.class,
                    AssetPropertiesConfiguration.class,
                    ExistingS3Candidate.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withPropertyValues(
                    "app.assets.storage.endpoint=http://127.0.0.1:9000",
                    "app.assets.storage.region=us-east-1",
                    "app.assets.storage.bucket=asset-bucket",
                    "app.assets.storage.access-key=runtime-access",
                    "app.assets.storage.secret-key=runtime-secret",
                    "app.assets.storage.path-style-access=true",
                    "app.assets.cdn-base-url=https://cdn.example.test");

    @Test
    void wiresRuntimeBeansWithoutCompetingWithAnExistingS3Candidate() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AssetCatalog.class);
            assertThat(context).hasSingleBean(AssetMetadataRepository.class);
            assertThat(context).hasSingleBean(AssetObjectStorage.class);
            assertThat(context).hasSingleBean(AssetObjectWriter.class);
            assertThat(context).hasBean(AssetModuleConfiguration.S3_CLIENT_BEAN_NAME);
            assertThat(context.getBean(S3Client.class)).isSameAs(context.getBean("existingS3Client"));
            assertThat(context.getBean(AssetProperties.class).toString())
                    .doesNotContain("runtime-access", "runtime-secret");
        });
    }

    @Test
    void invalidSecretFailsStartupValidationWithoutEchoingItsValue() {
        contextRunner
                .withPropertyValues("app.assets.storage.secret-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString()).doesNotContain("runtime-secret");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AssetProperties.class)
    static class AssetPropertiesConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class ExistingS3Candidate {

        @Bean
        S3Client existingS3Client() {
            return mock(S3Client.class);
        }
    }
}
