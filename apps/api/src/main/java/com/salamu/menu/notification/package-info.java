@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"ordering::api", "restaurant::api", "site::api"},
        displayName = "Notification",
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package com.salamu.menu.notification;
