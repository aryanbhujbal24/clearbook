package com.aryanbhujbal.clearbook.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** The price a position is marked at for margin, per symbol per business date. */
@Entity
@Table(name = "mark_price")
public class MarkPriceEntity {

    @EmbeddedId
    private Key key;

    /** Paise per share. */
    @Column(name = "price", nullable = false)
    private long price;

    protected MarkPriceEntity() {
        // for JPA
    }

    public MarkPriceEntity(String symbol, LocalDate businessDate, long price) {
        this.key = new Key(symbol, businessDate);
        this.price = price;
    }

    public Key getKey() {
        return key;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "symbol", nullable = false)
        private String symbol;

        @Column(name = "business_date", nullable = false)
        private LocalDate businessDate;

        protected Key() {
            // for JPA
        }

        public Key(String symbol, LocalDate businessDate) {
            this.symbol = symbol;
            this.businessDate = businessDate;
        }

        public String getSymbol() {
            return symbol;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(symbol, key.symbol)
                    && Objects.equals(businessDate, key.businessDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, businessDate);
        }
    }
}
