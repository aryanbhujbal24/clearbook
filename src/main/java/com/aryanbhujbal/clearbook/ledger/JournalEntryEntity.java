package com.aryanbhujbal.clearbook.ledger;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One journal entry: a set of lines that balance.
 *
 * <p>{@code tradeId} is the idempotency key, and it is backed by a UNIQUE
 * constraint rather than an application-level "have I seen this?" lookup. The
 * lookup is still worth doing — it turns the common case into a cheap read
 * instead of a failed insert — but it cannot be the guarantee, because
 * check-then-insert has a window and redelivery is precisely what lands two
 * callers in it at once. The constraint closes the window; the lookup just
 * makes it rare.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "trade_id", unique = true, updatable = false)
    private String tradeId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, fetch = FetchType.LAZY,
            orphanRemoval = true)
    private List<JournalLineEntity> lines = new ArrayList<>();

    protected JournalEntryEntity() {
        // for JPA
    }

    public JournalEntryEntity(String kind, String tradeId, LocalDate businessDate,
                              String description) {
        this.kind = kind;
        this.tradeId = tradeId;
        this.businessDate = businessDate;
        this.description = description;
    }

    /** Adds a line and keeps both sides of the association consistent. */
    public void addLine(JournalLineEntity line) {
        lines.add(line);
        line.setEntry(this);
    }

    public Long getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getTradeId() {
        return tradeId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<JournalLineEntity> getLines() {
        return Collections.unmodifiableList(lines);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JournalEntryEntity entry)) {
            return false;
        }
        return id != null && id.equals(entry.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
