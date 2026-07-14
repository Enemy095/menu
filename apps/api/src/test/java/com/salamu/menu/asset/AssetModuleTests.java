package com.salamu.menu.asset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.NamedInterface;
import org.springframework.modulith.core.ApplicationModules;

import com.salamu.menu.MenuPlatformApplication;
import com.salamu.menu.asset.api.AssetCatalog;

class AssetModuleTests {

    @Test
    void isAClosedIndependentModuleWithOneNamedPublicInterface() throws ClassNotFoundException {
        ApplicationModules modules = ApplicationModules.of(MenuPlatformApplication.class);

        modules.verify();
        assertThat(modules.getModuleByName("asset")).isPresent();

        ApplicationModule annotation = Class.forName("com.salamu.menu.asset.package-info")
                .getPackage()
                .getAnnotation(ApplicationModule.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.type()).isEqualTo(ApplicationModule.Type.CLOSED);
        assertThat(annotation.allowedDependencies()).isEmpty();
        assertThat(AssetCatalog.class.getPackage().getAnnotation(NamedInterface.class)).isNotNull();
    }
}
