package com.aryanbhujbal.clearbook.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Browsable API documentation, served at {@code /swagger-ui.html}.
 *
 * <p>Worth the one dependency for a service like this, because the thing a
 * reader most wants to see — a trade going in and an itemised set of charges
 * coming back — is otherwise only visible by writing a curl command.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI clearbookOpenApi() {
        return new OpenAPI().info(new Info()
                .title("clearbook")
                .version("0.1.0")
                .description("""
                        A double-entry trade settlement and margin ledger.

                        Fills post as balanced journal entries; every balance is \
                        derived from that journal rather than stored beside it. \
                        Submitting the same tradeId twice returns 200 with \
                        alreadyPosted=true rather than an error — it is the \
                        idempotency key, and a retry is a normal outcome.

                        All amounts are decimal strings, never JSON numbers: a \
                        consumer parsing 1234.56 into a binary float has \
                        reintroduced the error this ledger exists to prevent.""")
                .license(new License().name("MIT")));
    }
}
