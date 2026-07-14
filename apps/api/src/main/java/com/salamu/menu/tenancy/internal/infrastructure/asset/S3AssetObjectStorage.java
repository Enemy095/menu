package com.salamu.menu.tenancy.internal.infrastructure.asset;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.salamu.menu.tenancy.internal.application.asset.AssetObject;
import com.salamu.menu.tenancy.internal.application.asset.AssetObjectDescriptor;
import com.salamu.menu.tenancy.internal.application.asset.AssetObjectKey;
import com.salamu.menu.tenancy.internal.application.asset.AssetObjectProvenanceConflictException;
import com.salamu.menu.tenancy.internal.application.asset.AssetObjectStorage;
import com.salamu.menu.tenancy.internal.application.asset.ProvisionedAssetObjectDisposition;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
final class S3AssetObjectStorage implements AssetObjectStorage {

    static final String IMMUTABLE_CACHE_CONTROL = "public,max-age=31536000,immutable";
    static final String SHA_256_METADATA_KEY = "sha256";
    static final String RUN_FINGERPRINT_METADATA_KEY = "legacy-import-run-fingerprint";
    private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private final S3Client s3Client;
    private final String bucket;

    S3AssetObjectStorage(S3Client s3Client, TenantAssetProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.storage().bucket();
    }

    @Override
    public void putImmutable(AssetObject object) {
        HeadObjectResponse existing = head(object.key());
        if (existing != null) {
            verifyOrdinaryObject(AssetObjectDescriptor.from(object), existing);
            return;
        }

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(object.key().value())
                .contentType(object.contentType())
                .contentLength(object.sizeBytes())
                .cacheControl(IMMUTABLE_CACHE_CONTROL)
                .checksumSHA256(object.sha256().base64())
                .metadata(Map.of(SHA_256_METADATA_KEY, object.sha256().value()))
                .ifNoneMatch("*")
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(object.content()));
        } catch (S3Exception exception) {
            if (exception.statusCode() != 412) {
                throw new AssetStorageException("Failed to store tenant asset", exception);
            }
        } catch (SdkException exception) {
            throw new AssetStorageException("Failed to store tenant asset", exception);
        }
        verifyOrdinaryObject(AssetObjectDescriptor.from(object), requireHead(object.key()));
    }

    @Override
    public ProvisionedAssetObjectDisposition putProvisionedImmutable(
            AssetObject object,
            String runFingerprint) {
        requireRunFingerprint(runFingerprint);
        AssetObjectDescriptor descriptor = AssetObjectDescriptor.from(object);
        HeadObjectResponse existing = head(object.key());
        if (existing != null) {
            verifyProvisionedObject(descriptor, runFingerprint, existing);
            return ProvisionedAssetObjectDisposition.RECOVERED;
        }

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(object.key().value())
                .contentType(object.contentType())
                .contentLength(object.sizeBytes())
                .cacheControl(IMMUTABLE_CACHE_CONTROL)
                .checksumSHA256(object.sha256().base64())
                .metadata(Map.of(
                        SHA_256_METADATA_KEY, object.sha256().value(),
                        RUN_FINGERPRINT_METADATA_KEY, runFingerprint))
                .ifNoneMatch("*")
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(object.content()));
            verifyProvisionedObject(descriptor, runFingerprint, requireHead(object.key()));
            return ProvisionedAssetObjectDisposition.CREATED;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 412) {
                verifyProvisionedObject(descriptor, runFingerprint, requireHead(object.key()));
                return ProvisionedAssetObjectDisposition.RECOVERED;
            }
            return reconcileUncertainProvisioningPut(descriptor, runFingerprint, exception);
        } catch (SdkException exception) {
            return reconcileUncertainProvisioningPut(descriptor, runFingerprint, exception);
        }
    }

    @Override
    public boolean inspectProvisionedImmutable(AssetObjectDescriptor object, String runFingerprint) {
        requireRunFingerprint(runFingerprint);
        HeadObjectResponse existing = head(object.key());
        if (existing == null) {
            return false;
        }
        verifyProvisionedObject(object, runFingerprint, existing);
        return true;
    }

    @Override
    public boolean deleteProvisionedImmutable(AssetObjectDescriptor object, String runFingerprint) {
        requireRunFingerprint(runFingerprint);
        HeadObjectResponse existing = head(object.key());
        if (existing == null) {
            return false;
        }
        verifyProvisionedObject(object, runFingerprint, existing);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(object.key().value())
                    .ifMatch(existing.eTag())
                    .build());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            if (exception.statusCode() == 412) {
                throw new AssetObjectProvenanceConflictException();
            }
            throw new AssetStorageException("Failed to delete provisioned tenant asset", exception);
        } catch (SdkException exception) {
            throw new AssetStorageException("Failed to delete provisioned tenant asset", exception);
        }
        if (head(object.key()) != null) {
            throw new AssetStorageException("Provisioned tenant asset remained visible after deletion");
        }
        return true;
    }

    private ProvisionedAssetObjectDisposition reconcileUncertainProvisioningPut(
            AssetObjectDescriptor descriptor,
            String runFingerprint,
            SdkException failure) {
        HeadObjectResponse afterFailure;
        try {
            afterFailure = head(descriptor.key());
        } catch (RuntimeException inspectionFailure) {
            failure.addSuppressed(inspectionFailure);
            throw new AssetStorageException("Failed to reconcile provisioned tenant asset", failure);
        }
        if (afterFailure == null) {
            throw new AssetStorageException("Failed to store provisioned tenant asset", failure);
        }
        verifyProvisionedObject(descriptor, runFingerprint, afterFailure);
        return ProvisionedAssetObjectDisposition.RECOVERED;
    }

    private HeadObjectResponse head(AssetObjectKey objectKey) {
        try {
            return s3Client.headObject(headRequest(objectKey));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return null;
            }
            throw new AssetStorageException("Failed to inspect tenant asset", exception);
        } catch (SdkException exception) {
            throw new AssetStorageException("Failed to inspect tenant asset", exception);
        }
    }

    private HeadObjectResponse requireHead(AssetObjectKey objectKey) {
        HeadObjectResponse response = head(objectKey);
        if (response == null) {
            throw new AssetStorageException("Tenant asset was not visible after upload");
        }
        return response;
    }

    private HeadObjectRequest headRequest(AssetObjectKey objectKey) {
        return HeadObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey.value())
                .build();
    }

    private static void verifyOrdinaryObject(AssetObjectDescriptor expected, HeadObjectResponse actual) {
        boolean matches = actual.contentLength() == expected.sizeBytes()
                && expected.contentType().equals(actual.contentType())
                && IMMUTABLE_CACHE_CONTROL.equals(actual.cacheControl())
                && expected.sha256().value().equals(actual.metadata().get(SHA_256_METADATA_KEY));
        if (!matches) {
            throw new AssetStorageException(
                    "Immutable tenant asset key already contains different metadata or content");
        }
    }

    private static void verifyProvisionedObject(
            AssetObjectDescriptor expected,
            String runFingerprint,
            HeadObjectResponse actual) {
        boolean matches = actual.contentLength() == expected.sizeBytes()
                && expected.contentType().equals(actual.contentType())
                && IMMUTABLE_CACHE_CONTROL.equals(actual.cacheControl())
                && expected.sha256().value().equals(actual.metadata().get(SHA_256_METADATA_KEY))
                && runFingerprint.equals(actual.metadata().get(RUN_FINGERPRINT_METADATA_KEY));
        if (!matches) {
            throw new AssetObjectProvenanceConflictException();
        }
    }

    private static void requireRunFingerprint(String runFingerprint) {
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        if (!LOWERCASE_SHA_256.matcher(runFingerprint).matches()) {
            throw new IllegalArgumentException("runFingerprint must be lowercase SHA-256");
        }
    }
}
