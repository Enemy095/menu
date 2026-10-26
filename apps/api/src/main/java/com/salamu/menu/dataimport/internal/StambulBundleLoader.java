package com.salamu.menu.dataimport.internal;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.salamu.menu.asset.api.AssetImportRequest;
import com.salamu.menu.asset.api.AssetImporter;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.dataimport.api.DataImportRequest;
import com.salamu.menu.dataimport.api.DataImportTarget;

final class StambulBundleLoader {

    static final String SELECTION = "STAMBUL_APPROVED";
    static final int EXPECTED_CATEGORY_COUNT = 4;
    static final int EXPECTED_ITEM_COUNT = 23;
    static final String APPROVED_BUNDLE_SHA256 =
            "7b04313f8907b44820d9eccf2490cea118504e68be2ea20ffef6186df6955b40";
    static final UUID APPROVED_UUID_NAMESPACE =
            UUID.fromString("e9b2ab7a-0a0a-5309-9403-39ed9ec91d4b");
    private static final long MAX_BUNDLE_BYTES = 256 * 1024;
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Map<String, SourceAnchor> APPROVED_SOURCES = Map.of(
            "config", new SourceAnchor(
                    "Электронное меню/restaurants/stambul/config.js",
                    4589,
                    "99e636d9c8528f27586f5101bb7bfccd65609c89f4d0d2e71aec77e98db04d52"),
            "features", new SourceAnchor(
                    "Электронное меню/restaurants/stambul/features.js",
                    684,
                    "03ff360aa55e0e4d2b9b8799491ddc2e1832eeb407710095e31cf3ed483f7dfa"),
            "order-types", new SourceAnchor(
                    "Электронное меню/restaurants/stambul/order-types.js",
                    727,
                    "be26e63d26d504234e987d760f429b0798d26bf425748c6f471a19c28932dd7a"),
            "menu", new SourceAnchor(
                    "Электронное меню/restaurants/stambul/menu.js",
                    7085,
                    "9fdedb8be5882a04aa824c5b9618a755716447a0dc077b2e6b78726e039eb8ec"),
            "theme", new SourceAnchor(
                    "Электронное меню/themes/stambul/theme.css",
                    519,
                    "07b9cc54fc6ecb669d3daeff97f8c8b5c25804cde1816dee0186a094ecc29b9c"),
            "logo", new SourceAnchor(
                    "Электронное меню/restaurants/stambul/branding/logo.png",
                    43728,
                    "90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b"));
    private static final List<String> EXPECTED_CATEGORY_IDS = List.of(
            "rolls", "pizza", "sides", "burgers");
    private static final Set<String> EXPECTED_FEATURES = Set.of(
            "orders", "contacts", "geo_location", "social_links", "delivery", "pickup",
            "preorder", "custom_theme", "whatsapp_fallback", "promotions", "loyalty",
            "reservations", "delivery_blocks");

    private final ObjectMapper objectMapper;
    private final AssetImporter assetImporter;

    StambulBundleLoader(ObjectMapper objectMapper, AssetImporter assetImporter) {
        this.objectMapper = objectMapper;
        this.assetImporter = assetImporter;
    }

    PreparedStambulImport load(DataImportRequest request) {
        try {
            Path bundlePath = trustedRegularFile(request.bundlePath(), null);
            long bundleSize = Files.size(bundlePath);
            require(bundleSize > 0 && bundleSize <= MAX_BUNDLE_BYTES,
                    "Approved bundle size is invalid");
            byte[] bundleBytes = Files.readAllBytes(bundlePath);
            require(sha256(bundleBytes).equals(APPROVED_BUNDLE_SHA256),
                    "Bundle digest differs from the externally approved artifact");
            StambulApprovedBundle bundle = objectMapper.readValue(bundleBytes, StambulApprovedBundle.class);
            validateBundle(bundle);
            Map<String, byte[]> sourceBytes = verifySources(bundle, request.assetRoot());
            byte[] logoBytes = sourceBytes.get("logo");
            require(logoBytes != null, "Approved logo source is missing");
            var assetRequest = new AssetImportRequest(
                    AssetKind.valueOf(bundle.asset().kind()),
                    bundle.asset().contentType(),
                    bundle.asset().extension(),
                    logoBytes);
            var assetReceipt = assetImporter.validate(assetRequest);
            require(assetReceipt.sizeBytes() == bundle.asset().sizeBytes(), "Logo size differs from bundle");
            require(assetReceipt.sha256().equals(bundle.asset().sha256()), "Logo hash differs from bundle");

            EffectiveValues effective = effectiveValues(bundle, request);
            UUID namespace = APPROVED_UUID_NAMESPACE;
            UUID assetId = DeterministicUuid.versionFive(namespace, "asset:logo");
            UUID restaurantId = DeterministicUuid.versionFive(namespace, "restaurant:stambul");
            UUID menuVersionId = DeterministicUuid.versionFive(namespace, "menu:stambul:1");
            Map<String, UUID> categoryIds = new LinkedHashMap<>();
            Map<String, UUID> itemIds = new LinkedHashMap<>();
            for (var category : bundle.menu().categories()) {
                categoryIds.put(
                        category.publicId(),
                        DeterministicUuid.versionFive(namespace, "category:" + category.publicId()));
                for (var item : category.items()) {
                    itemIds.put(
                            item.publicId(),
                            DeterministicUuid.versionFive(namespace, "item:" + item.publicId()));
                }
            }
            String bundleSha256 = APPROVED_BUNDLE_SHA256;
            String effectiveSha256 = sha256((bundleSha256 + "\n"
                    + request.target() + "\n"
                    + effective.timezone() + "\n"
                    + effective.canonicalUrl() + "\n"
                    + effective.approvalArtifact()).getBytes(StandardCharsets.UTF_8));
            return new PreparedStambulImport(
                    bundle,
                    assetRequest,
                    assetReceipt,
                    bundleSha256,
                    effectiveSha256,
                    effective.timezone(),
                    effective.canonicalUrl(),
                    namespace,
                    assetId,
                    restaurantId,
                    menuVersionId,
                    Map.copyOf(categoryIds),
                    Map.copyOf(itemIds));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Approved bundle is not valid JSON", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Approved bundle source could not be read", exception);
        }
    }

    private static void validateBundle(StambulApprovedBundle bundle) {
        require(bundle != null, "Approved bundle is missing");
        require(bundle.schemaVersion() == 1, "Unsupported approved bundle schema");
        require(SELECTION.equals(bundle.selection()), "Only the owner-approved Stambul selection is accepted");
        require("stambul".equals(bundle.datasetKey()), "Approved dataset key is invalid");
        require(APPROVED_UUID_NAMESPACE.toString().equals(bundle.uuidNamespace()),
                "UUID namespace differs from the approved namespace");
        require(bundle.approval() != null
                        && bundle.approval().preserveLegacyItemText()
                        && !blank(bundle.approval().decisionArtifact()),
                "Owner approval evidence is incomplete");
        require(bundle.localTestBaseline() != null
                        && "ru-RU".equals(bundle.localTestBaseline().locale())
                        && "RUB".equals(bundle.localTestBaseline().currency())
                        && "none".equals(bundle.localTestBaseline().feePolicy())
                        && bundle.localTestBaseline().minimumOrderMinor() == null
                        && bundle.localTestBaseline().freeDeliveryThresholdMinor() == null
                        && (bundle.localTestBaseline().notificationDestination() == null
                                || bundle.localTestBaseline().notificationDestination().isNull())
                        && !bundle.localTestBaseline().workerEnabled(),
                "Local/test operational baseline is invalid");
        require(bundle.asset() != null
                        && "logo".equals(bundle.asset().name())
                        && "LOGO".equals(bundle.asset().kind())
                        && "image/png".equals(bundle.asset().contentType())
                        && "png".equals(bundle.asset().extension())
                        && bundle.asset().sizeBytes() > 0
                        && validSha(bundle.asset().sha256())
                        && !blank(bundle.asset().altText())
                        && bundle.asset().altText().length() <= 160,
                "Approved logo metadata is invalid");
        validateSite(bundle.site());
        validateRestaurant(bundle.restaurant());
        validateMenu(bundle.menu());
    }

    private static void validateSite(StambulApprovedBundle.Site site) {
        require(site != null && !blank(site.displayName()) && !blank(site.businessType()),
                "Site identity is incomplete");
        require(site.designTokens() != null && site.designTokens().isObject(),
                "Design tokens are invalid");
        require("#7C6A64".equals(text(site.designTokens(), "mutedTextColor")),
                "Approved accessible muted text color is required");
        require("Georgia, Times New Roman, serif".equals(text(site.designTokens(), "fontFamily")),
                "Approved safe font family is required");
        require(site.localizedTexts() != null && site.localizedTexts().isArray()
                        && site.localizedTexts().size() == 1,
                "Exactly one localized site text is required");
        require(site.hero() != null && site.hero().isObject() && site.hero().get("imageAssetId") == null,
                "Stambul has no approved hero asset");
        require(site.seo() != null && !blank(site.seo().title()) && !blank(site.seo().description()),
                "SEO copy is incomplete");
        require(site.profile() != null
                        && !blank(site.profile().publicPhone())
                        && site.profile().address() == null
                        && site.profile().workingHours() != null
                        && site.profile().workingHours().isArray()
                        && site.profile().workingHours().size() == 7
                        && site.profile().socialLinks() != null
                        && site.profile().socialLinks().isArray()
                        && site.profile().socialLinks().isEmpty(),
                "Approved public profile is invalid");
        require(site.features() != null && site.features().size() == EXPECTED_FEATURES.size(),
                "Feature set cardinality is invalid");
        Set<String> featureKeys = new HashSet<>();
        for (var feature : site.features()) {
            require(feature != null && EXPECTED_FEATURES.contains(feature.key())
                            && feature.config() != null && feature.config().isObject()
                            && featureKeys.add(feature.key()),
                    "Feature set is invalid");
        }
        require(featureKeys.equals(EXPECTED_FEATURES), "Feature set differs from approval");
        require(!enabled(site.features(), "geo_location"), "Zero coordinates cannot enable geolocation");
        require(enabled(site.features(), "preorder")
                        && enabled(site.features(), "delivery")
                        && enabled(site.features(), "pickup"),
                "Approved order types are incomplete");
    }

    private static void validateRestaurant(StambulApprovedBundle.Restaurant restaurant) {
        require(restaurant != null
                        && "stambul".equals(restaurant.externalKey())
                        && "stambul".equals(restaurant.slug())
                        && !blank(restaurant.displayName()),
                "Approved restaurant is invalid");
    }

    private static void validateMenu(StambulApprovedBundle.Menu menu) {
        require(menu != null && menu.version() == 1 && "RUB".equals(menu.currency()),
                "Approved menu metadata is invalid");
        Instant.parse(menu.publishedAt());
        require(menu.categories() != null && menu.categories().size() == EXPECTED_CATEGORY_COUNT,
                "Approved category count is invalid");
        List<String> categoryIds = menu.categories().stream()
                .map(StambulApprovedBundle.Category::publicId)
                .toList();
        require(categoryIds.equals(EXPECTED_CATEGORY_IDS), "Approved category order is invalid");
        Set<String> itemIds = new HashSet<>();
        int itemCount = 0;
        for (var category : menu.categories()) {
            require(validPublicId(category.publicId()) && !blank(category.name()) && category.items() != null,
                    "Approved category is invalid");
            for (var item : category.items()) {
                require(item != null
                                && validPublicId(item.publicId())
                                && itemIds.add(item.publicId())
                                && !blank(item.name())
                                && item.name().length() <= 160
                                && item.description() != null
                                && item.description().length() <= 1000
                                && item.priceMinor() >= 0
                                && item.priceMinor() <= 1_000_000_000_000L,
                        "Approved menu item is invalid");
                itemCount++;
            }
        }
        require(itemCount == EXPECTED_ITEM_COUNT, "Approved item count is invalid");
        String preservedName = menu.categories().stream()
                .flatMap(category -> category.items().stream())
                .filter(item -> "pizza-four-cheese".equals(item.publicId()))
                .map(StambulApprovedBundle.Item::name)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Approved repeated-space item is missing"));
        require("Пицца    4 сыра".equals(preservedName), "Approved legacy item text was normalized");
    }

    private static Map<String, byte[]> verifySources(StambulApprovedBundle bundle, Path assetRoot)
            throws IOException {
        require(bundle.sources() != null && bundle.sources().size() == APPROVED_SOURCES.size(),
                "Approved source set cardinality is invalid");
        Set<String> sourceKinds = new HashSet<>();
        Map<String, byte[]> result = new LinkedHashMap<>();
        Path trustedRoot = trustedDirectory(assetRoot);
        for (var source : bundle.sources()) {
            SourceAnchor anchor = source == null ? null : APPROVED_SOURCES.get(source.kind());
            require(source != null
                            && anchor != null
                            && sourceKinds.add(source.kind())
                            && anchor.path().equals(source.path())
                            && anchor.sizeBytes() == source.sizeBytes()
                            && anchor.sha256().equals(source.sha256()),
                    "Approved source metadata is invalid");
            Path sourcePath = safeResolve(trustedRoot, anchor.path());
            long rawSize = Files.size(sourcePath);
            require(rawSize > 0 && rawSize <= Math.max(anchor.sizeBytes() * 2, anchor.sizeBytes() + 1024),
                    "Approved source size is outside the safe bound: " + source.kind());
            byte[] bytes = Files.readAllBytes(sourcePath);
            if (!"logo".equals(source.kind())) {
                bytes = normalizeTextLineEndings(bytes);
            }
            require(bytes.length == anchor.sizeBytes(), "Approved source size mismatch: " + source.kind());
            require(sha256(bytes).equals(anchor.sha256()), "Approved source hash mismatch: " + source.kind());
            result.put(source.kind(), bytes);
        }
        require(sourceKinds.equals(APPROVED_SOURCES.keySet()), "Approved source set is incomplete");
        require(bundle.asset().sourcePath().equals(bundle.sources().stream()
                        .filter(source -> "logo".equals(source.kind()))
                        .findFirst()
                        .orElseThrow()
                        .path()),
                "Logo source path differs from source inventory");
        return Map.copyOf(result);
    }

    /**
     * Approval hashes use the canonical Git LF representation. Existing Windows clones may still
     * contain CRLF bytes from before the repository gained its eol=lf attributes, so text sources
     * are normalized in memory. Binary approval sources are never passed to this method.
     */
    static byte[] normalizeTextLineEndings(byte[] bytes) {
        byte[] normalized = new byte[bytes.length];
        int write = 0;
        for (int read = 0; read < bytes.length; read++) {
            byte current = bytes[read];
            if (current == '\r') {
                if (read + 1 < bytes.length && bytes[read + 1] == '\n') {
                    read++;
                }
                normalized[write++] = '\n';
            } else {
                normalized[write++] = current;
            }
        }
        return java.util.Arrays.copyOf(normalized, write);
    }

    private static EffectiveValues effectiveValues(
            StambulApprovedBundle bundle,
            DataImportRequest request) {
        if (request.target() == DataImportTarget.LOCAL_TEST) {
            var baseline = bundle.localTestBaseline();
            require(baseline.scheduleApproved()
                            && baseline.publicContactApproved()
                            && baseline.assetRightsApprovedForLocalTest(),
                    "Local/test baseline approval is incomplete");
            ZoneId.of(baseline.timezone());
            URI canonicalUrl = safeCanonicalUrl(baseline.canonicalUrl(), true);
            return new EffectiveValues(
                    baseline.timezone(), canonicalUrl, bundle.approval().decisionArtifact());
        }

        require(!blank(request.timezoneOverride()), "Production timezone override is required");
        ZoneId.of(request.timezoneOverride());
        require(request.canonicalUrlOverride() != null, "Production canonical URL override is required");
        URI canonicalUrl = safeCanonicalUrl(request.canonicalUrlOverride().toString(), false);
        require(!blank(request.approvalArtifact())
                        && request.publicContactApproved()
                        && request.scheduleApproved()
                        && request.assetRightsApproved()
                        && request.workerDisabledAcknowledged(),
                "Production approval flags are incomplete");
        return new EffectiveValues(
                request.timezoneOverride(), canonicalUrl, request.approvalArtifact());
    }

    private static URI safeCanonicalUrl(String value, boolean allowLocalhost) {
        URI uri = URI.create(value);
        require(uri.isAbsolute()
                        && "https".equalsIgnoreCase(uri.getScheme())
                        && uri.getHost() != null
                        && uri.getUserInfo() == null
                        && uri.getQuery() == null
                        && uri.getFragment() == null,
                "Canonical URL must be an origin-safe HTTPS URL");
        if (!allowLocalhost) {
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            require(!host.equals("localhost")
                            && !host.endsWith(".localhost")
                            && !host.endsWith(".invalid"),
                    "Production canonical URL cannot use a local/test host");
        }
        return uri;
    }

    static Path safeResolve(Path root, String relative) throws IOException {
        require(relative != null && !relative.isBlank(), "Approved source path is blank");
        Path resolved = root.resolve(relative).normalize();
        require(resolved.startsWith(root), "Approved source escapes the asset root");
        Path current = root;
        for (Path component : root.relativize(resolved)) {
            current = current.resolve(component);
            require(!Files.isSymbolicLink(current), "Approved source path contains a symbolic link");
        }
        return trustedRegularFile(resolved, root);
    }

    private static Path trustedDirectory(Path directory) throws IOException {
        require(!Files.isSymbolicLink(directory), "Asset root cannot be a symbolic link");
        Path real = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS), "Asset root is not a directory");
        return real;
    }

    private static Path trustedRegularFile(Path file, Path requiredRoot) throws IOException {
        require(!Files.isSymbolicLink(file), "Approved input cannot be a symbolic link");
        Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        require(Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS), "Approved input is not a regular file");
        if (requiredRoot != null) {
            require(real.startsWith(requiredRoot), "Approved input resolves outside the asset root");
        }
        return real;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.asString() : null;
    }

    private static boolean enabled(List<StambulApprovedBundle.Feature> features, String key) {
        return features.stream().filter(feature -> key.equals(feature.key()))
                .findFirst().orElseThrow().enabled();
    }

    private static boolean validSha(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static boolean validPublicId(String value) {
        return value != null && value.length() <= 120 && PUBLIC_ID.matcher(value).matches();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record EffectiveValues(String timezone, URI canonicalUrl, String approvalArtifact) {
    }

    private record SourceAnchor(String path, long sizeBytes, String sha256) {
    }
}
