package com.banking.api_gateway.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
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

/**
 * Stand-in for one microservice behind the gateway. It records every request it receives
 * and, unless a test scripts another response, answers 200 with a JSON that says which
 * service got which method and URI.
 */
public final class StubService {

    /** A request as the service received it from the gateway. */
    public record ReceivedRequest(String method, String uri, Headers headers, String body) {
    }

    /** A scripted answer: status, extra headers and JSON body. */
    private record Answer(int status, Map<String, String> headers, String body) {
    }

    private final String name;
    private final HttpServer server;
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Answer answer;

    private StubService(String name) {
        this.name = name;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * Starts a stub that plays the service registered as {@code name}.
     *
     * @param name the {@code spring.application.name} of the service
     * @return the running stub
     */
    public static StubService start(String name) {
        return new StubService(name);
    }

    public String name() {
        return name;
    }

    /** Base URL of the stub, the URI the load balancer resolves {@code lb://<name>} to. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Answers every following request with this status, headers and body. */
    public void respondWith(int status, Map<String, String> headers, String body) {
        answer = new Answer(status, headers, body);
    }

    /** Forgets the received requests and goes back to the default answer. */
    public void reset() {
        requests.clear();
        answer = null;
    }

    public List<ReceivedRequest> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String uri = exchange.getRequestURI().toString();
        requests.add(new ReceivedRequest(exchange.getRequestMethod(), uri, exchange.getRequestHeaders(), body));

        Answer current = answer != null ? answer : new Answer(200, Map.of(), """
                {"service":"%s","method":"%s","uri":"%s"}""".formatted(name, exchange.getRequestMethod(), uri));
        byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        current.headers().forEach(exchange.getResponseHeaders()::add);
        exchange.sendResponseHeaders(current.status(), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
