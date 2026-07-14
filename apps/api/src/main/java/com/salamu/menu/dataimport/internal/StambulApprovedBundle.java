package com.salamu.menu.dataimport.internal;

import java.util.List;

import tools.jackson.databind.JsonNode;

record StambulApprovedBundle(
        int schemaVersion,
        String selection,
        String datasetKey,
        String uuidNamespace,
        Approval approval,
        List<Source> sources,
        LocalTestBaseline localTestBaseline,
        Asset asset,
        Site site,
        Restaurant restaurant,
        Menu menu) {

    record Approval(
            String decisionArtifact,
            String decisionDate,
            boolean preserveLegacyItemText,
            List<String> approvedLocalNormalizations) {
    }

    record Source(String kind, String path, long sizeBytes, String sha256) {
    }

    record LocalTestBaseline(
            String locale,
            String currency,
            String timezone,
            String canonicalUrl,
            boolean scheduleApproved,
            boolean publicContactApproved,
            boolean assetRightsApprovedForLocalTest,
            String feePolicy,
            Long minimumOrderMinor,
            Long freeDeliveryThresholdMinor,
            JsonNode notificationDestination,
            boolean workerEnabled) {
    }

    record Asset(
            String name,
            String kind,
            String sourcePath,
            String contentType,
            String extension,
            long sizeBytes,
            String sha256,
            String altText) {
    }

    record Site(
            String displayName,
            String businessType,
            JsonNode designTokens,
            JsonNode localizedTexts,
            JsonNode hero,
            Seo seo,
            Profile profile,
            List<Feature> features) {
    }

    record Seo(String title, String description) {
    }

    record Profile(
            String publicPhone,
            String publicPhoneLabel,
            String publicEmail,
            String address,
            String workingHoursText,
            JsonNode workingHours,
            JsonNode socialLinks) {
    }

    record Feature(String key, boolean enabled, JsonNode config) {
    }

    record Restaurant(String externalKey, String slug, String displayName) {
    }

    record Menu(long version, String publishedAt, String currency, List<Category> categories) {
    }

    record Category(String publicId, String name, List<Item> items) {
    }

    record Item(String publicId, String name, String description, long priceMinor) {
    }
}
