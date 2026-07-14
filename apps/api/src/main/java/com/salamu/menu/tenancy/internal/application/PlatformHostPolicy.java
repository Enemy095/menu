package com.salamu.menu.tenancy.internal.application;

public interface PlatformHostPolicy {

    HostKind classify(String normalizedHost);

    enum HostKind {
        TENANT_DOMAIN,
        PLATFORM,
        LOCAL
    }
}
