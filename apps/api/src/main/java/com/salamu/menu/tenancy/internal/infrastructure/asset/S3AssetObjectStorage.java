package com.salamu.menu.tenancy.internal.infrastructure.asset;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.salamu.menu.tenancy.internal.application.asset.AssetObject;
import com.salamu.menu.tenancy.internal.application.asset.AssetObjectStorage;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
final class S3AssetObjectStorage implements AssetObjectStorage {

    static final String IMMUTABLE_CACHE_CONTROL = "public,max-age=31536000,immutable";
    private static final String SHA_256_METADATA_KEY = "sha256";

    private final S3Client s3Client;
    private final String bucket;

    S3AssetObjectStorage(S3Client s3Client, TenantAssetProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.storage().bucket();
    }

    @Override
    public void putImmutable(AssetObject object) {
        HeadObjectResponse existing = head(object);
        if (existing != null) {
            verifyExisting(object, existing);
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
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(object.content()));
        } catch (SdkException exception) {
            throw new AssetStorageException("Failed to store tenant asset", exception);
        }
        verifyExisting(object, requireHead(object));
    }

    private HeadObjectResponse head(AssetObject object) {
        try {
            return s3Client.headObject(headRequest(object));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return null;
            }
            throw new AssetStorageException("Failed to inspect tenant asset", exception);
        } catch (SdkException exception) {
            throw new AssetStorageException("Failed to inspect tenant asset", exception);
        }
    }

    private HeadObjectResponse requireHead(AssetObject object) {
        HeadObjectResponse response = head(object);
        if (response == null) {
            throw new AssetStorageException("Tenant asset was not visible after upload");
        }
        return response;
    }

    private HeadObjectRequest headRequest(AssetObject object) {
        return HeadObjectRequest.builder()
                .bucket(bucket)
                .key(object.key().value())
                .build();
    }

    private static void verifyExisting(AssetObject expected, HeadObjectResponse actual) {
        boolean matches = actual.contentLength() == expected.sizeBytes()
                && expected.contentType().equals(actual.contentType())
                && IMMUTABLE_CACHE_CONTROL.equals(actual.cacheControl())
                && expected.sha256().value().equals(actual.metadata().get(SHA_256_METADATA_KEY));
        if (!matches) {
            throw new AssetStorageException("Immutable tenant asset key already contains different metadata or content");
        }
    }
}
