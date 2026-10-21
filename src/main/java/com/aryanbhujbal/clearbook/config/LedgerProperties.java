package com.aryanbhujbal.clearbook.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Charges and margin, as configuration rather than constants.
 *
 * <p>Every rate here is set by regulation or by commercial agreement and all
 * of them change. Burying them in a calculation means a rate change becomes a
 * code change, a release and a chance to get one of them wrong; keeping them
 * here means it is a configuration change with the old values still in version
 * control.
 *
 * <p>The defaults are illustrative, in the shape of Indian equity delivery
 * charges. They are not a statement of current statutory rates and should be
 * checked against the current circulars before anyone relies on them.
 */
@ConfigurationProperties(prefix = "clearbook")
public class LedgerProperties {

    private Fees fees = new Fees();
    private Margin margin = new Margin();

    public Fees getFees() {
        return fees;
    }

    public void setFees(Fees fees) {
        this.fees = fees;
    }

    public Margin getMargin() {
        return margin;
    }

    public void setMargin(Margin margin) {
        this.margin = margin;
    }

    public static class Fees {

        /** Brokerage as a fraction of turnover. */
        private BigDecimal brokerageRate = new BigDecimal("0.0003");

        /** Brokerage ceiling per order, in paise. */
        private long brokerageCapPaise = 2_000;

        /** Securities transaction tax, both sides on delivery. */
        private BigDecimal securitiesTransactionTaxRate = new BigDecimal("0.001");

        /** Exchange transaction charge. */
        private BigDecimal exchangeTransactionRate = new BigDecimal("0.0000297");

        /** Regulator turnover fee. */
        private BigDecimal regulatorTurnoverRate = new BigDecimal("0.000001");

        /** Stamp duty, charged on purchases only. */
        private BigDecimal stampDutyRate = new BigDecimal("0.00015");

        /** GST, levied on brokerage plus exchange and regulator charges. */
        private BigDecimal goodsAndServicesTaxRate = new BigDecimal("0.18");

        public BigDecimal getBrokerageRate() {
            return brokerageRate;
        }

        public void setBrokerageRate(BigDecimal brokerageRate) {
            this.brokerageRate = brokerageRate;
        }

        public long getBrokerageCapPaise() {
            return brokerageCapPaise;
        }

        public void setBrokerageCapPaise(long brokerageCapPaise) {
            this.brokerageCapPaise = brokerageCapPaise;
        }

        public BigDecimal getSecuritiesTransactionTaxRate() {
            return securitiesTransactionTaxRate;
        }

        public void setSecuritiesTransactionTaxRate(BigDecimal rate) {
            this.securitiesTransactionTaxRate = rate;
        }

        public BigDecimal getExchangeTransactionRate() {
            return exchangeTransactionRate;
        }

        public void setExchangeTransactionRate(BigDecimal rate) {
            this.exchangeTransactionRate = rate;
        }

        public BigDecimal getRegulatorTurnoverRate() {
            return regulatorTurnoverRate;
        }

        public void setRegulatorTurnoverRate(BigDecimal rate) {
            this.regulatorTurnoverRate = rate;
        }

        public BigDecimal getStampDutyRate() {
            return stampDutyRate;
        }

        public void setStampDutyRate(BigDecimal rate) {
            this.stampDutyRate = rate;
        }

        public BigDecimal getGoodsAndServicesTaxRate() {
            return goodsAndServicesTaxRate;
        }

        public void setGoodsAndServicesTaxRate(BigDecimal rate) {
            this.goodsAndServicesTaxRate = rate;
        }
    }

    public static class Margin {

        /** Maintenance requirement as a fraction of marked position value. */
        private BigDecimal maintenanceRate = new BigDecimal("0.20");

        public BigDecimal getMaintenanceRate() {
            return maintenanceRate;
        }

        public void setMaintenanceRate(BigDecimal maintenanceRate) {
            this.maintenanceRate = maintenanceRate;
        }
    }
}
