package com.salamu.menu.ordering.internal.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrderingConfiguration {

    @Bean
    Clock orderClock() {
        return Clock.systemUTC();
    }
}
