package com.salamu.menu.site;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.NamedInterface;

import com.salamu.menu.site.api.SiteConfigurationQuery;

class SiteModuleTests {

    @Test
    void isClosedAndExportsOnlyItsApiWithTheTwoApprovedDependencies() throws ClassNotFoundException {
        ApplicationModule module = Class.forName("com.salamu.menu.site.package-info")
                .getPackage()
                .getAnnotation(ApplicationModule.class);

        assertThat(module).isNotNull();
        assertThat(module.type()).isEqualTo(ApplicationModule.Type.CLOSED);
        assertThat(module.allowedDependencies()).containsExactlyInAnyOrder("asset::api", "restaurant::api");
        assertThat(SiteConfigurationQuery.class.getPackage().getAnnotation(NamedInterface.class).value())
                .containsExactly("api");
    }
}
