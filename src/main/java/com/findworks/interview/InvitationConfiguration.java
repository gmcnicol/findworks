package com.findworks.interview;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InvitationConfiguration {

    @Bean
    Clock invitationClock() {
        return Clock.systemUTC();
    }
}
