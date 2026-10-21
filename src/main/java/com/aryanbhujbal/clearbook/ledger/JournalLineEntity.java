package com.aryanbhujbal.clearbook.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * One leg of an entry: an account, the asset it is denominated in, and a
 * signed amount.
 *
 * <p>{@code amount} is a {@code long} of the asset's smallest unit — paise for
 * {@code INR}, whole shares for {@code EQ:*}. The sign carries direction, so
 * there is no separate debit/credit column: a debit is negative, a credit
 * positive, and "the entry balances" is simply "the amounts sum to zero",
 * per asset. That makes the invariant something the database can check, which
 * it does.
 */
@Entity
@Table(name = "journal_line")
public class JournalLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    private JournalEntryEntity entry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private AccountEntity account;

    @Column(name = "asset", nullable = false, updatable = false)
    private String asset;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    protected JournalLineEntity() {
        // for JPA
    }

    public JournalLineEntity(AccountEntity account, String asset, long amount) {
        this.account = account;
        this.asset = asset;
        this.amount = amount;
    }

    void setEntry(JournalEntryEntity entry) {
        this.entry = entry;
    }

    public Long getId() {
        return id;
    }

    public JournalEntryEntity getEntry() {
        return entry;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public String getAsset() {
        return asset;
    }

    public long getAmount() {
        return amount;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JournalLineEntity line)) {
            return false;
        }
        return id != null && id.equals(line.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
