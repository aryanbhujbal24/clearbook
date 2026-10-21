package com.aryanbhujbal.clearbook.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aryanbhujbal.clearbook.domain.FeeSchedule;
import com.aryanbhujbal.clearbook.domain.MarginPolicy;
import com.aryanbhujbal.clearbook.domain.Money;

/**
 * Builds the domain's value objects from configuration.
 *
 * <p>{@link FeeSchedule} and {@link MarginPolicy} are plain records with no
 * Spring annotations on them, and that is deliberate: the arithmetic they
 * carry is the part most worth testing, and it tests far better as a value
 * object that can be constructed in one line than as a bean that needs a
 * context to exist. This class is the only place the two worlds meet.
 */
@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfiguration {

    @Bean
    public FeeSchedule feeSchedule(LedgerProperties properties) {
        LedgerProperties.Fees fees = properties.getFees();
        return new FeeSchedule(
                fees.getBrokerageRate(),
                Money.ofPaise(fees.getBrokerageCapPaise()),
                fees.getSecuritiesTransactionTaxRate(),
                fees.getExchangeTransactionRate(),
                fees.getRegulatorTurnoverRate(),
                fees.getStampDutyRate(),
                fees.getGoodsAndServicesTaxRate());
    }

    @Bean
    public MarginPolicy marginPolicy(LedgerProperties properties) {
        return new MarginPolicy(properties.getMargin().getMaintenanceRate());
    }
}
