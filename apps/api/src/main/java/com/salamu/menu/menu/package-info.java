@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"asset::api", "restaurant::api"},
        displayName = "Menu",
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package com.salamu.menu.menu;
