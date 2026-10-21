package com.aryanbhujbal.clearbook.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.aryanbhujbal.clearbook.domain.Money;
import com.aryanbhujbal.clearbook.support.LedgerFixture;
import com.aryanbhujbal.clearbook.support.PostgresIT;

/**
 * The HTTP surface, against a real database.
 *
 * <p>MockMvc rather than a full server because the thing under test is the web
 * layer's own behaviour — binding, validation, status codes, the shape of an
 * error — and a real socket adds time without adding coverage of any of it.
 */
@AutoConfigureMockMvc
class TradeApiIT extends PostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private LedgerFixture fixture;

    private String client;

    @BeforeEach
    void openAccounts() {
        client = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        fixture.openAndFund(client, Money.ofRupees(500_000), DAY);
        fixture.mark("TCS", DAY, Money.ofRupees("3050.00"));
    }

    @Test
    @DisplayName("a valid trade returns 201 with its itemised charges")
    void submitTrade() throws Exception {
        mvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJson("T-" + client + "-api", "BUY", 40, "3000.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryId", notNullValue()))
                .andExpect(jsonPath("$.alreadyPosted", is(false)))
                .andExpect(jsonPath("$.totalCharges", is("165.94")))
                .andExpect(jsonPath("$.charges.brokerage", is("20.00")))
                .andExpect(jsonPath("$.charges.GST", is("4.26")))
                .andExpect(jsonPath("$.margin.solvent", is(true)));
    }

    @Test
    @DisplayName("resubmitting the same trade returns 200, not an error")
    void resubmitIsNotAnError() throws Exception {
        String body = tradeJson("T-" + client + "-again", "BUY", 10, "3000.00");

        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // A client retrying after a timeout asked for the same thing twice and
        // should get the same answer, not a 409 it has to special-case.
        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyPosted", is(true)));
    }

    @Test
    @DisplayName("a malformed request is a 400 naming every field that was wrong")
    void validationFailuresAreItemised() throws Exception {
        String body = """
                {
                  "tradeId": "",
                  "clientId": "not a client id",
                  "symbol": "tcs",
                  "side": "BUY",
                  "quantity": -5,
                  "price": "3000.001",
                  "businessDate": "2026-03-02"
                }
                """;

        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors.tradeId", containsString("idempotency key")))
                .andExpect(jsonPath("$.fieldErrors.clientId", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.symbol", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.quantity", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.price", notNullValue()));
    }

    @Test
    @DisplayName("a trade that would breach margin is a 409 that says by how much")
    void marginBreachIsAConflict() throws Exception {
        String thin = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        fixture.openAndFund(thin, Money.ofRupees(1_000), DAY);

        mvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tradeId": "T-%s-big",
                                  "clientId": "%s",
                                  "symbol": "TCS",
                                  "side": "BUY",
                                  "quantity": 1000,
                                  "price": "3000.00",
                                  "businessDate": "2026-03-02"
                                }
                                """.formatted(thin, thin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("MARGIN_BREACH")))
                .andExpect(jsonPath("$.details", notNullValue()));
    }

    @Test
    @DisplayName("trading for an account that does not exist is a 422")
    void unknownAccountIsUnprocessable() throws Exception {
        mvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJsonFor("NOBODY", "T-nobody-1", "BUY", 1, "3000.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("UNKNOWN_ACCOUNT")));
    }

    @Test
    @DisplayName("a statement lists the movements behind the balance")
    void statementShowsItsWorking() throws Exception {
        mvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJson("T-" + client + "-st", "BUY", 40, "3000.00")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/accounts/{client}/statement", client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId", is(client)))
                // 50,000,000 paise funded, less the 12,016,594 the purchase cost.
                .andExpect(jsonPath("$.cash", is("379834.06")))
                .andExpect(jsonPath("$.positions['TCS']", is(40)))
                .andExpect(jsonPath("$.lines[0].tradeId", notNullValue()));
    }

    @Test
    void marginSnapshotIsAvailable() throws Exception {
        mvc.perform(get("/api/v1/accounts/{client}/margin", client)
                        .param("asOf", "2026-03-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solvent", is(true)))
                .andExpect(jsonPath("$.equity", notNullValue()));
    }

    private String tradeJson(String tradeId, String side, long quantity, String price) {
        return tradeJsonFor(client, tradeId, side, quantity, price);
    }

    private String tradeJsonFor(String clientId, String tradeId, String side,
                                long quantity, String price) {
        return """
                {
                  "tradeId": "%s",
                  "clientId": "%s",
                  "symbol": "TCS",
                  "side": "%s",
                  "quantity": %d,
                  "price": "%s",
                  "businessDate": "2026-03-02"
                }
                """.formatted(tradeId, clientId, side, quantity, price);
    }
}
