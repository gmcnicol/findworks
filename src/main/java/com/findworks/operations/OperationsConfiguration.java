package com.findworks.operations;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OperationsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OperationalSignalSource operationalSignalSource() {
        return List::of;
    }

    @Bean
    @ConditionalOnMissingBean
    AlertReceiver alertReceiver() {
        return ignored -> { };
    }

    @Bean
    @ConditionalOnMissingBean
    BackupVerifier backupVerifier() {
        return ignored -> new BackupVerifier.Result("unavailable");
    }
}
