package com.salamu.menu.menu.internal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.salamu.menu.asset.api.AssetCatalog;
import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.PublicAsset;
import com.salamu.menu.menu.internal.application.PublishedMenuQueryService;
import com.salamu.menu.restaurant.api.DefaultRestaurantQuery;
import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.restaurant.api.RestaurantSnapshot;
import com.salamu.menu.restaurant.api.RestaurantStatus;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishedMenuPostgreSqlIntegrationTests {

    private static final UUID RESTAURANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MENU_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID BURGERS_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID PIZZA_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID BURGER_ID = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final UUID PIZZA_ITEM_ID = UUID.fromString("60000000-0000-4000-8000-000000000006");
    private static final UUID GROUP_ID = UUID.fromString("70000000-0000-4000-8000-000000000007");

    private PostgreSQLContainer postgres;
    private JdbcTemplate setupJdbc;
    private AtomicInteger menuSelectCount;
    private AtomicInteger assetBatchCount;
    private PublishedMenuQueryService service;

    @BeforeAll
    void startPostgreSqlAndMigrateToFinalSchema() {
        postgres = new PostgreSQLContainer("postgres:17.5-alpine3.21")
                .withDatabaseName("published_menu")
                .withUsername("published_menu")
                .withPassword("published_menu");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        setupJdbc = new JdbcTemplate(dataSource);
        menuSelectCount = new AtomicInteger();
        assetBatchCount = new AtomicInteger();
        DataSource countingDataSource = countingDataSource(dataSource, menuSelectCount);
        var repository = new JdbcPublishedMenuReadRepository(new JdbcTemplate(countingDataSource));
        DefaultRestaurantQuery restaurantQuery = () -> new RestaurantSnapshot(
                new RestaurantId(RESTAURANT_ID), "main", "Main restaurant", RestaurantStatus.ACTIVE);
        service = new PublishedMenuQueryService(restaurantQuery, repository, emptyAssetCatalog(assetBatchCount));
    }

    @AfterAll
    void stopPostgreSql() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetFinalTables() {
        setupJdbc.execute("TRUNCATE TABLE restaurants CASCADE");
        menuSelectCount.set(0);
        assetBatchCount.set(0);
        insertFixture();
    }

    @Test
    void readsFinalTablesInCanonicalOrderWithFiveQueriesAndOneAssetBatch() {
        var menu = service.current();

        assertThat(menu.currency()).isEqualTo("RUB");
        assertThat(menu.categories()).extracting(category -> category.publicId())
                .containsExactly("burgers", "pizza");
        assertThat(menu.items()).extracting(item -> item.publicId())
                .containsExactly("classic-burger", "pepperoni");
        assertThat(menu.items().getFirst().available()).isFalse();
        assertThat(menu.items().getFirst().modifierGroups()).isEmpty();
        assertThat(menu.items().getLast().modifierGroups()).singleElement().satisfies(group -> {
            assertThat(group.publicId()).isEqualTo("size");
            assertThat(group.options()).extracting(option -> option.publicId())
                    .containsExactly("large", "small");
            assertThat(group.options()).extracting(option -> option.priceDeltaMinor())
                    .containsExactly(10_000L, -5_000L);
        });
        assertThat(menuSelectCount).hasValue(5);
        assertThat(assetBatchCount).hasValue(1);
    }

    @Test
    void addingMoreItemsAndModifiersDoesNotIncreaseQueryCount() {
        setupJdbc.update("""
                INSERT INTO menu_items (
                    id, menu_version_id, category_id, public_id, name,
                    price_minor, currency, available, sort_order
                ) VALUES (
                    '80000000-0000-4000-8000-000000000008', ?::uuid, ?::uuid,
                    'margherita', 'Margherita', 45000, 'RUB', true, 5
                )
                """, MENU_ID, PIZZA_ID);
        setupJdbc.update("""
                INSERT INTO menu_modifier_groups (
                    id, menu_version_id, menu_item_id, public_id, name,
                    min_selections, max_selections, sort_order
                ) VALUES (
                    '90000000-0000-4000-8000-000000000009', ?::uuid,
                    '80000000-0000-4000-8000-000000000008',
                    'crust', 'Crust', 1, 1, 0
                )
                """, MENU_ID);
        setupJdbc.update("""
                INSERT INTO menu_modifier_options (
                    id, menu_version_id, modifier_group_id, public_id, name,
                    price_delta_minor, available, sort_order
                ) VALUES (
                    'a0000000-0000-4000-8000-00000000000a', ?::uuid,
                    '90000000-0000-4000-8000-000000000009',
                    'thin', 'Thin', 0, true, 0
                )
                """, MENU_ID);

        var menu = service.current();

        assertThat(menu.items()).hasSize(3);
        assertThat(menuSelectCount).hasValue(5);
        assertThat(assetBatchCount).hasValue(1);
    }

    private void insertFixture() {
        setupJdbc.update("""
                INSERT INTO restaurants (
                    id, external_key, slug, display_name, status, is_default
                ) VALUES (?::uuid, 'main', 'main', 'Main restaurant', 'active', true)
                """, RESTAURANT_ID);
        setupJdbc.update("""
                INSERT INTO menu_versions (
                    id, restaurant_id, version, status, published_at, currency
                ) VALUES (?::uuid, ?::uuid, 7, 'published', '2026-07-14T09:00:00Z', 'RUB')
                """, MENU_ID, RESTAURANT_ID);
        setupJdbc.update("""
                INSERT INTO menu_categories (
                    id, menu_version_id, public_id, name, sort_order, available
                ) VALUES
                    (?::uuid, ?::uuid, 'pizza', 'Pizza', 20, true),
                    (?::uuid, ?::uuid, 'burgers', 'Burgers', 10, true),
                    ('b0000000-0000-4000-8000-00000000000b', ?::uuid,
                     'hidden', 'Hidden', 0, false)
                """, PIZZA_ID, MENU_ID, BURGERS_ID, MENU_ID, MENU_ID);
        setupJdbc.update("""
                INSERT INTO menu_items (
                    id, menu_version_id, category_id, public_id, name,
                    price_minor, currency, available, sort_order
                ) VALUES
                    (?::uuid, ?::uuid, ?::uuid, 'pepperoni', 'Pepperoni',
                     59000, 'RUB', true, 10),
                    (?::uuid, ?::uuid, ?::uuid, 'classic-burger', 'Classic burger',
                     49000, 'RUB', false, 5),
                    ('c0000000-0000-4000-8000-00000000000c', ?::uuid,
                     'b0000000-0000-4000-8000-00000000000b', 'secret', 'Secret',
                     1, 'RUB', true, 0)
                """, PIZZA_ITEM_ID, MENU_ID, PIZZA_ID, BURGER_ID, MENU_ID, BURGERS_ID, MENU_ID);
        setupJdbc.update("""
                INSERT INTO menu_modifier_groups (
                    id, menu_version_id, menu_item_id, public_id, name,
                    min_selections, max_selections, sort_order
                ) VALUES (?::uuid, ?::uuid, ?::uuid, 'size', 'Size', 1, 1, 0)
                """, GROUP_ID, MENU_ID, PIZZA_ITEM_ID);
        setupJdbc.update("""
                INSERT INTO menu_modifier_options (
                    id, menu_version_id, modifier_group_id, public_id, name,
                    price_delta_minor, available, sort_order
                ) VALUES
                    ('d0000000-0000-4000-8000-00000000000d', ?::uuid, ?::uuid,
                     'small', 'Small', -5000, true, 10),
                    ('e0000000-0000-4000-8000-00000000000e', ?::uuid, ?::uuid,
                     'large', 'Large', 10000, false, 5)
                """, MENU_ID, GROUP_ID, MENU_ID, GROUP_ID);
    }

    private static AssetCatalog emptyAssetCatalog(AtomicInteger batchCount) {
        return new AssetCatalog() {
            @Override
            public PublicAsset requireActive(AssetId id) {
                throw new AssertionError("menu must resolve assets through the batch API");
            }

            @Override
            public Map<AssetId, PublicAsset> requireActive(Collection<AssetId> ids) {
                batchCount.incrementAndGet();
                assertThat(ids).isEmpty();
                return Map.of();
            }
        };
    }

    private static DataSource countingDataSource(DataSource delegate, AtomicInteger selectCount) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("getConnection") && result instanceof Connection connection) {
                            return countingConnection(connection, selectCount);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static Connection countingConnection(Connection delegate, AtomicInteger selectCount) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && (sql.contains("menu_versions")
                                    || sql.contains("menu_categories")
                                    || sql.contains("menu_items")
                                    || sql.contains("menu_modifier_groups")
                                    || sql.contains("menu_modifier_options"))) {
                        selectCount.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }
}
