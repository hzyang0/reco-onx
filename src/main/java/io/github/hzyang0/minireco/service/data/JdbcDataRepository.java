package io.github.hzyang0.minireco.service.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
public final class JdbcDataRepository implements AutoCloseable {
    private final DatabaseConfig config;
    private final HikariDataSource dataSource;

    public JdbcDataRepository(DatabaseConfig config) {
        this.config = config;
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(config.jdbcUrl());
        poolConfig.setUsername(config.username());
        poolConfig.setPassword(config.password());
        poolConfig.setMaximumPoolSize(config.maximumPoolSize());
        poolConfig.setMinimumIdle(Math.min(2, config.maximumPoolSize()));
        poolConfig.setConnectionTimeout(config.connectionTimeoutMs());
        poolConfig.setValidationTimeout(Math.min(config.connectionTimeoutMs(), 1_000));
        poolConfig.setPoolName("mini-reco-db");
        this.dataSource = new HikariDataSource(poolConfig);
        Flyway.configure()
                .dataSource(dataSource)
                .locations(config.flywayLocations().split(","))
                .validateMigrationNaming(true)
                .load()
                .migrate();
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

    public boolean isHealthy() {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet result = statement.executeQuery()) {
            return result.next() && result.getInt(1) == 1;
        } catch (SQLException ignored) {
            return false;
        }
    }

    public PoolStats poolStats() {
        return new PoolStats(
                dataSource.getHikariPoolMXBean().getActiveConnections(),
                dataSource.getHikariPoolMXBean().getIdleConnections(),
                dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(),
                dataSource.getHikariPoolMXBean().getTotalConnections()
        );
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

    public void createConsoleUser(
            ConsoleUserProfile profile,
            List<String> eventTypes,
            String rankExperiment
    ) {
        String profileSql = "INSERT INTO user_profiles "
                + "(user_id, age, new_user, default_category, province, city, persona_name, persona_summary) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        String experimentSql = "INSERT INTO experiment_assignments "
                + "(user_id, scene, recall_exp, rank_exp) VALUES (?, ?, 'SELF_SERVICE', ?)";
        String eventSql = "INSERT INTO user_events "
                + "(user_id, category, event_type, event_time) VALUES (?, ?, ?, ?)";

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement profileStatement = connection.prepareStatement(profileSql);
                 PreparedStatement experimentStatement = connection.prepareStatement(experimentSql);
                 PreparedStatement eventStatement = connection.prepareStatement(eventSql)) {
                profileStatement.setLong(1, profile.userId());
                profileStatement.setInt(2, profile.age());
                profileStatement.setBoolean(3, profile.newUser());
                profileStatement.setString(4, profile.preferredCategory());
                profileStatement.setString(5, profile.province());
                profileStatement.setString(6, profile.city());
                profileStatement.setString(7, profile.personaName());
                profileStatement.setString(8, profile.personaSummary());
                profileStatement.executeUpdate();

                experimentStatement.setLong(1, profile.userId());
                experimentStatement.setString(2, profile.defaultScene());
                experimentStatement.setString(3, rankExperiment);
                experimentStatement.executeUpdate();

                long eventTime = Instant.now().getEpochSecond() - eventTypes.size();
                for (String eventType : eventTypes) {
                    eventStatement.setLong(1, profile.userId());
                    eventStatement.setString(2, profile.preferredCategory());
                    eventStatement.setString(3, eventType);
                    eventStatement.setLong(4, ++eventTime);
                    eventStatement.addBatch();
                }
                if (!eventTypes.isEmpty()) {
                    eventStatement.executeBatch();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw databaseFailure("create console user", e);
        }
    }

    public List<UserEvent> findEvents(long userId) {
        String sql = "SELECT user_id, category, event_type, event_time "
                + "FROM user_events WHERE user_id = ? ORDER BY event_time DESC LIMIT 500";
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

    public List<CatalogItem> findCatalogBySource(String source, String preferredCategory, int limit) {
        int preferredLimit = Math.max(1, limit * 3 / 4);
        int explorationLimit = limit - preferredLimit;
        String columns = "item_id, title, source, category, base_score, recall_reason ";
        String sql = "(SELECT " + columns + "FROM catalog_items "
                + "WHERE source = ? AND category = ? ORDER BY base_score DESC LIMIT ?) "
                + "UNION ALL "
                + "(SELECT " + columns + "FROM catalog_items "
                + "WHERE source = ? AND category <> ? ORDER BY base_score DESC LIMIT ?)";
        List<CatalogItem> items = new ArrayList<>();
        try (Connection connection = openConnection();
            PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source);
            statement.setString(2, preferredCategory);
            statement.setInt(3, preferredLimit);
            statement.setString(4, source);
            statement.setString(5, preferredCategory);
            statement.setInt(6, explorationLimit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new CatalogItem(
                            result.getLong("item_id"),
                            result.getString("title"),
                            result.getString("source"),
                        result.getString("category"),
                        result.getDouble("base_score"),
                        result.getString("recall_reason")
                    ));
                }
            }
            return List.copyOf(items);
        } catch (SQLException e) {
            throw databaseFailure("load catalog items", e);
        }
    }

    /**
     * Loads source-specific online state for all candidates in one SQL round trip.
     * This avoids N+1 queries while keeping goods, live and ad semantics separate.
     */
    public Map<Long, OnlineSnapshot> findOnlineSnapshots(List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        String sql = "SELECT c.item_id, c.source, "
                + "g.price, g.stock, g.sale_status, "
                + "l.room_id, l.anchor_id, l.heat, l.live_status, "
                + "a.creative_id, a.campaign_id, a.promoted_item_id, a.bid_cents, "
                + "a.remaining_budget_cents, a.delivery_status "
                + "FROM catalog_items c "
                + "LEFT JOIN goods_details g ON g.item_id = c.item_id "
                + "LEFT JOIN live_details l ON l.item_id = c.item_id "
                + "LEFT JOIN ad_creatives a ON a.item_id = c.item_id "
                + "WHERE c.item_id IN (" + placeholders + ")";
        Map<Long, OnlineSnapshot> snapshots = new HashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < itemIds.size(); index++) {
                statement.setLong(index + 1, itemIds.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long itemId = result.getLong("item_id");
                    snapshots.put(itemId, new OnlineSnapshot(
                            itemId,
                            result.getString("source"),
                            nullableInt(result, "price"),
                            nullableInt(result, "stock"),
                            result.getString("sale_status"),
                            result.getString("room_id"),
                            result.getString("anchor_id"),
                            nullableInt(result, "heat"),
                            result.getString("live_status"),
                            result.getString("creative_id"),
                            result.getString("campaign_id"),
                            nullableLong(result, "promoted_item_id"),
                            nullableInt(result, "bid_cents"),
                            nullableLong(result, "remaining_budget_cents"),
                            result.getString("delivery_status")
                    ));
                }
            }
            return Map.copyOf(snapshots);
        } catch (SQLException e) {
            throw databaseFailure("load online snapshots", e);
        }
    }

    public int appendUserEvents(
            long userId,
            List<Long> itemIds,
            String eventType,
            String requestId,
            String scene,
            long eventTime
    ) {
        String sql = "INSERT IGNORE INTO user_events "
                + "(user_id, item_id, category, event_type, event_time, request_id, scene) "
                + "SELECT ?, c.item_id, c.category, ?, ?, ?, ? FROM catalog_items c WHERE c.item_id = ?";
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 PreparedStatement activateUser = connection.prepareStatement(
                         "UPDATE user_profiles p JOIN catalog_items c ON c.item_id = ? "
                                 + "SET p.new_user = FALSE, p.default_category = c.category WHERE p.user_id = ?"
                 )) {
                for (Long itemId : itemIds) {
                    statement.setLong(1, userId);
                    statement.setString(2, eventType);
                    statement.setLong(3, eventTime);
                    statement.setString(4, requestId);
                    statement.setString(5, scene);
                    statement.setLong(6, itemId);
                    statement.addBatch();
                }
                int inserted = 0;
                for (int count : statement.executeBatch()) {
                    if (count > 0) {
                        inserted += count;
                    }
                }
                if (!"exposure".equals(eventType) && inserted > 0) {
                    activateUser.setLong(1, itemIds.get(itemIds.size() - 1));
                    activateUser.setLong(2, userId);
                    activateUser.executeUpdate();
                }
                connection.commit();
                return inserted;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw databaseFailure("append user events", e);
        }
    }

    /** Short-term conversation memory. Expiry keeps the table bounded without a separate cache. */
    public List<AgentConversation> findRecentAgentConversation(String sessionId, int limit) {
        String sql = "SELECT role_name, content, created_at FROM agent_conversations "
                + "WHERE session_id = ? AND expires_at > CURRENT_TIMESTAMP ORDER BY message_id DESC LIMIT ?";
        List<AgentConversation> result = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new AgentConversation(rows.getString("role_name"), rows.getString("content"),
                            rows.getTimestamp("created_at").toInstant()));
                }
            }
            java.util.Collections.reverse(result);
            return List.copyOf(result);
        } catch (SQLException e) {
            throw databaseFailure("load agent conversation", e);
        }
    }

    public void appendAgentConversation(String sessionId, long userId, String role, String content, int ttlHours) {
        String sql = "INSERT INTO agent_conversations (session_id, user_id, role_name, content, expires_at) "
                + "VALUES (?, ?, ?, ?, TIMESTAMPADD(HOUR, ?, CURRENT_TIMESTAMP))";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setLong(2, userId);
            statement.setString(3, role);
            statement.setString(4, content);
            statement.setInt(5, ttlHours);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw databaseFailure("append agent conversation", e);
        }
    }

    public Map<String, String> findAgentLongTermMemories(long userId) {
        String sql = "SELECT memory_key, memory_value FROM agent_long_term_memories WHERE user_id = ? "
                + "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)";
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString("memory_key"), rows.getString("memory_value"));
            }
            return Map.copyOf(result);
        } catch (SQLException e) {
            throw databaseFailure("load agent long-term memories", e);
        }
    }

    public void upsertAgentLongTermMemory(long userId, String key, String value, double confidence, String source) {
        String sql = "INSERT INTO agent_long_term_memories (user_id, memory_key, memory_value, confidence, source_name) "
                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE memory_value = VALUES(memory_value), "
                + "confidence = VALUES(confidence), source_name = VALUES(source_name), updated_at = CURRENT_TIMESTAMP";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, key);
            statement.setString(3, value);
            statement.setDouble(4, confidence);
            statement.setString(5, source);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw databaseFailure("upsert agent long-term memory", e);
        }
    }

    public int deleteExpiredAgentMemories() {
        try (Connection connection = openConnection();
             PreparedStatement conversation = connection.prepareStatement(
                     "DELETE FROM agent_conversations WHERE expires_at <= CURRENT_TIMESTAMP");
             PreparedStatement longTerm = connection.prepareStatement(
                     "DELETE FROM agent_long_term_memories WHERE expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP")) {
            return conversation.executeUpdate() + longTerm.executeUpdate();
        } catch (SQLException e) {
            throw databaseFailure("clean expired agent memories", e);
        }
    }

    private Integer nullableInt(ResultSet result, String name) throws SQLException {
        int value = result.getInt(name);
        return result.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet result, String name) throws SQLException {
        long value = result.getLong(name);
        return result.wasNull() ? null : value;
    }

    private Connection openConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private IllegalStateException databaseFailure(String operation, SQLException cause) {
        return new IllegalStateException(operation + " failed: " + cause.getMessage(), cause);
    }

    @Override
    public void close() {
        dataSource.close();
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
            String recallReason
    ) {
    }

    public record OnlineSnapshot(
            long itemId,
            String source,
            Integer price,
            Integer stock,
            String goodsStatus,
            String roomId,
            String anchorId,
            Integer heat,
            String liveStatus,
            String creativeId,
            String campaignId,
            Long promotedItemId,
            Integer bidCents,
            Long remainingBudgetCents,
            String adStatus
    ) {
    }

    public record PoolStats(int active, int idle, int pending, int total) {
    }

    public record AgentConversation(String role, String content, Instant createdAt) {
    }
}
