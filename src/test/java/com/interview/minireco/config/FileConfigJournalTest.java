package com.interview.minireco.config;

import com.interview.minireco.degradation.DegradationLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileConfigJournalTest {
    @TempDir
    Path tempDirectory;

    @Test
    void shouldRecoverVersionAndAuditHistoryAfterProcessRestart() {
        Path journalPath = tempDirectory.resolve("config.jsonl");
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC);
        DynamicConfigStore firstProcess = new DynamicConfigStore(clock, new FileConfigJournal(journalPath));
        firstProcess.update(1, 5, 20, DegradationLevel.LIGHT, "release-bot");
        firstProcess.update(2, 100, 0, DegradationLevel.NONE, "release-bot");

        DynamicConfigStore restartedProcess = new DynamicConfigStore(clock, new FileConfigJournal(journalPath));

        assertEquals(3, restartedProcess.get().version());
        assertEquals(100, restartedProcess.get().newPipelinePercent());
        assertEquals(3, restartedProcess.history().size());
        assertEquals("release-bot", restartedProcess.history().get(0).updatedBy());
    }

    @Test
    void shouldRepairOnlyTruncatedFinalEntryAndContinueAppending() throws Exception {
        Path journalPath = tempDirectory.resolve("config.jsonl");
        DynamicConfigStore store = new DynamicConfigStore(
                Clock.systemUTC(), new FileConfigJournal(journalPath)
        );
        store.update(1, 5, 0, DegradationLevel.NONE, "release-bot");
        Files.writeString(journalPath, "{\"version\":3", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        DynamicConfigStore recovered = new DynamicConfigStore(
                Clock.systemUTC(), new FileConfigJournal(journalPath)
        );
        recovered.update(2, 10, 0, DegradationLevel.NONE, "recovery-bot");
        DynamicConfigStore restartedAgain = new DynamicConfigStore(
                Clock.systemUTC(), new FileConfigJournal(journalPath)
        );

        assertEquals(3, restartedAgain.get().version());
        assertEquals(10, restartedAgain.get().newPipelinePercent());
        assertFalse(Files.readString(journalPath).contains("{\"version\":3{\""));
    }

    @Test
    void shouldRejectVersionGapInMiddleOfJournal() throws Exception {
        Path journalPath = tempDirectory.resolve("broken.jsonl");
        Files.writeString(journalPath, """
                {"version":1,"newPipelinePercent":100,"shadowPercent":0,"degradationLevel":"NONE","updatedBy":"bootstrap","updatedAt":"2026-07-20T10:00:00Z"}
                {"version":3,"newPipelinePercent":5,"shadowPercent":0,"degradationLevel":"NONE","updatedBy":"bad","updatedAt":"2026-07-20T10:01:00Z"}
                {"version":4,"newPipelinePercent":10,"shadowPercent":0,"degradationLevel":"NONE","updatedBy":"bad","updatedAt":"2026-07-20T10:02:00Z"}
                """, StandardCharsets.UTF_8);

        assertThrows(ConfigPersistenceException.class, () -> new FileConfigJournal(journalPath).load());
    }
}
