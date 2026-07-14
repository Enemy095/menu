package com.salamu.menu.tenancy.internal.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "TenantPersistenceEntity")
@Table(name = "tenants")
class TenantEntity {

    @Id
    UUID id;

    @Column(nullable = false, length = 63)
    String slug;

    @Column(nullable = false, length = 32)
    String status;

    @Column(nullable = false, length = 16)
    String locale;

    @Column(nullable = false, length = 3)
    String currency;

    @Column(nullable = false, length = 64)
    String timezone;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "TenantDomainPersistenceEntity")
@Table(name = "tenant_domains")
class TenantDomainEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(nullable = false, length = 253)
    String hostname;

    @Column(name = "domain_type", nullable = false, length = 32)
    String domainType;

    @Column(nullable = false, length = 32)
    String status;

    @Column(nullable = false)
    boolean canonical;

    @Column(name = "verified_at")
    OffsetDateTime verifiedAt;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "TenantBrandingPersistenceEntity")
@Table(name = "tenant_branding")
class TenantBrandingEntity {

    @Id
    @Column(name = "tenant_id")
    UUID tenantId;

    @Column(name = "display_name", nullable = false, length = 160)
    String displayName;

    @Column(name = "business_type", nullable = false, length = 80)
    String businessType;

    @Column(name = "logo_asset_id")
    UUID logoAssetId;

    @Column(name = "favicon_asset_id")
    UUID faviconAssetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "design_tokens", nullable = false, columnDefinition = "jsonb")
    Map<String, Object> designTokens;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "localized_texts", nullable = false, columnDefinition = "jsonb")
    List<Map<String, Object>> localizedTexts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    Map<String, Object> hero;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seo_metadata", nullable = false, columnDefinition = "jsonb")
    Map<String, Object> seoMetadata;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "TenantProfilePersistenceEntity")
@Table(name = "tenant_profiles")
class TenantProfileEntity {

    @Id
    @Column(name = "tenant_id")
    UUID tenantId;

    @Column(name = "public_phone", length = 64)
    String publicPhone;

    @Column(name = "public_phone_label", length = 160)
    String publicPhoneLabel;

    @Column(name = "public_email", length = 254)
    String publicEmail;

    @Column(name = "address_text", length = 500)
    String addressText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_hours", nullable = false, columnDefinition = "jsonb")
    List<Map<String, Object>> workingHours;

    @Column(name = "working_hours_text", length = 500)
    String workingHoursText;

    @Column(precision = 9, scale = 6)
    BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    BigDecimal longitude;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "social_links", nullable = false, columnDefinition = "jsonb")
    List<Map<String, Object>> socialLinks;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "TenantFeaturePersistenceEntity")
@Table(name = "tenant_features")
class TenantFeatureEntity {

    @EmbeddedId
    TenantFeatureId id;

    @Column(nullable = false)
    boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    Map<String, Object> config;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "TenantAssetPersistenceEntity")
@Table(name = "tenant_assets")
class TenantAssetEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(name = "object_key", nullable = false, length = 1024)
    String objectKey;

    @Column(name = "asset_type", nullable = false, length = 64)
    String assetType;

    @Column(name = "content_type", nullable = false, length = 127)
    String contentType;

    @Column(name = "size_bytes", nullable = false)
    long sizeBytes;

    @Column(nullable = false, length = 64)
    String sha256;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;
}
