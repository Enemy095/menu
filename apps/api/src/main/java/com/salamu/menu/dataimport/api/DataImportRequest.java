package com.salamu.menu.dataimport.api;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public record DataImportRequest(
        Path bundlePath,
        Path assetRoot,
        DataImportTarget target,
        String timezoneOverride,
        URI canonicalUrlOverride,
        String approvalArtifact,
        boolean publicContactApproved,
        boolean scheduleApproved,
        boolean assetRightsApproved,
        boolean workerDisabledAcknowledged) {

    public DataImportRequest {
        Objects.requireNonNull(bundlePath, "bundlePath");
        Objects.requireNonNull(assetRoot, "assetRoot");
        Objects.requireNonNull(target, "target");
        bundlePath = bundlePath.toAbsolutePath().normalize();
        assetRoot = assetRoot.toAbsolutePath().normalize();
    }

    public static DataImportRequest localTest(Path bundlePath, Path assetRoot) {
        return new DataImportRequest(
                bundlePath,
                assetRoot,
                DataImportTarget.LOCAL_TEST,
                null,
                null,
                null,
                false,
                false,
                false,
                false);
    }
}
