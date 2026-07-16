package com.interview.minireco.ops;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpHealthProbe {
    private HttpHealthProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: HttpHealthProbe <health-url>");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(args[0]))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200 || !response.body().contains("\"status\":\"UP\"")) {
            throw new IllegalStateException(
                    "HTTP health check failed: status=" + response.statusCode() + " body=" + response.body()
            );
        }
        System.out.println("HTTP_HEALTH_OK " + args[0]);
    }
}
