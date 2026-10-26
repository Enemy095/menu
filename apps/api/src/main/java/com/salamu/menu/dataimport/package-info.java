@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"asset::api", "menu::api", "site::api"},
        displayName = "Approved dataset import",
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package com.salamu.menu.dataimport;
