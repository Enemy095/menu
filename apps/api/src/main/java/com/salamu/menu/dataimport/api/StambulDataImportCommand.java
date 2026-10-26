package com.salamu.menu.dataimport.api;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Explicit non-HTTP entry point for the owner-approved Stambul bundle. */
public final class StambulDataImportCommand {

    private static final Set<String> VALUE_OPTIONS = Set.of(
            "bundle", "asset-root", "mode", "target", "timezone", "canonical-url",
            "approval-artifact");
    private static final Set<String> FLAG_OPTIONS = Set.of(
            "approve-public-contact", "approve-schedule", "approve-asset-rights",
            "acknowledge-worker-disabled");

    private StambulDataImportCommand() {
    }

    public static void main(String[] arguments) {
        int exitCode = run(arguments, System.getenv());
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] arguments, Map<String, String> environment) {
        try {
            Options options = Options.parse(arguments);
            DataImportRequest request = options.request();
            rejectContaminatingEnvironment(environment);
            rejectContaminatingSystemProperties(System.getProperties());
            try (var context = new SpringApplicationBuilder(StambulDataImportApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(authoritativeSpringArguments(environment, request))) {
                var importer = context.getBean(ApprovedDatasetImporter.class);
                DataImportResult result = options.apply()
                        ? importer.apply(request)
                        : importer.dryRun(request);
                String json = context.getBean(ObjectMapper.class).writeValueAsString(result);
                System.out.println("DATA_IMPORT_RESULT " + json);
            }
            return 0;
        } catch (DataImportConflictException exception) {
            System.err.println("DATA_IMPORT_CONFLICT " + exception.getMessage());
            return 3;
        } catch (IllegalArgumentException exception) {
            System.err.println("DATA_IMPORT_INVALID " + exception.getMessage());
            return 2;
        } catch (Exception exception) {
            System.err.println("DATA_IMPORT_FAILED " + exception.getClass().getSimpleName());
            return 1;
        }
    }

    static String[] authoritativeSpringArguments(
            Map<String, String> environment,
            DataImportRequest request) {
        var values = new LinkedHashMap<String, String>();
        values.put("spring.main.web-application-type", "none");
        values.put("spring.main.banner-mode", "off");
        values.put("spring.profiles.active", "");
        values.put("spring.config.location", "optional:classpath:/application.yml");
        values.put("spring.config.additional-location", "");
        values.put("spring.config.import", "optional:");
        values.put("spring.datasource.url", requiredEnvironment(environment, "DATA_IMPORT_JDBC_URL"));
        values.put("spring.datasource.username", requiredEnvironment(environment, "DATA_IMPORT_DB_USER"));
        values.put("spring.datasource.password", requiredEnvironment(environment, "DATA_IMPORT_DB_PASSWORD"));
        values.put("spring.flyway.enabled", "false");
        values.put("spring.sql.init.mode", "never");
        values.put("spring.jpa.generate-ddl", "false");
        values.put("spring.jpa.open-in-view", "false");
        values.put("spring.jpa.hibernate.ddl-auto", "validate");
        values.put("app.data-import.enabled", "true");
        values.put("app.assets.storage.endpoint", requiredEnvironment(environment, "DATA_IMPORT_S3_ENDPOINT"));
        values.put("app.assets.storage.region", requiredEnvironment(environment, "DATA_IMPORT_S3_REGION"));
        values.put("app.assets.storage.bucket", requiredEnvironment(environment, "DATA_IMPORT_S3_BUCKET"));
        values.put("app.assets.storage.access-key", requiredEnvironment(environment, "DATA_IMPORT_S3_ACCESS_KEY"));
        values.put("app.assets.storage.secret-key", requiredEnvironment(environment, "DATA_IMPORT_S3_SECRET_KEY"));
        values.put("app.assets.storage.path-style-access", environment.getOrDefault("DATA_IMPORT_S3_PATH_STYLE", "true"));
        String cdnBaseUrl = requiredEnvironment(environment, "DATA_IMPORT_CDN_BASE_URL");
        values.put("app.assets.cdn-base-url", cdnBaseUrl);
        values.put("app.external.asset-base-url", cdnBaseUrl);
        values.put(
                "app.external.public-base-url",
                request.canonicalUrlOverride() == null
                        ? "https://stambul.localhost"
                        : request.canonicalUrlOverride().toString());
        values.put("app.security.request-authority.allowed-hosts", "127.0.0.1");
        values.put("app.security.cors.allowed-origins", "");
        values.put("app.security.trusted-proxies.enabled", "false");
        values.put("app.worker.enabled", "false");
        values.put("management.tracing.export.otlp.enabled", "false");
        values.put("management.otlp.metrics.export.enabled", "false");
        values.put("management.logging.export.otlp.enabled", "false");
        return values.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    static void rejectContaminatingEnvironment(Map<String, String> environment) {
        var names = environment.keySet().stream()
                .filter(StambulDataImportCommand::isContaminatingEnvironmentName)
                .sorted()
                .toList();
        if (!names.isEmpty()) {
            throw new IllegalArgumentException(
                    "Contaminating inherited environment is forbidden: " + String.join(",", names));
        }
    }

    static void rejectContaminatingSystemProperties(Properties properties) {
        var names = properties.stringPropertyNames().stream()
                .filter(name -> name.matches("(?iu)^(?:spring|app|server|management|database|asset)\\..+"))
                .sorted()
                .toList();
        if (!names.isEmpty()) {
            throw new IllegalArgumentException(
                    "Contaminating JVM properties are forbidden: " + String.join(",", names));
        }
    }

    private static boolean isContaminatingEnvironmentName(String name) {
        return name.matches("(?iu)^(?:SPRING_|SERVER_|MANAGEMENT_|DATABASE_|APP_|ASSET_|OTEL_|LOGGING_).+")
                || name.matches("(?iu)^(?:PUBLIC_BASE_URL|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|JAVA_OPTS|CLASSPATH)$");
    }

    private static String requiredEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable " + name);
        }
        return value;
    }

    private record Options(
            Path bundle,
            Path assetRoot,
            boolean apply,
            DataImportTarget target,
            String timezone,
            URI canonicalUrl,
            String approvalArtifact,
            boolean publicContactApproved,
            boolean scheduleApproved,
            boolean assetRightsApproved,
            boolean workerDisabledAcknowledged) {

        static Options parse(String[] arguments) {
            var values = new LinkedHashMap<String, String>();
            var flags = new java.util.LinkedHashSet<String>();
            for (String argument : arguments) {
                if (argument == null || !argument.startsWith("--")) {
                    throw new IllegalArgumentException("Every importer argument must start with --");
                }
                int separator = argument.indexOf('=');
                String name = separator < 0 ? argument.substring(2) : argument.substring(2, separator);
                if (FLAG_OPTIONS.contains(name) && separator < 0) {
                    if (!flags.add(name)) {
                        throw new IllegalArgumentException("Duplicate importer flag --" + name);
                    }
                } else if (VALUE_OPTIONS.contains(name) && separator > 2) {
                    String value = argument.substring(separator + 1);
                    if (value.isBlank() || values.putIfAbsent(name, value) != null) {
                        throw new IllegalArgumentException("Invalid or duplicate importer option --" + name);
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported importer argument --" + name);
                }
            }
            String mode = required(values, "mode");
            boolean apply = switch (mode) {
                case "dry-run" -> false;
                case "apply" -> true;
                default -> throw new IllegalArgumentException("--mode must be dry-run or apply");
            };
            DataImportTarget target = switch (values.getOrDefault("target", "local-test")) {
                case "local-test" -> DataImportTarget.LOCAL_TEST;
                case "production" -> DataImportTarget.PRODUCTION;
                default -> throw new IllegalArgumentException("--target must be local-test or production");
            };
            URI canonicalUrl = values.containsKey("canonical-url")
                    ? URI.create(values.get("canonical-url"))
                    : null;
            return new Options(
                    Path.of(required(values, "bundle")),
                    Path.of(required(values, "asset-root")),
                    apply,
                    target,
                    values.get("timezone"),
                    canonicalUrl,
                    values.get("approval-artifact"),
                    flags.contains("approve-public-contact"),
                    flags.contains("approve-schedule"),
                    flags.contains("approve-asset-rights"),
                    flags.contains("acknowledge-worker-disabled"));
        }

        DataImportRequest request() {
            return new DataImportRequest(
                    bundle,
                    assetRoot,
                    target,
                    timezone,
                    canonicalUrl,
                    approvalArtifact,
                    publicContactApproved,
                    scheduleApproved,
                    assetRightsApproved,
                    workerDisabledAcknowledged);
        }

        private static String required(Map<String, String> values, String name) {
            String value = values.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Missing required importer option --" + name);
            }
            return value;
        }
    }
}
