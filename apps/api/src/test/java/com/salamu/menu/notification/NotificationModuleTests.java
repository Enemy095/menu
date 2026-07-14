package com.salamu.menu.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.NamedInterface;

import com.salamu.menu.notification.api.NotificationDestinationQuery;

class NotificationModuleTests {

    @Test
    void isClosedDependsOnlyOnOrderingApiAndExportsOnlyTheSanitizedApi() throws ClassNotFoundException {
        ApplicationModule module = Class.forName("com.salamu.menu.notification.package-info")
                .getPackage()
                .getAnnotation(ApplicationModule.class);

        assertThat(module).isNotNull();
        assertThat(module.type()).isEqualTo(ApplicationModule.Type.CLOSED);
        assertThat(module.allowedDependencies()).containsExactly("ordering::api");
        assertThat(NotificationDestinationQuery.class.getPackage().getAnnotation(NamedInterface.class).value())
                .containsExactly("api");
    }
}
