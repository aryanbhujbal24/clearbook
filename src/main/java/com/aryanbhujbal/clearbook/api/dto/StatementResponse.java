package com.aryanbhujbal.clearbook.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.aryanbhujbal.clearbook.domain.Asset;
import com.aryanbhujbal.clearbook.ledger.StatementService.Statement;
import com.aryanbhujbal.clearbook.ledger.StatementService.StatementLine;

/**
 * An account statement: the closing position, and every movement behind it.
 *
 * <p>The lines are not decoration. Because every balance here is folded from
 * exactly these entries rather than read from a stored figure, a client can
 * add the movements up and arrive at the closing balance — and if they ever
 * could not, the ledger would be wrong in a way worth finding out about.
 */
public record StatementResponse(
        String clientId,
        String cash,
        Map<String, Long> positions,
        List<Line> lines) {

    public static StatementResponse from(Statement statement) {
        return new StatementResponse(
                statement.clientId(),
                statement.cash().toPlainString(),
                statement.positions(),
                statement.lines().stream().map(Line::from).toList());
    }

    public record Line(
            Long entryId,
            LocalDate businessDate,
            String kind,
            String tradeId,
            String description,
            String account,
            String asset,
            String amount) {

        static Line from(StatementLine line) {
            // Cash is a decimal amount; a share count is a whole number and
            // formatting it with two decimal places would only invite someone
            // to wonder what half a share means.
            String amount = Asset.isCash(line.asset())
                    ? com.aryanbhujbal.clearbook.domain.Money.ofPaise(line.amount()).toPlainString()
                    : Long.toString(line.amount());

            return new Line(
                    line.entryId(),
                    line.businessDate(),
                    line.kind(),
                    line.tradeId(),
                    line.description(),
                    line.accountCode(),
                    line.asset(),
                    amount);
        }
    }
}
