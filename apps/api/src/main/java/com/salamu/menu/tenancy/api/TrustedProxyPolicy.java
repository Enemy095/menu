package com.salamu.menu.tenancy.api;

import java.net.InetAddress;

/** Shared trust decision for any forwarded request metadata. */
public interface TrustedProxyPolicy {

    boolean isTrusted(InetAddress remoteAddress);
}
