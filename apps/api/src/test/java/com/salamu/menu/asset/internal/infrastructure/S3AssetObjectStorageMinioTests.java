package com.salamu.menu.asset.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.internal.application.AssetContentPolicy;
import com.salamu.menu.asset.internal.application.AssetObject;
import com.salamu.menu.asset.internal.application.AssetObjectDescriptor;
import com.salamu.menu.asset.internal.application.AssetObjectKey;
import com.salamu.menu.asset.internal.application.AssetSha256;
import com.salamu.menu.asset.internal.application.AssetUploadContent;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3AssetObjectStorageMinioTests {

    private static final String BUCKET = "asset-module-integration";
    private static final String ACCESS_KEY = "integration-access";
    private static final String SECRET_KEY = "integration-secret";

    private GenericContainer<?> minio;
    private S3Client s3Client;
    private S3AssetObjectStorage storage;

    @BeforeAll
    void startMinio() {
        minio = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();
        s3Client = S3Client.builder()
                .endpointOverride(URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new S3AssetObjectStorage(s3Client, BUCKET);
    }

    @AfterAll
    void stopMinio() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    @Test
    void roundtripUsesExactlyTheCanonicalContentAddressedKey() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        AssetObject object = new AssetContentPolicy()
                .validate(new AssetUploadContent(AssetKind.LOGO, "image/png", "png", png))
                .toObject();

        storage.putImmutable(object);
        storage.putImmutable(object);

        var head = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET)
                .key(object.key().value())
                .build());
        byte[] downloaded = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(object.key().value())
                        .build())
                .asByteArray();
        assertThat(object.key().value()).isEqualTo("assets/logo/" + object.sha256().value() + ".png");
        assertThat(downloaded).isEqualTo(png);
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(head.contentLength()).isEqualTo(png.length);
        assertThat(head.cacheControl()).isEqualTo(S3AssetObjectStorage.IMMUTABLE_CACHE_CONTROL);
        assertThat(head.metadata()).containsEntry(S3AssetObjectStorage.SHA_256_METADATA_KEY, object.sha256().value());
    }

    @Test
    void rejectsWrongContentTypeHashAndSizeWithRedactedFailures() {
        AssetSha256 contentTypeSha = new AssetSha256("b".repeat(64));
        AssetObjectKey contentTypeKey = AssetObjectKey.canonical(AssetKind.HERO, contentTypeSha, "png");
        putRaw(contentTypeKey, "application/octet-stream", 8, contentTypeSha.value());
        assertIntegrityFailure(new AssetObjectDescriptor(
                AssetKind.HERO, contentTypeKey, "image/png", 8, contentTypeSha));

        AssetSha256 sizeSha = new AssetSha256("c".repeat(64));
        AssetObjectKey sizeKey = AssetObjectKey.canonical(AssetKind.HERO, sizeSha, "png");
        putRaw(sizeKey, "image/png", 8, sizeSha.value());
        assertIntegrityFailure(new AssetObjectDescriptor(
                AssetKind.HERO, sizeKey, "image/png", 7, sizeSha));

        AssetSha256 metadataSha = new AssetSha256("e".repeat(64));
        AssetObjectKey metadataKey = AssetObjectKey.canonical(AssetKind.HERO, metadataSha, "png");
        putRaw(metadataKey, "image/png", 8, "f".repeat(64));
        assertIntegrityFailure(new AssetObjectDescriptor(
                AssetKind.HERO, metadataKey, "image/png", 8, metadataSha));
    }

    @Test
    void rejectsForgedShaMetadataWhenStoredBytesDifferAtTheSameSize() {
        byte[] expectedBytes = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01};
        AssetSha256 expectedSha = AssetSha256.calculate(expectedBytes);
        AssetObjectKey key = AssetObjectKey.canonical(AssetKind.OPEN_GRAPH, expectedSha, "png");
        byte[] differentBytes = expectedBytes.clone();
        differentBytes[differentBytes.length - 1] = 0x02;
        putRaw(key, "image/png", differentBytes, expectedSha.value());

        assertIntegrityFailure(new AssetObjectDescriptor(
                AssetKind.OPEN_GRAPH,
                key,
                "image/png",
                expectedBytes.length,
                expectedSha));
    }

    @Test
    void twoConcurrentConditionalWritersConvergeOnOneVerifiedObject() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x03};
        AssetObject object = new AssetContentPolicy()
                .validate(new AssetUploadContent(AssetKind.CATEGORY, "image/png", "png", png))
                .toObject();
        AtomicInteger coordinatedHeads = new AtomicInteger();
        AtomicInteger conditionalConflicts = new AtomicInteger();
        CyclicBarrier bothObservedMissing = new CyclicBarrier(2);
        S3Client racingClient = racingClient(
                object.key().value(), coordinatedHeads, conditionalConflicts, bothObservedMissing);
        S3AssetObjectStorage racingStorage = new S3AssetObjectStorage(racingClient, BUCKET);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                racingStorage.putImmutable(object);
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                racingStorage.putImmutable(object);
                return null;
            });

            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(coordinatedHeads).hasValue(2);
        assertThat(conditionalConflicts).hasValue(1);
        storage.requirePresent(object.descriptor());
        assertThat(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(object.key().value())
                        .build())
                .asByteArray()).isEqualTo(png);
    }

    @Test
    void missingCanonicalObjectNeverFallsBackToAnotherKey() {
        AssetSha256 sha = new AssetSha256("d".repeat(64));
        AssetObjectKey canonical = AssetObjectKey.canonical(AssetKind.MENU_ITEM, sha, "webp");
        s3Client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("old/location/available.webp").build(),
                RequestBody.fromBytes(new byte[] {1}));

        assertThatThrownBy(() -> storage.requirePresent(
                        new AssetObjectDescriptor(AssetKind.MENU_ITEM, canonical, "image/webp", 12, sha)))
                .isInstanceOf(AssetStorageException.class)
                .hasMessage("Asset storage object is unavailable")
                .hasMessageNotContaining(BUCKET)
                .hasMessageNotContaining(canonical.value())
                .hasNoCause();
    }

    private void putRaw(AssetObjectKey key, String contentType, long size, String metadataSha) {
        putRaw(key, contentType, new byte[(int) size], metadataSha);
    }

    private void putRaw(AssetObjectKey key, String contentType, byte[] content, String metadataSha) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key.value())
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        .cacheControl(S3AssetObjectStorage.IMMUTABLE_CACHE_CONTROL)
                        .metadata(Map.of(S3AssetObjectStorage.SHA_256_METADATA_KEY, metadataSha))
                        .build(),
                RequestBody.fromBytes(content));
    }

    private S3Client racingClient(
            String objectKey,
            AtomicInteger coordinatedHeads,
            AtomicInteger conditionalConflicts,
            CyclicBarrier bothObservedMissing) {
        return (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(),
                new Class<?>[] {S3Client.class},
                (proxy, method, arguments) -> {
                    boolean matchingHead = method.getName().equals("headObject")
                            && arguments != null
                            && arguments.length == 1
                            && arguments[0] instanceof HeadObjectRequest request
                            && objectKey.equals(request.key());
                    boolean coordinate = matchingHead && claimOneOfTwoInitialHeads(coordinatedHeads);
                    try {
                        return method.invoke(s3Client, arguments);
                    } catch (InvocationTargetException exception) {
                        Throwable cause = exception.getCause();
                        if (method.getName().equals("putObject")
                                && cause instanceof S3Exception s3Exception
                                && (s3Exception.statusCode() == 409 || s3Exception.statusCode() == 412)) {
                            conditionalConflicts.incrementAndGet();
                        }
                        throw cause;
                    } finally {
                        if (coordinate) {
                            bothObservedMissing.await(10, TimeUnit.SECONDS);
                        }
                    }
                });
    }

    private static boolean claimOneOfTwoInitialHeads(AtomicInteger coordinatedHeads) {
        while (true) {
            int current = coordinatedHeads.get();
            if (current >= 2) {
                return false;
            }
            if (coordinatedHeads.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void assertIntegrityFailure(AssetObjectDescriptor descriptor) {
        assertThatThrownBy(() -> storage.requirePresent(descriptor))
                .isInstanceOf(AssetStorageException.class)
                .hasMessage("Asset storage integrity verification failed")
                .hasMessageNotContaining(BUCKET)
                .hasMessageNotContaining(descriptor.key().value())
                .hasNoCause();
    }
}
