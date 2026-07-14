package com.salamu.menu.tenancy.api;

/** Outcome of reconciling one immutable object with its tenant-scoped registry record. */
public enum TenantAssetProvisioningDisposition {
    CREATED,
    /** Same-run immutable object reconciled after a retry or an uncertain previous PUT. */
    RECOVERED
}
