package com.aryanbhujbal.clearbook.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aryanbhujbal.clearbook.api.dto.SubmitTradeRequest;
import com.aryanbhujbal.clearbook.api.dto.TradeAcceptedResponse;
import com.aryanbhujbal.clearbook.trade.TradeService;
import com.aryanbhujbal.clearbook.trade.TradeService.SettlementOutcome;

/**
 * Trade submission over HTTP.
 *
 * <p>Thin by design: validate, convert, delegate, convert back. Anything that
 * looks like a decision belongs a layer down, where it can be tested without
 * a web server.
 */
@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {

    private final TradeService trades;

    public TradeController(TradeService trades) {
        this.trades = trades;
    }

    /**
     * Submits a fill for settlement.
     *
     * <p>Returns 201 when the trade posted and <b>200</b> when it had already
     * posted under the same trade id. Not 409: a client retrying after a
     * timeout is asking for the same thing twice and should get the same
     * answer, not an error it has to special-case. The distinction is carried
     * in the body for callers that care.
     */
    @PostMapping
    public ResponseEntity<TradeAcceptedResponse> submit(
            @Valid @RequestBody SubmitTradeRequest request) {

        SettlementOutcome outcome = trades.settle(request.toTrade());
        TradeAcceptedResponse body = TradeAcceptedResponse.from(outcome);

        HttpStatus status = outcome.result().alreadyPosted()
                ? HttpStatus.OK
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }
}
