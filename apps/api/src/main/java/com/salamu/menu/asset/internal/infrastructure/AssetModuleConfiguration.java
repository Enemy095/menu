package com.salamu.menu.asset.internal.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.salamu.menu.asset.api.AssetCatalog;
import com.salamu.menu.asset.internal.application.AssetCatalogService;
import com.salamu.menu.asset.internal.application.AssetContentPolicy;
import com.salamu.menu.asset.internal.application.AssetMetadataRepository;
import com.salamu.menu.asset.internal.application.AssetObjectStorage;
import com.salamu.menu.asset.internal.application.AssetObjectWriter;
import com.salamu.menu.asset.internal.application.AssetPublicUrlResolver;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = true)
class AssetModuleConfiguration {

    static final String S3_CLIENT_BEAN_NAME = "assetModuleS3Client";

    @Bean
    AssetMetadataRepository assetMetadataRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAssetMetadataRepository(jdbcTemplate);
    }

    @Bean
    AssetPublicUrlResolver assetPublicUrlResolver(AssetProperties properties) {
        return new CdnAssetPublicUrlResolver(properties);
    }

    @Bean
    AssetCatalog assetCatalog(
            AssetMetadataRepository repository,
            AssetPublicUrlResolver publicUrlResolver) {
        return new AssetCatalogService(repository, publicUrlResolver);
    }

    @Bean
    AssetContentPolicy assetContentPolicy() {
        return new AssetContentPolicy();
    }

    @Bean(name = S3_CLIENT_BEAN_NAME, destroyMethod = "close", autowireCandidate = false)
    S3Client assetModuleS3Client(AssetProperties properties) {
        AssetProperties.Storage storage = properties.storage();
        return S3Client.builder()
                .endpointOverride(storage.endpoint())
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(storage.pathStyleAccess())
                        .build())
                .build();
    }

    @Bean
    AssetObjectStorage assetObjectStorage(AssetProperties properties) {
        return new S3AssetObjectStorage(
                assetModuleS3Client(properties),
                properties.storage().bucket());
    }

    @Bean
    AssetObjectWriter assetObjectWriter(
            AssetContentPolicy contentPolicy,
            AssetObjectStorage objectStorage) {
        return new AssetObjectWriter(contentPolicy, objectStorage);
    }
}
