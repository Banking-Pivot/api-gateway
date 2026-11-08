package com.banking.api_gateway.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Minimal stand-in for the external Config Server. It answers
 * {@code GET /{application}/{profiles}} with the Environment JSON the Spring Cloud Config
 * client expects, so tests exercise the real {@code spring.config.import=configserver:}
 * path without the real server.
 */
public final class StubConfigServer {

    private final HttpServer server;
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

    private StubConfigServer(Map<String, String> properties) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requestedPaths.add(path);

            String[] segments = path.split("/");
            String application = segments.length > 1 ? segments[1] : "application";
            String profiles = segments.length > 2 ? segments[2] : "default";

            byte[] body = environmentJson(application, profiles, properties).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    public static StubConfigServer start(Map<String, String> properties) {
        return new StubConfigServer(properties);
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public List<String> requestedPaths() {
        return List.copyOf(requestedPaths);
    }

    private static String environmentJson(String application, String profiles, Map<String, String> properties) {
        String source = properties.entrySet().stream()
                .map(entry -> quote(entry.getKey()) + ":" + quote(entry.getValue()))
                .collect(Collectors.joining(","));

        return "{\"name\":" + quote(application)
                + ",\"profiles\":[" + quote(profiles) + "]"
                + ",\"label\":null,\"version\":null,\"state\":null"
                + ",\"propertySources\":[{\"name\":" + quote("stub:" + application + ".yml")
                + ",\"source\":{" + source + "}}]}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
