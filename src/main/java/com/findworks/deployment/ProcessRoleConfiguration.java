package com.findworks.deployment;

import com.findworks.runtime.RuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProcessRoleConfiguration {

    @Bean
    ApplicationRunner deploymentGuard(DeploymentProperties deployment, RuntimeProperties runtime,
            @Value("${findworks.process-role:local}") String role,
            @Value("${spring.datasource.url}") String databaseUrl,
            @Value("${findworks.invitation.public-origin:}") String publicOrigin,
            @Value("${spring.flyway.enabled:true}") boolean flywayEnabled) {
        return ignored -> deployment.validate(role, databaseUrl, publicOrigin,
                flywayEnabled, runtime.enabled(), runtime.image());
    }

    @Bean
    @ConditionalOnProperty(name = "findworks.process-role", havingValue = "migrate")
    ApplicationRunner exitAfterMigration(ConfigurableApplicationContext application) {
        return ignored -> application.close();
    }
}
