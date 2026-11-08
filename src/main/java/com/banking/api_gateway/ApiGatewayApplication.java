package com.banking.api_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single entry point of the banking APIs, built on Spring Cloud Gateway.
 *
 * <p>The routes are configuration, not code: they come from the Config Server
 * (banking-config/api-gateway.yml) and send each path to a service registered in Eureka
 * through {@code lb://<service>}, so the gateway follows the instances that are up.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    /**
     * Starts the gateway.
     *
     * @param args command line arguments, passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
