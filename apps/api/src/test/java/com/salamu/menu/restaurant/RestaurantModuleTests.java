package com.salamu.menu.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.NamedInterface;

import com.salamu.menu.restaurant.api.DefaultRestaurantQuery;

class RestaurantModuleTests {

    @Test
    void isClosedIndependentAndExportsOnlyTheApiPackage() throws ClassNotFoundException {
        ApplicationModule module = Class.forName("com.salamu.menu.restaurant.package-info")
                .getPackage()
                .getAnnotation(ApplicationModule.class);

        assertThat(module).isNotNull();
        assertThat(module.type()).isEqualTo(ApplicationModule.Type.CLOSED);
        assertThat(module.allowedDependencies()).isEmpty();
        assertThat(DefaultRestaurantQuery.class.getPackage().getAnnotation(NamedInterface.class).value())
                .containsExactly("api");
    }
}
