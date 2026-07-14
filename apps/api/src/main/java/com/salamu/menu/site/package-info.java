@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"asset::api", "restaurant::api"},
        displayName = "Site",
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package com.salamu.menu.site;
