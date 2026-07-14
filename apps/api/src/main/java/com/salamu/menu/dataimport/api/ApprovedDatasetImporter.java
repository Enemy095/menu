package com.salamu.menu.dataimport.api;

public interface ApprovedDatasetImporter {

    DataImportResult dryRun(DataImportRequest request);

    DataImportResult apply(DataImportRequest request);
}
