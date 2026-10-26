package com.salamu.menu.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;

class OrderingModuleTests {

    @Test
    void isClosedAndDependsOnlyOnPublishedSiteAndMenuContracts() throws ClassNotFoundException {
        ApplicationModule module = Class.forName("com.salamu.menu.ordering.package-info")
                .getPackage()
                .getAnnotation(ApplicationModule.class);

        assertThat(module).isNotNull();
        assertThat(module.type()).isEqualTo(ApplicationModule.Type.CLOSED);
        assertThat(module.allowedDependencies())
                .containsExactly("menu::api", "site::api", "restaurant::api");
    }
}
