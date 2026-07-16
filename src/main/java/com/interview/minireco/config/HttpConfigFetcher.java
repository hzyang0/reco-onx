package com.interview.minireco.config;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpConfigFetcher implements ConfigFetcher {
    private final HttpClient client;
    private final URI uri;
    private final Gson gson = new Gson();

    public HttpConfigFetcher(String baseUrl) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        this.uri = URI.create(baseUrl.replaceAll("/$", "") + "/api/config");
    }

    @Override
    public RuntimeConfigSnapshot fetch() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("config center returned HTTP " + response.statusCode());
        }
        ConfigPayload payload = gson.fromJson(response.body(), ConfigPayload.class);
        if (payload == null) {
            throw new IllegalArgumentException("config payload is empty");
        }
        return new RuntimeConfigSnapshot(
                payload.version,
                payload.newPipelinePercent,
                payload.shadowPercent,
                com.interview.minireco.degradation.DegradationLevel.parse(payload.degradationLevel),
                payload.updatedBy,
                java.time.Instant.parse(payload.updatedAt)
        );
    }

    private static final class ConfigPayload {
        long version;
        int newPipelinePercent;
        int shadowPercent;
        String degradationLevel;
        String updatedBy;
        String updatedAt;
    }
}
