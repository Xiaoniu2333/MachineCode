package com.Xiaoniu2333.mlky.machinecode.paper;

import com.Xiaoniu2333.mlky.machinecode.common.Platform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite persistence.
 *
 * <p>The main table intentionally has exactly the five requested columns, in this order:</p>
 * <ol>
 *   <li>player name</li>
 *   <li>player UUID</li>
 *   <li>system platform</li>
 *   <li>machine code</li>
 *   <li>banned ({@code true}/{@code false})</li>
 * </ol>
 *
 * <p>The primary key is (uuid, machine code), so a player changing hardware keeps a history and
 * {@code /mcode <机器码>} can find every account that used that hardware. SQLite's implicit
 * {@code rowid} is used as a monotonically increasing "last seen" ordering value, avoiding an
 * extra column in the requested schema. IP addresses live in an auxiliary table because they are
 * not one of the five requested columns.</p>
 */
public final class MachineCodeRepository implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(MachineCodeRepository.class.getName());

    private final Path databasePath;
    private Connection connection;

    public MachineCodeRepository(final Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath().normalize();
    }

    public synchronized void open() {
        if (this.connection != null) {
            return;
        }
        try {
            Files.createDirectories(this.databasePath.getParent());
            Class.forName("org.sqlite.JDBC");
            final String url = "jdbc:sqlite:" + this.databasePath.toString().replace('\\', '/');
            this.connection = DriverManager.getConnection(url);
            try (Statement statement = this.connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS machinecode_players (
                            player_name TEXT NOT NULL,
                            player_uuid TEXT NOT NULL,
                            platform TEXT NOT NULL,
                            machine_code TEXT NOT NULL,
                            banned TEXT NOT NULL DEFAULT 'false'
                                CHECK (banned IN ('true', 'false')),
                            PRIMARY KEY (player_uuid, machine_code)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_machinecode_players_code
                        ON machinecode_players(machine_code)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_machinecode_players_name
                        ON machinecode_players(player_name COLLATE NOCASE)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS machinecode_ips (
                            player_uuid TEXT PRIMARY KEY,
                            player_name TEXT NOT NULL,
                            last_ip TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS machinecode_bans (
                            machine_code TEXT PRIMARY KEY,
                            banned_at INTEGER NOT NULL,
                            banned_by TEXT
                        )
                        """);
                statement.execute("PRAGMA user_version=1");
            }
        } catch (final Exception exception) {
            throw new StorageException("Unable to open SQLite database", exception);
        }
    }

    public synchronized void saveIp(final UUID playerId, final String playerName,
                                    final String ip) {
        final String sql = """
                INSERT INTO machinecode_ips(player_uuid, player_name, last_ip, updated_at)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    player_name = excluded.player_name,
                    last_ip = excluded.last_ip,
                    updated_at = excluded.updated_at
                """;
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerName);
            statement.setString(3, ip);
            statement.setLong(4, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (final SQLException exception) {
            throw new StorageException("Unable to save player IP", exception);
        }
    }

    public synchronized Optional<String> findIp(final UUID playerId) {
        final String sql = "SELECT last_ip FROM machinecode_ips WHERE player_uuid = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.ofNullable(result.getString(1))
                        : Optional.empty();
            }
        } catch (final SQLException exception) {
            throw new StorageException("Unable to read player IP", exception);
        }
    }

    /**
     * Stores a player's latest report for one machine code.
     *
     * <p>The previous row (if any) is deleted and re-inserted inside a transaction. SQLite reuses
     * a rowid on {@code UPDATE}, so an <code>ON CONFLICT DO UPDATE</code> would make a player who
     * switches A -&gt; B -&gt; A still look like they are on B when ordering by rowid. Deleting and
     * re-inserting gives the current row a fresh rowid and preserves the five-column schema.</p>
     *
     * @return whether the machine code is banned after the write
     */
    public synchronized boolean saveReport(final UUID playerId, final String playerName,
                                           final Platform platform, final String machineCode) {
        final boolean banned = this.isBanned(machineCode);
        final String delete = """
                DELETE FROM machinecode_players
                WHERE player_uuid = ? AND machine_code = ?
                """;
        final String insert = """
                INSERT INTO machinecode_players(
                    player_name, player_uuid, platform, machine_code, banned
                ) VALUES(?, ?, ?, ?, ?)
                """;
        try {
            this.inTransaction(() -> {
                try (PreparedStatement deleteStatement = connection.prepareStatement(delete);
                     PreparedStatement insertStatement = connection.prepareStatement(insert)) {
                    deleteStatement.setString(1, playerId.toString());
                    deleteStatement.setString(2, machineCode);
                    deleteStatement.executeUpdate();

                    insertStatement.setString(1, playerName);
                    insertStatement.setString(2, playerId.toString());
                    insertStatement.setString(3, platform.wireName());
                    insertStatement.setString(4, machineCode);
                    insertStatement.setString(5, banned ? "true" : "false");
                    insertStatement.executeUpdate();
                }
                return 1;
            });
            return banned || this.isBanned(machineCode);
        } catch (final RuntimeException exception) {
            throw exception instanceof StorageException storageException
                    ? storageException
                    : new StorageException("Unable to save machine code report", exception);
        }
    }

    /**
     * Finds the most recent machine report for a UUID.
     */
    public synchronized Optional<PlayerRecord> findLatestByPlayerId(final UUID playerId) {
        final String sql = """
                SELECT player_name, player_uuid, platform, machine_code, banned
                FROM machinecode_players
                WHERE player_uuid = ?
                ORDER BY rowid DESC
                LIMIT 1
                """;
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readPlayer(result));
            }
        } catch (final SQLException exception) {
            throw new StorageException("Unable to query machine code by player", exception);
        }
    }

    /**
     * Finds the most recent machine report for a player name.
     */
    public synchronized Optional<PlayerRecord> findLatestByPlayerName(final String playerName) {
        final String sql = """
                SELECT player_name, player_uuid, platform, machine_code, banned
                FROM machinecode_players
                WHERE player_name = ? COLLATE NOCASE
                ORDER BY rowid DESC
                LIMIT 1
                """;
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readPlayer(result));
            }
        } catch (final SQLException exception) {
            throw new StorageException("Unable to query machine code by player name", exception);
        }
    }

    /**
     * Loads the newest record for every known player UUID. Used to seed the PlaceholderAPI
     * cache without querying SQLite on the main thread for each placeholder request.
     */
    public synchronized List<PlayerRecord> findAllLatest() {
        final String sql = """
                SELECT player_name, player_uuid, platform, machine_code, banned
                FROM machinecode_players
                ORDER BY rowid ASC
                """;
        try (PreparedStatement statement = connection().prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            final Map<UUID, PlayerRecord> latest = new LinkedHashMap<>();
            while (result.next()) {
                final PlayerRecord record = readPlayer(result);
                latest.put(record.playerId(), record);
            }
            return new ArrayList<>(latest.values());
        } catch (final SQLException exception) {
            throw new StorageException("Unable to load placeholder player cache", exception);
        }
    }

    /**
     * Loads all last-known IPs for the PlaceholderAPI cache.
     */
    public synchronized Map<UUID, String> findAllIps() {
        final String sql = "SELECT player_uuid, last_ip FROM machinecode_ips";
        try (PreparedStatement statement = connection().prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            final Map<UUID, String> ips = new LinkedHashMap<>();
            while (result.next()) {
                try {
                    ips.put(UUID.fromString(result.getString(1)), result.getString(2));
                } catch (final IllegalArgumentException ignored) {
                    // Ignore a malformed UUID row; it cannot belong to a valid player.
                }
            }
            return ips;
        } catch (final SQLException exception) {
            throw new StorageException("Unable to load placeholder IP cache", exception);
        }
    }

    /**
     * Reverse lookup: all player names associated with a machine code, newest first.
     */
    public synchronized Optional<MachineLookup> findByMachineCode(final String machineCode) {
        final String sql = """
                SELECT player_name, player_uuid, platform, machine_code, banned
                FROM machinecode_players
                WHERE machine_code = ?
                ORDER BY rowid DESC
                """;
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, machineCode);
            try (ResultSet result = statement.executeQuery()) {
                final Set<String> names = new LinkedHashSet<>();
                Platform platform = null;
                boolean banned = this.isBanned(machineCode);
                boolean found = false;
                while (result.next()) {
                    found = true;
                    names.add(result.getString(1));
                    if (platform == null) {
                        platform = Platform.fromWireName(result.getString(3)).orElse(null);
                    }
                    banned = banned || "true".equals(result.getString(5));
                }
                if (!found && !banned) {
                    return Optional.empty();
                }
                return Optional.of(new MachineLookup(
                        machineCode,
                        platform,
                        new ArrayList<>(names),
                        banned
                ));
            }
        } catch (final SQLException exception) {
            throw new StorageException("Unable to reverse-lookup machine code", exception);
        }
    }

    public synchronized boolean isBanned(final String machineCode) {
        final String sql = """
                SELECT 1 FROM machinecode_bans WHERE machine_code = ?
                UNION ALL
                SELECT 1 FROM machinecode_players
                WHERE machine_code = ? AND banned = 'true'
                LIMIT 1
                """;
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, machineCode);
            statement.setString(2, machineCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (final SQLException exception) {
            throw new StorageException("Unable to query ban state", exception);
        }
    }

    /**
     * Bans a machine code even if it has no report yet.
     *
     * @return number of player rows updated
     */
    public synchronized int ban(final String machineCode, final String operator) {
        final String insertBan = """
                INSERT INTO machinecode_bans(machine_code, banned_at, banned_by)
                VALUES(?, ?, ?)
                ON CONFLICT(machine_code) DO UPDATE SET
                    banned_at = excluded.banned_at,
                    banned_by = excluded.banned_by
                """;
        final String updatePlayers = """
                UPDATE machinecode_players SET banned = 'true' WHERE machine_code = ?
                """;
        return inTransaction(() -> {
            try (PreparedStatement banStatement = connection.prepareStatement(insertBan);
                 PreparedStatement updateStatement = connection.prepareStatement(updatePlayers)) {
                banStatement.setString(1, machineCode);
                banStatement.setLong(2, Instant.now().toEpochMilli());
                banStatement.setString(3, operator);
                banStatement.executeUpdate();

                updateStatement.setString(1, machineCode);
                return updateStatement.executeUpdate();
            }
        });
    }

    /**
     * Removes a ban from a machine code and updates all historical rows.
     *
     * @return number of player rows updated
     */
    public synchronized int unban(final String machineCode) {
        final String deleteBan = "DELETE FROM machinecode_bans WHERE machine_code = ?";
        final String updatePlayers = """
                UPDATE machinecode_players SET banned = 'false' WHERE machine_code = ?
                """;
        return inTransaction(() -> {
            try (PreparedStatement deleteStatement = connection.prepareStatement(deleteBan);
                 PreparedStatement updateStatement = connection.prepareStatement(updatePlayers)) {
                deleteStatement.setString(1, machineCode);
                deleteStatement.executeUpdate();

                updateStatement.setString(1, machineCode);
                return updateStatement.executeUpdate();
            }
        });
    }

    /**
     * Bans by player: resolves the most recent machine code for a UUID.
     */
    public synchronized boolean isBannedForPlayer(final UUID playerId) {
        return this.findLatestByPlayerId(playerId)
                .map(record -> this.isBanned(record.machineCode()))
                .orElse(false);
    }

    private PlayerRecord readPlayer(final ResultSet result) throws SQLException {
        final UUID uuid = UUID.fromString(result.getString(2));
        final Platform platform = Platform.fromWireName(result.getString(3))
                .orElseThrow(() -> new StorageException("Unknown platform stored in database"));
        return new PlayerRecord(
                result.getString(1),
                uuid,
                platform,
                result.getString(4),
                "true".equals(result.getString(5))
        );
    }

    private int inTransaction(final SqlCall<Integer> call) {
        try {
            connection.setAutoCommit(false);
            try {
                final int result = call.call();
                connection.commit();
                return result;
            } catch (final Exception exception) {
                try {
                    connection.rollback();
                } catch (final SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new StorageException("SQLite statement failed", exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (final SQLException exception) {
            throw new StorageException("SQLite transaction failed", exception);
        }
    }

    private Connection connection() {
        if (this.connection == null) {
            throw new StorageException("SQLite database is not open");
        }
        return this.connection;
    }

    @Override
    public synchronized void close() {
        if (this.connection == null) {
            return;
        }
        try {
            this.connection.close();
        } catch (final SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to close SQLite database", exception);
        } finally {
            this.connection = null;
        }
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T call() throws Exception;
    }

    public record PlayerRecord(
            String playerName,
            UUID playerId,
            Platform platform,
            String machineCode,
            boolean banned
    ) {
    }

    public record MachineLookup(
            String machineCode,
            Platform platform,
            List<String> playerNames,
            boolean banned
    ) {
    }

    public static final class StorageException extends RuntimeException {
        public StorageException(final String message) {
            super(message);
        }

        public StorageException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
