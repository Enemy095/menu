package com.salamu.menu.dataimport.internal;

import java.sql.SQLException;
import java.util.Objects;

import tools.jackson.databind.ObjectMapper;

import com.salamu.menu.asset.api.AssetImporter;
import com.salamu.menu.dataimport.api.ApprovedDatasetImporter;
import com.salamu.menu.dataimport.api.DataImportConflictException;
import com.salamu.menu.dataimport.api.DataImportRequest;
import com.salamu.menu.dataimport.api.DataImportResult;
import com.salamu.menu.menu.api.PublishedMenuQuery;
import com.salamu.menu.site.api.SiteConfigurationQuery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "app.data-import.enabled", havingValue = "true")
final class StambulDataImportService implements ApprovedDatasetImporter {

    private final AssetImporter assetImporter;
    private final StambulBundleLoader loader;
    private final StambulContentGraph graph;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;
    private final SiteConfigurationQuery siteConfigurationQuery;
    private final PublishedMenuQuery publishedMenuQuery;

    StambulDataImportService(
            AssetImporter assetImporter,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            SiteConfigurationQuery siteConfigurationQuery,
            PublishedMenuQuery publishedMenuQuery) {
        this.assetImporter = Objects.requireNonNull(assetImporter, "assetImporter");
        this.loader = new StambulBundleLoader(objectMapper, assetImporter);
        this.graph = new StambulContentGraph(jdbcTemplate, objectMapper);
        this.readTransaction = transactionTemplate(transactionManager, true);
        this.writeTransaction = transactionTemplate(transactionManager, false);
        this.siteConfigurationQuery = Objects.requireNonNull(siteConfigurationQuery, "siteConfigurationQuery");
        this.publishedMenuQuery = Objects.requireNonNull(publishedMenuQuery, "publishedMenuQuery");
    }

    @Override
    public DataImportResult dryRun(DataImportRequest request) {
        PreparedStambulImport prepared = loader.load(request);
        StambulContentGraph.State state = readTransaction.execute(status -> graph.inspect(prepared));
        if (state == StambulContentGraph.State.CONFLICT) {
            throw conflict();
        }
        return result(state == StambulContentGraph.State.MATCH ? "UNCHANGED" : "READY", prepared,
                state == StambulContentGraph.State.MATCH);
    }

    @Override
    public DataImportResult apply(DataImportRequest request) {
        PreparedStambulImport prepared = loader.load(request);
        StambulContentGraph.State preflight = readTransaction.execute(status -> graph.inspect(prepared));
        if (preflight == StambulContentGraph.State.CONFLICT) {
            throw conflict();
        }

        var storedAsset = assetImporter.validateAndStore(prepared.assetRequest());
        if (!storedAsset.equals(prepared.assetReceipt())) {
            throw new IllegalStateException("Stored asset metadata differs from validated content");
        }
        StambulContentGraph.State result = publishWithSerializationRetry(prepared);
        return result(result == StambulContentGraph.State.EMPTY ? "CREATED" : "UNCHANGED", prepared, true);
    }

    private StambulContentGraph.State publishWithSerializationRetry(PreparedStambulImport prepared) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return writeTransaction.execute(status -> {
                    graph.lockPublication();
                    StambulContentGraph.State state = graph.inspect(prepared);
                    if (state == StambulContentGraph.State.CONFLICT) {
                        throw conflict();
                    }
                    if (state == StambulContentGraph.State.EMPTY) {
                        graph.insert(prepared);
                        return StambulContentGraph.State.EMPTY;
                    }
                    return StambulContentGraph.State.MATCH;
                });
            } catch (DataAccessException exception) {
                if (attempt == 3 || !isSerializationFailure(exception)) {
                    throw exception;
                }
            }
        }
        throw new IllegalStateException("Unreachable publication retry state");
    }

    private static boolean isSerializationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && "40001".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private DataImportResult result(
            String outcome,
            PreparedStambulImport prepared,
            boolean includeRepresentationHashes) {
        String configurationHash = null;
        String menuHash = null;
        if (includeRepresentationHashes) {
            configurationHash = siteConfigurationQuery.current().representationHash();
            menuHash = publishedMenuQuery.current().representationHash();
        }
        return new DataImportResult(
                outcome,
                StambulBundleLoader.SELECTION,
                prepared.bundleSha256(),
                prepared.effectiveSha256(),
                prepared.assetId(),
                prepared.restaurantId(),
                prepared.menuVersionId(),
                StambulBundleLoader.EXPECTED_CATEGORY_COUNT,
                StambulBundleLoader.EXPECTED_ITEM_COUNT,
                prepared.assetReceipt().sha256(),
                configurationHash,
                menuHash);
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager,
            boolean readOnly) {
        var template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    private static DataImportConflictException conflict() {
        return new DataImportConflictException(
                "Target content graph differs from the approved Stambul bundle");
    }
}
