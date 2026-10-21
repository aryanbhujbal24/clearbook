package com.aryanbhujbal.clearbook.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aryanbhujbal.clearbook.api.dto.MarginSnapshotResponse;
import com.aryanbhujbal.clearbook.api.dto.StatementResponse;
import com.aryanbhujbal.clearbook.ledger.StatementService;

/** Account statements and margin snapshots. */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final StatementService statements;

    public AccountController(StatementService statements) {
        this.statements = statements;
    }

    /** Closing cash, open positions, and every movement that produced them. */
    @GetMapping("/{clientId}/statement")
    public ResponseEntity<StatementResponse> statement(@PathVariable String clientId) {
        return ResponseEntity.ok(StatementResponse.from(statements.statementFor(clientId)));
    }

    /**
     * Where the account stands against its maintenance requirement.
     *
     * <p>{@code asOf} selects which marks to value positions at, defaulting to
     * today. Positions are valued at the most recent mark at or before that
     * date, so a holiday carries the last price forward rather than leaving
     * the account unvaluable.
     */
    @GetMapping("/{clientId}/margin")
    public ResponseEntity<MarginSnapshotResponse> margin(
            @PathVariable String clientId,
            @RequestParam(value = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        LocalDate effective = asOf == null ? LocalDate.now() : asOf;
        return ResponseEntity.ok(
                MarginSnapshotResponse.from(statements.marginFor(clientId, effective)));
    }
}
