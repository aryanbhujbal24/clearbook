package com.aryanbhujbal.clearbook.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * A memo of what an account's balance was as at a given line, so a balance
 * need not be folded from the beginning of history on every margin check.
 *
 * <p>This is explicitly <em>not</em> a second source of truth. The journal
 * remains the only one; a checkpoint is a cached prefix of the same fold, and
 * {@code balance = checkpoint.amount + Σ(lines after it)}. Deleting every row
 * in this table changes no answer the system gives, only how long it takes —
 * a property the test suite asserts directly, because the moment it stops
 * holding, "balances are derived" has quietly become false.
 */
@Entity
@Table(name = "balance_checkpoint")
public class BalanceCheckpointEntity {

    @EmbeddedId
    private Key key;

    /** Covers every line with an id at or below this value. */
    @Column(name = "through_line_id", nullable = false)
    private long throughLineId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected BalanceCheckpointEntity() {
        // for JPA
    }

    public BalanceCheckpointEntity(Long accountId, String asset, long throughLineId, long amount) {
        this.key = new Key(accountId, asset);
        this.throughLineId = throughLineId;
        this.amount = amount;
    }

    public Key getKey() {
        return key;
    }

    public long getThroughLineId() {
        return throughLineId;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void advanceTo(long newThroughLineId, long newAmount) {
        this.throughLineId = newThroughLineId;
        this.amount = newAmount;
    }

    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "account_id", nullable = false)
        private Long accountId;

        @Column(name = "asset", nullable = false)
        private String asset;

        protected Key() {
            // for JPA
        }

        public Key(Long accountId, String asset) {
            this.accountId = accountId;
            this.asset = asset;
        }

        public Long getAccountId() {
            return accountId;
        }

        public String getAsset() {
            return asset;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(accountId, key.accountId) && Objects.equals(asset, key.asset);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, asset);
        }
    }
}
