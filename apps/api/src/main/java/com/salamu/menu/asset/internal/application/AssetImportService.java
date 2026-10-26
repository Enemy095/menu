package com.salamu.menu.asset.internal.application;

import java.util.Objects;

import com.salamu.menu.asset.api.AssetImportReceipt;
import com.salamu.menu.asset.api.AssetImportRequest;
import com.salamu.menu.asset.api.AssetImporter;

public final class AssetImportService implements AssetImporter {

    private final AssetContentPolicy contentPolicy;
    private final AssetObjectWriter objectWriter;

    public AssetImportService(AssetContentPolicy contentPolicy, AssetObjectWriter objectWriter) {
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "contentPolicy");
        this.objectWriter = Objects.requireNonNull(objectWriter, "objectWriter");
    }

    @Override
    public AssetImportReceipt validate(AssetImportRequest request) {
        Objects.requireNonNull(request, "request");
        return receipt(contentPolicy.validate(upload(request)).toObject().descriptor());
    }

    @Override
    public AssetImportReceipt validateAndStore(AssetImportRequest request) {
        Objects.requireNonNull(request, "request");
        return receipt(objectWriter.validateAndStore(upload(request)));
    }

    private static AssetUploadContent upload(AssetImportRequest request) {
        return new AssetUploadContent(
                request.kind(), request.contentType(), request.extension(), request.content());
    }

    private static AssetImportReceipt receipt(AssetObjectDescriptor descriptor) {
        return new AssetImportReceipt(
                descriptor.kind(),
                descriptor.key().value(),
                descriptor.contentType(),
                descriptor.sizeBytes(),
                descriptor.sha256().value());
    }
}
