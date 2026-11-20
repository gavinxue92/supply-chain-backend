package dev.mattest.supplychain.database;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class InitialSchemaIntegrationTest {
    private static final String DATABASE = "supply_chain";

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName(DATABASE)
                    .withUsername("test")
                    .withPassword("test");

    @BeforeAll
    static void initializeDatabase() throws Exception {
        try (Connection connection = openConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/schema.sql"));
        }
    }

    @Test
    void createsEveryCoreTableWithRequiredEngineCharsetAndAuditColumns() throws Exception {
        Set<String> expectedTables = Set.of(
                "admin_users",
                "admin_sessions",
                "categories",
                "media_resources",
                "products",
                "product_specs",
                "product_keywords",
                "product_gallery",
                "customer_services",
                "global_contact_configs",
                "global_contact_services",
                "product_contact_services",
                "audit_logs");

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery("""
                     SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
                     FROM information_schema.TABLES
                     WHERE TABLE_SCHEMA = '%s'
                     """.formatted(DATABASE))) {
            Set<String> actualTables = new TreeSet<>();
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                actualTables.add(tableName);
                assertThat(tables.getString("ENGINE")).isEqualToIgnoringCase("InnoDB");
                assertThat(tables.getString("TABLE_COLLATION"))
                        .isEqualToIgnoringCase("utf8mb4_unicode_ci");
            }
            assertThat(actualTables).containsAll(expectedTables);
        }

        for (String table : expectedTables) {
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement();
                 ResultSet columns = statement.executeQuery("""
                         SELECT COLUMN_NAME
                         FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s'
                         """.formatted(DATABASE, table))) {
                Set<String> actualColumns = new TreeSet<>();
                while (columns.next()) {
                    actualColumns.add(columns.getString("COLUMN_NAME"));
                }
                assertThat(actualColumns)
                        .as("common audit columns on %s", table)
                        .contains("created_at", "updated_at", "is_deleted");
            }
        }
    }

    @Test
    void storesReferencePricesAsDecimalTenTwo() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("""
                     SELECT DATA_TYPE, NUMERIC_PRECISION, NUMERIC_SCALE
                     FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = '%s'
                       AND TABLE_NAME = 'product_specs'
                       AND COLUMN_NAME = 'reference_price'
                     """.formatted(DATABASE))) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getString("DATA_TYPE")).isEqualToIgnoringCase("decimal");
            assertThat(columns.getInt("NUMERIC_PRECISION")).isEqualTo(10);
            assertThat(columns.getInt("NUMERIC_SCALE")).isEqualTo(2);
        }

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO categories (name, parent_id, is_active, sort_order)
                    VALUES ('价格校验分类', NULL, TRUE, 0)
                    """);
            try (ResultSet category = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
                category.next();
                long categoryId = category.getLong(1);
                statement.executeUpdate("""
                        INSERT INTO products (name, status, category_id)
                        VALUES ('价格校验产品', 'DRAFT', %d)
                        """.formatted(categoryId));
            }
            long productId;
            try (ResultSet product = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
                product.next();
                productId = product.getLong(1);
            }

            assertThatThrownBy(() ->
                    statement.executeUpdate("""
                            INSERT INTO product_specs (product_id, name, reference_price)
                            VALUES (%d, '无效价格规格', 0)
                            """.formatted(productId)))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void preventsDuplicateTopLevelCategoryNamesUnderConfiguredCollation() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO categories (name, parent_id, is_active, sort_order)
                    VALUES ('肉类', NULL, TRUE, 0)
                    """);

            assertThatThrownBy(() ->
                    statement.executeUpdate("""
                            INSERT INTO categories (name, parent_id, is_active, sort_order)
                            VALUES ('肉类', NULL, TRUE, 1)
                            """))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void allowsReusingCategoryNameAfterSoftDeletion() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO categories (name, parent_id, is_active, sort_order)
                    VALUES ('可复用分类', NULL, TRUE, 0)
                    """);
            statement.executeUpdate("""
                    UPDATE categories SET is_deleted = TRUE
                    WHERE name = '可复用分类'
                    """);
            assertThat(statement.executeUpdate("""
                    INSERT INTO categories (name, parent_id, is_active, sort_order)
                    VALUES ('可复用分类', NULL, TRUE, 1)
                    """)).isEqualTo(1);
        }
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
