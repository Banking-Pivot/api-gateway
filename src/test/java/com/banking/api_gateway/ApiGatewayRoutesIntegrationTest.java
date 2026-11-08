package com.banking.api_gateway;

import com.banking.api_gateway.support.StubConfigServer;
import com.banking.api_gateway.support.StubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the gateway on a random port with its real {@code spring.config.import}. A stub
 * Config Server serves the routes of {@code stub-config/api-gateway.yml}, and three stub
 * services stand in for customer-service, account-service and credit-service, found by the
 * load balancer through the simple discovery client instead of Eureka.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayRoutesIntegrationTest {

    private static final StubService CUSTOMERS = StubService.start("customer-service");
    private static final StubService ACCOUNTS = StubService.start("account-service");
    private static final StubService CREDITS = StubService.start("credit-service");
    private static final List<StubService> SERVICES = List.of(CUSTOMERS, ACCOUNTS, CREDITS);
    private static final StubConfigServer CONFIG_SERVER;

    static {
        Map<String, String> properties = new HashMap<>(yaml("stub-config/api-gateway.yml"));
        SERVICES.forEach(service -> properties.put(
                "spring.cloud.discovery.client.simple.instances." + service.name() + "[0].uri", service.url()));
        CONFIG_SERVER = StubConfigServer.start(properties);
        // Read by application.yaml: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
        System.setProperty("CONFIG_SERVER_URL", CONFIG_SERVER.url());
    }

    @LocalServerPort
    private int port;

    private WebTestClient gateway;

    @BeforeEach
    void startClean() {
        SERVICES.forEach(StubService::reset);
        gateway = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void loadsItsRoutesFromTheConfigServer() {
        assertThat(CONFIG_SERVER.requestedPaths()).contains("/api-gateway/default");
    }

    /** The service receives the same method, path and query the client sent to the gateway. */
    @ParameterizedTest
    @CsvSource({
            "GET,  /customers,                                                  customer-service",
            "POST, /customers,                                                  customer-service",
            "GET,  /customers/42,                                               customer-service",
            "GET,  /accounts/7/movements?size=10,                               account-service",
            "POST, /accounts/7/transfers,                                       account-service",
            "GET,  /accounts/reports/products?from=2026-01-01&to=2026-01-31,    account-service",
            "GET,  /credits?customerId=42&type=CREDIT_CARD&status=ACTIVE&size=1, credit-service",
            "POST, /credits/9/payments,                                         credit-service"
    })
    void routesEachPathToItsService(String method, String uri, String service) {
        gateway.method(HttpMethod.valueOf(method)).uri(uri)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo(service)
                .jsonPath("$.method").isEqualTo(method)
                .jsonPath("$.uri").isEqualTo(uri);

        SERVICES.stream()
                .filter(stub -> !stub.name().equals(service))
                .forEach(stub -> assertThat(stub.requests()).as(stub.name()).isEmpty());
    }

    /**
     * Optimistic locking of customer-service travels through the gateway: the If-Match header
     * and the body go to the service, its status, ETag and error body come back unchanged.
     */
    @Test
    void forwardsHeadersBodyAndStatusBothWays() {
        CUSTOMERS.respondWith(412, Map.of("ETag", "\"v2\""), "{\"code\":\"CUSTOMER_VERSION_CONFLICT\"}");

        gateway.put().uri("/customers/42")
                .header("If-Match", "\"v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Ana Perez\"}")
                .exchange()
                .expectStatus().isEqualTo(412)
                .expectHeader().valueEquals("ETag", "\"v2\"")
                .expectBody().jsonPath("$.code").isEqualTo("CUSTOMER_VERSION_CONFLICT");

        StubService.ReceivedRequest received = CUSTOMERS.requests().get(0);
        assertThat(received.headers().getFirst("If-Match")).isEqualTo("\"v1\"");
        assertThat(received.body()).isEqualTo("{\"name\":\"Ana Perez\"}");
    }

    /** Only the three APIs are published; a path that merely starts like one is not. */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/loans", "/customersX", "/actuator/env", "/v3/api-docs"})
    void answersNotFoundForAnyOtherPath(String uri) {
        gateway.get().uri(uri).exchange().expectStatus().isNotFound().expectBody().consumeWith(body -> { });

        SERVICES.forEach(stub -> assertThat(stub.requests()).as(stub.name()).isEmpty());
    }

    private static Map<String, String> yaml(String resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        Map<String, String> values = new HashMap<>();
        factory.getObject().forEach((key, value) -> values.put(key.toString(), value.toString()));
        return values;
    }
}
