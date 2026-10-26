package com.salamu.menu.dataimport.api;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal non-web application context for the one-time approved dataset publication.
 *
 * <p>The importer intentionally does not scan the platform application root. That keeps servlet
 * security, controllers, workers, JPA repositories, Flyway and observability exporters outside the
 * command process while retaining the JDBC read projections used to verify public representation
 * hashes after publication.</p>
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    JdbcClientAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    TransactionAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    ValidationAutoConfiguration.class
})
@ComponentScan(basePackages = {
    "com.salamu.menu.dataimport.internal",
    "com.salamu.menu.asset.internal",
    "com.salamu.menu.restaurant.internal.application",
    "com.salamu.menu.restaurant.internal.infrastructure.persistence",
    "com.salamu.menu.site.internal.application",
    "com.salamu.menu.site.internal.infrastructure.persistence",
    "com.salamu.menu.menu.internal.application",
    "com.salamu.menu.menu.internal.infrastructure.persistence"
})
public final class StambulDataImportApplication {
}
