package com.lifeos.application.port;

import com.lifeos.domain.workspace.ProvisionedResourceType;

import java.util.List;
import java.util.Optional;

public interface NotionProvisioningPort {

    VerificationResult verify(String databaseId, ProvisionedResourceType type, ExpectedShape expected);

    Optional<String> findChildByIdentity(String parentPageId, ProvisionedResourceType type, ExpectedShape expected);

    String createRootPage(PageShape expected);

    VerificationResult verifyPage(String pageId, PageShape expected);

    void repairPage(String pageId, PageShape expected);

    Optional<String> findRootByIdentity(PageShape expected);

    String createDatabase(String parentPageId, DatabaseSpec spec);

    void repairShape(String databaseId, ExpectedShape expected);

    void ensureRelation(RelationSpec spec);

    void ensureRollup(RollupSpec spec);

    void ensureFormula(FormulaSpec spec);

    boolean hasSampleRecords(String databaseId);

    void insertSampleRecords(String databaseId, List<RecordSpec> records);
}
