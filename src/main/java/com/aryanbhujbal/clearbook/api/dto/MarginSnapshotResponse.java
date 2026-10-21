package com.aryanbhujbal.clearbook.api.dto;

import com.aryanbhujbal.clearbook.domain.MarginPolicy.MarginSnapshot;

/** An account's margin position, with every amount as a decimal string. */
public record MarginSnapshotResponse(
        String cash,
        String marketValue,
        String equity,
        String requirement,
        String headroom,
        boolean solvent) {

    public static MarginSnapshotResponse from(MarginSnapshot snapshot) {
        return new MarginSnapshotResponse(
                snapshot.cash().toPlainString(),
                snapshot.marketValue().toPlainString(),
                snapshot.equity().toPlainString(),
                snapshot.requirement().toPlainString(),
                snapshot.headroom().toPlainString(),
                snapshot.isSolvent());
    }
}
