package com.salamu.menu.acceptance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Verifies that no local-only acceptance class is packaged in the production executable. */
public final class AcceptanceArtifactVerifier {

    private static final String PROVIDER_ENTRY =
            "BOOT-INF/classes/com/salamu/menu/notification/internal/acceptance/"
                    + "AcceptanceNotificationProvider.class";

    private AcceptanceArtifactVerifier() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected production and acceptance jar paths");
        }
        boolean productionContainsProvider = contains(Path.of(arguments[0]), PROVIDER_ENTRY);
        boolean acceptanceContainsProvider = contains(Path.of(arguments[1]), PROVIDER_ENTRY);
        if (productionContainsProvider) {
            throw new IllegalStateException(
                    "Acceptance notification provider leaked into the production bootJar");
        }
        if (!acceptanceContainsProvider) {
            throw new IllegalStateException(
                    "Acceptance bootJar does not contain its deterministic notification provider");
        }
        System.out.println("Acceptance provider isolation verified: production=absent, acceptance=present.");
    }

    private static boolean contains(Path archive, String entry) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getEntry(entry) != null;
        }
    }
}
