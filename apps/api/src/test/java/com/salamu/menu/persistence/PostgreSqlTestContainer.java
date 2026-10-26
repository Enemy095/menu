package com.salamu.menu.persistence;

import org.testcontainers.postgresql.PostgreSQLContainer;
final class PostgreSqlTestContainer {

    static final PostgreSQLContainer INSTANCE = createAndStart();

    private PostgreSqlTestContainer() {
    }

    private static PostgreSQLContainer createAndStart() {
        var container = new PostgreSQLContainer("postgres:17.5-alpine3.21")
                .withDatabaseName("emenu_test")
                .withUsername("emenu_test")
                .withPassword("emenu_test");
        container.start();
        return container;
    }
}
