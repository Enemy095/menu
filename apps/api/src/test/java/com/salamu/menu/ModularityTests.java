package com.salamu.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifiesModuleBoundaries() {
        var modules = ApplicationModules.of(MenuPlatformApplication.class);

        modules.verify();
        assertThat(modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet()))
                .isEqualTo(Set.of(
                        "asset",
                        "restaurant",
                        "site",
                        "menu",
                        "ordering",
                        "notification",
                        "identity"));
    }
}
