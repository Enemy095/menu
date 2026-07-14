package com.salamu.menu.ordering.internal.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
class OrderingConfiguration {

    @Bean("orderClock")
    @Primary
    Clock orderClock() {
        return Clock.systemUTC();
    }
}
