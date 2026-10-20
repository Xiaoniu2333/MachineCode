package com.Xiaoniu2333.mlky.machinecode.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.Xiaoniu2333.mlky.machinecode.common.Platform;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MachineCodeRepositoryTest {
    private static final String CODE_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CODE_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void mainTableHasExactlyTheFiveRequestedColumns(@TempDir final Path directory)
            throws Exception {
        final Path database = directory.resolve("machinecode.db");
        try (MachineCodeRepository repository = new MachineCodeRepository(database)) {
            repository.open();
        }

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toString().replace('\\', '/'));
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "PRAGMA table_info(machinecode_players)")) {
            final List<String> names = new java.util.ArrayList<>();
            while (result.next()) {
                names.add(result.getString("name"));
            }
            assertEquals(
                    List.of("player_name", "player_uuid", "platform", "machine_code", "banned"),
                    names);
        }
    }

    @Test
    void storesReportsAndSupportsTwoWayLookup(@TempDir final Path directory) {
        final UUID alex = UUID.randomUUID();
        final UUID steve = UUID.randomUUID();
        try (MachineCodeRepository repository = new MachineCodeRepository(
                directory.resolve("machinecode.db"))) {
            repository.open();
            repository.saveIp(alex, "Alex", "10.0.0.2");
            assertFalse(repository.saveReport(alex, "Alex", Platform.WINDOWS, CODE_A));
            assertFalse(repository.saveReport(steve, "Steve", Platform.LINUX, CODE_A));

            assertEquals("10.0.0.2", repository.findIp(alex).orElseThrow());
            final MachineCodeRepository.PlayerRecord alexRecord =
                    repository.findLatestByPlayerId(alex).orElseThrow();
            assertEquals(CODE_A, alexRecord.machineCode());
            assertEquals(Platform.WINDOWS, alexRecord.platform());
            assertFalse(alexRecord.banned());

            final MachineCodeRepository.MachineLookup lookup =
                    repository.findByMachineCode(CODE_A).orElseThrow();
            assertEquals(2, lookup.playerNames().size());
            assertTrue(lookup.playerNames().containsAll(List.of("Alex", "Steve")));
            assertEquals(Platform.LINUX, lookup.platform());

            repository.saveIp(steve, "Steve", "10.0.0.3");
            assertEquals(2, repository.findAllLatest().size());
            assertEquals("10.0.0.2", repository.findAllIps().get(alex));
            assertEquals("10.0.0.3", repository.findAllIps().get(steve));
        }
    }

    @Test
    void bansFutureReportsAndCanBeLifted(@TempDir final Path directory) {
        final UUID alex = UUID.randomUUID();
        try (MachineCodeRepository repository = new MachineCodeRepository(
                directory.resolve("machinecode.db"))) {
            repository.open();
            repository.saveReport(alex, "Alex", Platform.WINDOWS, CODE_A);

            assertEquals(1, repository.ban(CODE_A, "Console"));
            assertTrue(repository.isBanned(CODE_A));
            assertTrue(repository.findLatestByPlayerId(alex).orElseThrow().banned());

            // A machine code banned before it was ever seen must still catch a later report.
            assertEquals(0, repository.ban(CODE_B, "Console"));
            final UUID newcomer = UUID.randomUUID();
            assertTrue(repository.saveReport(newcomer, "Newcomer", Platform.MACOS, CODE_B));
            assertTrue(repository.findLatestByPlayerId(newcomer).orElseThrow().banned());

            assertEquals(1, repository.unban(CODE_A));
            assertFalse(repository.isBanned(CODE_A));
            assertFalse(repository.findLatestByPlayerId(alex).orElseThrow().banned());
        }
    }

    @Test
    void hardwareChangesKeepHistoryAndLatestWins(@TempDir final Path directory) {
        final UUID alex = UUID.randomUUID();
        try (MachineCodeRepository repository = new MachineCodeRepository(
                directory.resolve("machinecode.db"))) {
            repository.open();
            repository.saveReport(alex, "Alex", Platform.WINDOWS, CODE_A);
            repository.saveReport(alex, "Alex", Platform.WINDOWS, CODE_B);

            assertEquals(CODE_B, repository.findLatestByPlayerId(alex).orElseThrow().machineCode());
            assertTrue(repository.findByMachineCode(CODE_A).isPresent());
            assertTrue(repository.findByMachineCode(CODE_B).isPresent());

            // Returning to an older machine must make it the current row again.
            repository.saveReport(alex, "Alex", Platform.WINDOWS, CODE_A);
            assertEquals(CODE_A, repository.findLatestByPlayerId(alex).orElseThrow().machineCode());
        }
    }
}
