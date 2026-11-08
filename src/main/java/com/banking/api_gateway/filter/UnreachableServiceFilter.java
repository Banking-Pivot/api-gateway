package com.banking.api_gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;

/**
 * Answers 503 Service Unavailable when the instance chosen for a route does not accept the
 * connection, instead of the 500 Internal Server Error the gateway gives by default.
 *
 * <p>It happens between the moment a service stops and the moment Eureka drops it from the
 * registry: the gateway still sends traffic to the old address. The caller sees the same
 * status as when the service has no instance at all, and the log gets one warning instead
 * of a stack trace per request.
 */
@Component
public class UnreachableServiceFilter implements GlobalFilter, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(UnreachableServiceFilter.class);

    /**
     * Lets the request go through the rest of the chain and turns a refused or timed-out
     * connection into a 503.
     *
     * @param exchange the current request and response
     * @param chain    the remaining filters, routing included
     * @return completion of the request, or the 503 error
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorMap(ConnectException.class, error -> {
            URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            LOG.warn("{} {} -> {} is unreachable: {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), target, error.getMessage());
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service unreachable", error);
        });
    }

    /** First of all filters, so it sees the errors of every other one. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
