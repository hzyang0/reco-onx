package io.github.hzyang0.minireco.service.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC repository for the local MySQL data set.
 *
 * <p>The application keeps this boundary separate from the orchestration
 * layer so a future RPC/feature-store client can replace it without changing
 * Operators or the DAG.</p>
 */
public final class JdbcDataRepository {
    private final DatabaseConfig config;

    public JdbcDataRepository(DatabaseConfig config) {
        this.config = config;
    }

    public void verifyConnection() {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet ignored = statement.executeQuery()) {
            // Opening and executing the query is the health check.
        } catch (SQLException e) {
            throw databaseFailure("verify database connection", e);
        }
    }

    public Optional<UserProfile> findUser(long userId) {
        String sql = "SELECT user_id, age, new_user, default_category, province, city "
                + "FROM user_profiles WHERE user_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserProfile(
                        result.getLong("user_id"),
                        result.getInt("age"),
                        result.getBoolean("new_user"),
                        result.getString("default_category"),
                        result.getString("province"),
                        result.getString("city")
                ));
            }
        } catch (SQLException e) {
            throw databaseFailure("load user profile", e);
        }
    }

    public List<ConsoleUserProfile> findAllConsoleUsers() {
        String sql = "SELECT p.user_id, p.age, p.new_user, p.default_category, "
                + "p.province, p.city, p.persona_name, p.persona_summary, "
                + "COALESCE(e.scene, 'mall') AS default_scene "
                + "FROM user_profiles p LEFT JOIN experiment_assignments e ON e.user_id = p.user_id "
                + "ORDER BY p.user_id";
        List<ConsoleUserProfile> profiles = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                profiles.add(new ConsoleUserProfile(
                        result.getLong("user_id"),
                        result.getInt("age"),
                        result.getBoolean("new_user"),
                        result.getString("default_category"),
                        result.getString("province"),
                        result.getString("city"),
                        result.getString("persona_name"),
                        result.getString("persona_summary"),
                        result.getString("default_scene")
                ));
            }
            return List.copyOf(profiles);
        } catch (SQLException e) {
            throw databaseFailure("load console user profiles", e);
        }
    }

    public long countCatalogItems() {
        String sql = "SELECT COUNT(*) FROM catalog_items";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        } catch (SQLException e) {
            throw databaseFailure("count catalog items", e);
        }
    }

    public List<UserEvent> findEvents(long userId) {
        String sql = "SELECT user_id, category, event_type, event_time "
                + "FROM user_events WHERE user_id = ? ORDER BY event_time DESC";
        List<UserEvent> events = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new UserEvent(
                            result.getLong("user_id"),
                            result.getString("category"),
                            result.getString("event_type"),
                            result.getLong("event_time")
                    ));
                }
            }
            return List.copyOf(events);
        } catch (SQLException e) {
            throw databaseFailure("load user events", e);
        }
    }

    public Optional<ExperimentAssignment> findExperiment(long userId, String scene) {
        String sql = "SELECT user_id, scene, recall_exp, rank_exp "
                + "FROM experiment_assignments WHERE user_id = ? AND scene = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, scene);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ExperimentAssignment(
                        result.getLong("user_id"),
                        result.getString("scene"),
                        result.getString("recall_exp"),
                        result.getString("rank_exp")
                ));
            }
        } catch (SQLException e) {
            throw databaseFailure("load experiment assignment", e);
        }
    }

    public List<CatalogItem> findCatalogBySource(String source) {
        String sql = "SELECT item_id, title, source, category, base_score, recall_reason, room_id, creative_id "
                + "FROM catalog_items WHERE source = ? ORDER BY base_score DESC";
        List<CatalogItem> items = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new CatalogItem(
                            result.getLong("item_id"),
                            result.getString("title"),
                            result.getString("source"),
                            result.getString("category"),
                            result.getDouble("base_score"),
                            result.getString("recall_reason"),
                            result.getString("room_id"),
                            result.getString("creative_id")
                    ));
                }
            }
            return List.copyOf(items);
        } catch (SQLException e) {
            throw databaseFailure("load catalog items", e);
        }
    }

    public Optional<Inventory> findInventory(long itemId) {
        String sql = "SELECT item_id, price, stock, status FROM inventory_snapshots WHERE item_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Inventory(
                        result.getLong("item_id"),
                        result.getInt("price"),
                        result.getInt("stock"),
                        result.getString("status")
                ));
            }
        } catch (SQLException e) {
            throw databaseFailure("load inventory", e);
        }
    }

    /**
     * Loads the inventory for all candidates in one SQL round trip.
     * This avoids the N+1 query pattern in the online-feature stage.
     */
    public Map<Long, Inventory> findInventoryByItemIds(List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        String sql = "SELECT item_id, price, stock, status FROM inventory_snapshots "
                + "WHERE item_id IN (" + placeholders + ")";
        Map<Long, Inventory> inventoryByItemId = new HashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < itemIds.size(); index++) {
                statement.setLong(index + 1, itemIds.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long itemId = result.getLong("item_id");
                    inventoryByItemId.put(itemId, new Inventory(
                            itemId,
                            result.getInt("price"),
                            result.getInt("stock"),
                            result.getString("status")
                    ));
                }
            }
            return Map.copyOf(inventoryByItemId);
        } catch (SQLException e) {
            throw databaseFailure("load inventory batch", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
    }

    private IllegalStateException databaseFailure(String operation, SQLException cause) {
        return new IllegalStateException(operation + " failed: " + cause.getMessage(), cause);
    }

    public record UserProfile(
            long userId,
            int age,
            boolean newUser,
            String defaultCategory,
            String province,
            String city
    ) {
    }

    public record UserEvent(long userId, String category, String eventType, long eventTime) {
    }

    public record ConsoleUserProfile(
            long userId,
            int age,
            boolean newUser,
            String preferredCategory,
            String province,
            String city,
            String personaName,
            String personaSummary,
            String defaultScene
    ) {
    }

    public record ExperimentAssignment(long userId, String scene, String recallExp, String rankExp) {
    }

    public record CatalogItem(
            long itemId,
            String title,
            String source,
            String category,
            double baseScore,
            String recallReason,
            String roomId,
            String creativeId
    ) {
    }

    public record Inventory(long itemId, int price, int stock, String status) {
    }
}
