package com.banking.api_gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class UnreachableServiceFilterTest {

    private final UnreachableServiceFilter filter = new UnreachableServiceFilter();
    private final MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/customers"));

    @Test
    void refusedConnectionBecomesServiceUnavailable() {
        ConnectException refused = new ConnectException("Connection refused");

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(refused)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) error).getStatusCode())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCause()).isSameAs(refused);
                })
                .verify();
    }

    @Test
    void otherErrorsPassThroughUnchanged() {
        IOException reset = new IOException("Connection reset by peer");

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(reset)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(reset))
                .verify();
    }

    @Test
    void successfulRequestsAreUntouched() {
        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty())).verifyComplete();
    }

    @Test
    void runsBeforeEveryOtherFilter() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }
}
