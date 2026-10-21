package com.aryanbhujbal.clearbook.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * A ledger account.
 *
 * <p>Carries no balance field, deliberately. The balance is a fold over the
 * journal (see {@link BalanceService}); storing it here would create a second
 * source of truth that can drift from the entries that produced it, and
 * reconciling the two is the problem this design exists to avoid.
 *
 * <p>What the row does carry is {@link #version}, and that is load-bearing.
 * Every posting takes an optimistic-lock force-increment on the accounts it
 * touches, so two transactions posting to the same account cannot commit
 * concurrently — one fails the version check and retries against a balance it
 * has re-read. Two things depend on that:
 *
 * <ul>
 *   <li><b>Margin cannot be breached by a race.</b> Without it, two trades
 *   each read the same pre-trade balance, each pass their own margin check,
 *   and together breach it. The ledger stays perfectly balanced and the
 *   account is quietly undermargined — a write skew, and invisible.</li>
 *   <li><b>Balance checkpoints stay correct.</b> Identity values are assigned
 *   before commit, so concurrent writers can commit out of order; a
 *   checkpoint taken "through line 101" would permanently lose a line 100
 *   that commits afterwards. Serialising postings per account makes line ids
 *   gap-free in commit order within the scope a checkpoint covers.</li>
 * </ul>
 */
@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "owner_id", updatable = false)
    private String ownerId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected AccountEntity() {
        // for JPA
    }

    public AccountEntity(String code, String kind, String ownerId) {
        this.code = code;
        this.kind = kind;
        this.ownerId = ownerId;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getKind() {
        return kind;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountEntity account)) {
            return false;
        }
        return id != null && id.equals(account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Account[" + code + "]";
    }
}
