package io.github.hzyang0.minireco.service.data;

import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.downstream.impl.JdbcUserFeatureService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@Testcontainers
class JdbcDataRepositoryIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("mini_reco")
            .withUsername("mini_reco")
            .withPassword("mini_reco");

    private static JdbcDataRepository repository;

    @BeforeAll
    static void setUpRepository() {
        repository = new JdbcDataRepository(new DatabaseConfig(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword(),
                4,
                2_000,
                "filesystem:db/migration"
        ));
    }

    @AfterAll
    static void closeRepository() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void flywayShouldCreateIndependentSourceTables() throws Exception {
        assertEquals(300, repository.countCatalogItems());
        assertEquals(100, count("goods_details"));
        assertEquals(100, count("live_details"));
        assertEquals(100, count("ad_creatives"));
        assertTrue(repository.isHealthy());
    }

    @Test
    void feedbackShouldTurnColdStartUserIntoBehaviorUser() {
        int inserted = repository.appendUserEvents(
                1000L,
                List.of(11101L),
                "purchase",
                "integration-feedback-1",
                "mall",
                1_786_000_000L
        );

        UserFeature feature = new JdbcUserFeatureService(repository).getUserFeature(1000L);

        assertEquals(1, inserted);
        assertFalse(feature.isNewUser());
        assertEquals("digital", feature.getPreferredCategory());
    }

    private static long count(String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
