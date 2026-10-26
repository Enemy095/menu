@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"menu::api", "site::api", "restaurant::api"},
        displayName = "Ordering",
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package com.salamu.menu.ordering;
