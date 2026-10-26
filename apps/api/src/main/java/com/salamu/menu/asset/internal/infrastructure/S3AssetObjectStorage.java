package com.salamu.menu.asset.internal.infrastructure;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

import com.salamu.menu.asset.internal.application.AssetObject;
import com.salamu.menu.asset.internal.application.AssetObjectDescriptor;
import com.salamu.menu.asset.internal.application.AssetObjectKey;
import com.salamu.menu.asset.internal.application.AssetObjectStorage;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3AssetObjectStorage implements AssetObjectStorage {

    static final String IMMUTABLE_CACHE_CONTROL = "public,max-age=31536000,immutable";
    static final String SHA_256_METADATA_KEY = "sha256";

    private final S3Client s3Client;
    private final String bucket;

    public S3AssetObjectStorage(S3Client s3Client, String bucket) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
    }

    @Override
    public void putImmutable(AssetObject object) {
        Objects.requireNonNull(object, "object");
        HeadObjectResponse existing = head(object.key());
        if (existing != null) {
            verify(object.descriptor(), existing);
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
            if (exception.statusCode() != 409 && exception.statusCode() != 412) {
                throw AssetStorageException.operationFailed();
            }
        } catch (SdkException exception) {
            throw AssetStorageException.operationFailed();
        }

        verify(object.descriptor(), requireHead(object.key()));
    }

    @Override
    public void requirePresent(AssetObjectDescriptor object) {
        Objects.requireNonNull(object, "object");
        verify(object, requireHead(object.key()));
    }

    private HeadObjectResponse head(AssetObjectKey objectKey) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey.value())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return null;
            }
            throw AssetStorageException.operationFailed();
        } catch (SdkException exception) {
            throw AssetStorageException.operationFailed();
        }
    }

    private HeadObjectResponse requireHead(AssetObjectKey objectKey) {
        HeadObjectResponse response = head(objectKey);
        if (response == null) {
            throw AssetStorageException.unavailable();
        }
        return response;
    }

    private void verify(AssetObjectDescriptor expected, HeadObjectResponse actual) {
        boolean metadataMatches = actual.contentLength() == expected.sizeBytes()
                && expected.contentType().equals(actual.contentType())
                && IMMUTABLE_CACHE_CONTROL.equals(actual.cacheControl())
                && expected.sha256().value().equals(actual.metadata().get(SHA_256_METADATA_KEY));
        if (!metadataMatches) {
            throw AssetStorageException.integrityFailed();
        }

        String providerChecksum = actual.checksumSHA256();
        if (providerChecksum != null && !providerChecksum.isBlank()) {
            if (!expected.sha256().base64().equals(providerChecksum)) {
                throw AssetStorageException.integrityFailed();
            }
            return;
        }

        verifyDownloadedBytes(expected);
    }

    private void verifyDownloadedBytes(AssetObjectDescriptor expected) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(expected.key().value())
                .build();
        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
            if (response.response().contentLength() != expected.sizeBytes()) {
                throw AssetStorageException.integrityFailed();
            }

            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[8192];
            long total = 0;
            while (true) {
                int maximumRead = (int) Math.min(buffer.length, expected.sizeBytes() - total + 1);
                int read = response.read(buffer, 0, maximumRead);
                if (read == -1) {
                    break;
                }
                total += read;
                if (total > expected.sizeBytes()) {
                    throw AssetStorageException.integrityFailed();
                }
                digest.update(buffer, 0, read);
            }
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (total != expected.sizeBytes() || !expected.sha256().value().equals(actualSha256)) {
                throw AssetStorageException.integrityFailed();
            }
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw AssetStorageException.unavailable();
            }
            throw AssetStorageException.operationFailed();
        } catch (SdkException | IOException exception) {
            throw AssetStorageException.operationFailed();
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
