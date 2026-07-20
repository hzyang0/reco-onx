package com.interview.minireco.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.interview.minireco.degradation.DegradationLevel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class FileConfigJournal implements ConfigJournal {
    private final Path path;
    private final Gson gson = new Gson();

    public FileConfigJournal(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<RuntimeConfigSnapshot> load() {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<RuntimeConfigSnapshot> snapshots = new ArrayList<>();
            long expectedVersion = 1;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    continue;
                }
                RuntimeConfigSnapshot snapshot;
                try {
                    snapshot = parse(line);
                } catch (RuntimeException e) {
                    if (index == lines.size() - 1) {
                        rewriteValidEntries(snapshots);
                        break; // A crash may leave only the final append truncated.
                    }
                    throw new ConfigPersistenceException("invalid config journal entry at line " + (index + 1), e);
                }
                if (snapshot.version() != expectedVersion) {
                    throw new ConfigPersistenceException(
                            "config journal version gap at line " + (index + 1)
                                    + ": expected=" + expectedVersion + ", actual=" + snapshot.version()
                    );
                }
                snapshots.add(snapshot);
                expectedVersion++;
            }
            return snapshots;
        } catch (IOException e) {
            throw new ConfigPersistenceException("failed to read config journal " + path, e);
        }
    }

    @Override
    public synchronized void append(RuntimeConfigSnapshot snapshot) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = (gson.toJson(snapshot.toMap()) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException e) {
            throw new ConfigPersistenceException("failed to append config journal " + path, e);
        }
    }

    @Override
    public String description() {
        return "file:" + path;
    }

    private RuntimeConfigSnapshot parse(String line) {
        JsonObject object = JsonParser.parseString(line).getAsJsonObject();
        return new RuntimeConfigSnapshot(
                object.get("version").getAsLong(),
                object.get("newPipelinePercent").getAsInt(),
                object.get("shadowPercent").getAsInt(),
                DegradationLevel.parse(object.get("degradationLevel").getAsString()),
                object.get("updatedBy").getAsString(),
                Instant.parse(object.get("updatedAt").getAsString())
        );
    }

    private void rewriteValidEntries(List<RuntimeConfigSnapshot> snapshots) {
        Path temp = path.resolveSibling(path.getFileName() + ".recovery.tmp");
        StringBuilder content = new StringBuilder();
        for (RuntimeConfigSnapshot snapshot : snapshots) {
            content.append(gson.toJson(snapshot.toMap())).append(System.lineSeparator());
        }
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ConfigPersistenceException("failed to recover truncated config journal " + path, e);
        }
    }
}
