package io.github.hzyang0.minireco.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardHttpHandlerTest {
    private HttpServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", new DashboardHttpHandler());
        server.start();
        client = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void rootShouldServeDashboardHtml() throws Exception {
        HttpResponse<String> response = get("/");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertTrue(response.body().contains("<title>Mini Reco 控制台</title>"));
        assertTrue(response.body().contains("/assets/dashboard.js"));
        assertTrue(response.body().contains("id=\"userProfile\""));
        assertTrue(response.body().contains("id=\"profileForm\""));
        assertTrue(response.body().contains("dashboard.js?v=5"));
        assertTrue(response.body().contains("id=\"feedbackStatus\""));
        assertTrue(response.body().contains("id=\"agentForm\""));
        assertTrue(response.headers().firstValue("Content-Security-Policy").isPresent());
    }

    @Test
    void assetsShouldUseExpectedContentTypes() throws Exception {
        HttpResponse<String> css = get("/assets/dashboard.css");
        HttpResponse<String> javascript = get("/assets/dashboard.js");
        HttpResponse<String> favicon = get("/favicon.svg");

        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("Content-Type").orElseThrow().startsWith("text/css"));
        assertTrue(css.body().contains(".pipeline"));
        assertEquals(200, javascript.statusCode());
        assertTrue(javascript.headers().firstValue("Content-Type").orElseThrow()
                .startsWith("application/javascript"));
        assertTrue(javascript.body().contains("runRecommendation"));
        assertTrue(javascript.body().contains("/api/console-data"));
        assertTrue(javascript.body().contains("/api/users"));
        assertTrue(javascript.body().contains("itemAttributeCells"));
        assertTrue(javascript.body().contains("创意 ID"));
        assertTrue(javascript.body().contains("/api/events"));
        assertTrue(javascript.body().contains("/api/agent/chat"));
        assertTrue(javascript.body().contains("runDiagnosis"));
        assertEquals(200, favicon.statusCode());
        assertEquals("image/svg+xml", favicon.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(favicon.body().contains("<svg"));
    }

    @Test
    void unknownResourceAndUnsupportedMethodShouldBeRejected() throws Exception {
        HttpResponse<String> missing = get("/missing");
        HttpRequest postRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> post = client.send(postRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("resource not found"));
        assertEquals(405, post.statusCode());
        assertTrue(post.body().contains("method not allowed"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
