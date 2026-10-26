package com.salamu.menu.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.salamu.menu.menu.api.PublishedMenuQuery;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.NamedInterface;

class MenuModuleTests {

    @Test
    void isClosedAndExportsOnlyTheApiPackage() throws ClassNotFoundException {
        ApplicationModule module = Class.forName("com.salamu.menu.menu.package-info")
                .getPackage()
                .getAnnotation(ApplicationModule.class);

        assertThat(module).isNotNull();
        assertThat(module.type()).isEqualTo(ApplicationModule.Type.CLOSED);
        assertThat(module.allowedDependencies()).containsExactly("asset::api", "restaurant::api");
        assertThat(PublishedMenuQuery.class.getPackage().getAnnotation(NamedInterface.class).value())
                .containsExactly("api");
    }
}
