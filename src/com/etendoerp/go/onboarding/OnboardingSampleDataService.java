/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.onboarding;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.service.db.DataImportService;
import org.openbravo.service.db.ImportResult;

/**
 * ETP-5426 — imports the optional sample data (GOClient's partners, products, documents, stock,
 * costing and fixed assets) into a tenant whose onboarding has already committed.
 *
 * <p><b>Why a separate step, after the commit.</b> The onboarding request commits once, at the
 * end of the provisioning chain, and {@code DataImportService} rolls the whole session back on any
 * error. Importing the sample data inside that transaction would let a sample-data failure take
 * the whole signup down. Run after the commit, a failure can only discard the sample data itself —
 * the tenant stays created and usable. It also makes the step independent of how the tenant was
 * built: a tenant claimed from the pool (ETP-5389) and a classic one both reach this point with
 * the base dataset already committed, so the pool itself needs no change.
 *
 * <p><b>Transaction contract.</b> This service neither commits nor rolls back: the caller commits
 * on success and rolls back on any exception, exactly as for the provisioning steps.
 *
 * <p>What is imported, and how it differs from GOClient, is described in
 * {@link OnboardingSampleDataDefinition}.
 */
public class OnboardingSampleDataService {

  private static final Logger log = LogManager.getLogger(OnboardingSampleDataService.class);

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_MARKER_IDS = "markerIds";
  private static final String PARAM_ORG_ID = "orgId";
  private static final String PARAM_PENDING = "pending";

  /** What {@link #importSampleData} did. */
  public enum Outcome {
    /** The sample data was imported in the current transaction. */
    IMPORTED,
    /** The tenant already had the sample data; nothing was written. */
    ALREADY_PRESENT
  }

  /**
   * The sample-data import records, like any import, the mapping from each GOClient id to the
   * tenant's new id. Any mapped GOClient business partner means the sample data is already there.
   */
  private static final String PRESENCE_SQL =
      "SELECT COUNT(*) FROM ad_ref_data_loaded"
      + " WHERE ad_client_id = :" + PARAM_CLIENT_ID
      + "   AND generic_id IN (:" + PARAM_MARKER_IDS + ")";

  /**
   * Moves each document-type sequence past the highest number the imported documents carry.
   *
   * <p>GOClient's sequences still sit at their start value (1000000) while its documents are
   * numbered up to 1000010, so without this the tenant's first real order, invoice, shipment or
   * payment would repeat a sample number. Only sequences that would go BACKWARDS are left alone.
   * Orders and invoices are numbered by their target document type.
   */
  private static final String ALIGN_DOCTYPE_SEQUENCES_SQL =
      "UPDATE ad_sequence s SET currentnext = x.nextno, updated = now()"
      + " FROM (SELECT dt.docnosequence_id AS seq_id,"
      + "              MAX(CAST(substring(d.documentno FROM '([0-9]+)$') AS numeric)) + 1 AS nextno"
      + "         FROM (SELECT COALESCE(c_doctypetarget_id, c_doctype_id) AS doctype_id, documentno"
      + "                 FROM c_order WHERE ad_client_id = :" + PARAM_CLIENT_ID
      + "               UNION ALL"
      + "               SELECT COALESCE(c_doctypetarget_id, c_doctype_id), documentno"
      + "                 FROM c_invoice WHERE ad_client_id = :" + PARAM_CLIENT_ID
      + "               UNION ALL"
      + "               SELECT c_doctype_id, documentno"
      + "                 FROM m_inout WHERE ad_client_id = :" + PARAM_CLIENT_ID
      + "               UNION ALL"
      + "               SELECT c_doctype_id, documentno"
      + "                 FROM fin_payment WHERE ad_client_id = :" + PARAM_CLIENT_ID + ") d"
      + "         JOIN c_doctype dt ON dt.c_doctype_id = d.doctype_id"
      + "        WHERE dt.docnosequence_id IS NOT NULL AND d.documentno ~ '[0-9]+$'"
      + "        GROUP BY dt.docnosequence_id) x"
      + " WHERE s.ad_sequence_id = x.seq_id AND s.currentnext < x.nextno";

  /**
   * Same as {@link #ALIGN_DOCTYPE_SEQUENCES_SQL} for the documents numbered by a per-table counter
   * ({@code DocumentNo_<table>}) instead of a document type: fixed assets and goods movements.
   * The physical inventory carries no number, so it has nothing to align.
   */
  private static final String ALIGN_TABLE_COUNTER_SEQUENCES_SQL =
      "UPDATE ad_sequence s SET currentnext = x.nextno, updated = now()"
      + " FROM (SELECT d.counter_name,"
      + "              MAX(CAST(substring(d.documentno FROM '([0-9]+)$') AS numeric)) + 1 AS nextno"
      + "         FROM (SELECT 'documentno_a_asset' AS counter_name, documentno"
      + "                 FROM a_asset WHERE ad_client_id = :" + PARAM_CLIENT_ID
      + "               UNION ALL"
      + "               SELECT 'documentno_m_movement', documentno"
      + "                 FROM m_movement WHERE ad_client_id = :" + PARAM_CLIENT_ID + ") d"
      + "        WHERE d.documentno ~ '[0-9]+$'"
      + "        GROUP BY d.counter_name) x"
      + " WHERE s.ad_client_id = :" + PARAM_CLIENT_ID
      + "   AND lower(s.name) = x.counter_name AND s.currentnext < x.nextno";

  /**
   * Gives every sample partner still carrying {@link OnboardingSampleDataDefinition#PENDING_IDENTIFIER}
   * the next numbers of the tenant's own {@code C_BPartner.EM_Etgo_Identifier} sequence (the one
   * onboarding creates for the business organization), in {@code VALUE} order, and advances that
   * sequence past them — the same numbers a user creating these partners in the app would get.
   *
   * <p>Done here, in one statement, because the column's transactional sequence generator runs per
   * insert inside the import's single flush and handed the same number to every partner. The
   * sequence's prefix and suffix are applied; its (optional) mask is not.
   */
  private static final String RENUMBER_PARTNER_IDENTIFIERS_SQL =
      "WITH seq AS ("
      + "  SELECT s.ad_sequence_id, s.currentnext, s.incrementno, s.prefix, s.suffix"
      + "    FROM ad_sequence s"
      + "    JOIN ad_column c ON c.ad_column_id = s.ad_column_id"
      + "    JOIN ad_table t ON t.ad_table_id = c.ad_table_id"
      + "   WHERE s.ad_client_id = :" + PARAM_CLIENT_ID + " AND s.ad_org_id = :" + PARAM_ORG_ID
      + "     AND s.isactive = 'Y'"
      + "     AND lower(t.tablename) = 'c_bpartner' AND lower(c.columnname) = 'em_etgo_identifier'"
      + "   ORDER BY s.ad_sequence_id LIMIT 1),"
      + " pending AS ("
      + "  SELECT b.c_bpartner_id, row_number() OVER (ORDER BY b.value, b.c_bpartner_id) - 1 AS pos"
      + "    FROM c_bpartner b"
      + "   WHERE b.ad_client_id = :" + PARAM_CLIENT_ID + " AND b.em_etgo_identifier = :" + PARAM_PENDING + "),"
      + " renumbered AS ("
      + "  UPDATE c_bpartner b"
      + "     SET em_etgo_identifier = COALESCE(seq.prefix, '')"
      + "         || CAST(seq.currentnext + pending.pos * seq.incrementno AS varchar)"
      + "         || COALESCE(seq.suffix, ''),"
      + "         updated = now()"
      + "    FROM pending, seq"
      + "   WHERE b.c_bpartner_id = pending.c_bpartner_id"
      + "  RETURNING b.c_bpartner_id)"
      + " UPDATE ad_sequence s"
      + "    SET currentnext = seq.currentnext + (SELECT COUNT(*) FROM renumbered) * seq.incrementno,"
      + "        updated = now()"
      + "   FROM seq"
      + "  WHERE s.ad_sequence_id = seq.ad_sequence_id AND EXISTS (SELECT 1 FROM renumbered)";

  private static final String PENDING_PARTNER_IDENTIFIERS_SQL =
      "SELECT COUNT(*) FROM c_bpartner"
      + " WHERE ad_client_id = :" + PARAM_CLIENT_ID + " AND em_etgo_identifier = :" + PARAM_PENDING;

  private final Supplier<OnboardingDatasetNormalizer> normalizerSupplier;
  private final OnboardingAccountingWiringService accountingWiringService;

  /**
   * Creates the service with the production sample-data normalizer and accounting wiring.
   */
  public OnboardingSampleDataService() {
    this(OnboardingDatasetNormalizer::forSampleData, new OnboardingAccountingWiringService());
  }

  OnboardingSampleDataService(Supplier<OnboardingDatasetNormalizer> normalizerSupplier,
      OnboardingAccountingWiringService accountingWiringService) {
    this.normalizerSupplier = Objects.requireNonNull(normalizerSupplier,
        "normalizerSupplier is required");
    this.accountingWiringService = Objects.requireNonNull(accountingWiringService,
        "accountingWiringService is required");
  }

  /**
   * Whether an onboarding request must receive the sample data: the user opted in, it provisions a
   * demo (not a paid) environment, and the tenant is Spanish and invoices in euros — the sample
   * data's taxes, tax ids and amounts are Spanish/EUR. A request that fails any condition is
   * provisioned without sample data, whatever the form sent.
   *
   * @param requested   the signup form's opt-in
   * @param paidUpgrade whether the request provisions a paid environment
   * @param countryCode the requested country (ISO 3166-1 alpha-2)
   * @param currencyIso the requested currency (ISO 4217)
   * @return {@code true} when the sample data must be imported
   */
  public static boolean isEligible(boolean requested, boolean paidUpgrade, String countryCode,
      String currencyIso) {
    return OnboardingSampleDataDefinition.isEligible(requested, paidUpgrade, countryCode,
        currencyIso);
  }

  /**
   * Imports the sample data into the tenant, then provisions its posting accounts and moves the
   * document sequences past the imported numbers. Idempotent: a tenant that already has it is left
   * untouched.
   *
   * @param clientId    the tenant
   * @param orgId       the tenant's business organization, owner of the imported rows
   * @param adminUserId the tenant admin, used as the DAL context
   * @param adminRoleId the tenant admin role, used as the DAL context
   * @return what was done
   * @throws OBException when the import fails; the caller must roll back
   */
  public Outcome importSampleData(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    validateArguments(clientId, orgId, adminUserId, adminRoleId);
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        return importInTenantContext(clientId, orgId);
      } finally {
        exitAdminMode();
      }
    } finally {
      // Never leave the DAL context switched to the tenant (see onboarding's own context leak).
      restoreExecutionContext(previousContext);
    }
  }

  private Outcome importInTenantContext(String clientId, String orgId) {
    Client client = resolveClient(clientId);
    if (client == null) {
      throw new OBException("Client not found with ID: " + clientId);
    }
    Organization organization = resolveOrganization(orgId);
    if (organization == null) {
      throw new OBException("Organization not found with ID: " + orgId);
    }
    if (isAlreadyImported(clientId)) {
      log.info("Sample data already present for client {}; skipping (idempotent resume)",
          clientId);
      return Outcome.ALREADY_PRESENT;
    }

    String xml = normalizerSupplier.get().buildDatasetXml(orgId);
    ImportResult result = importXml(client, organization, xml);
    if (result == null) {
      throw new OBException("Sample data import returned no result");
    }
    if (result.hasErrorOccured()) {
      String message = StringUtils.defaultIfBlank(result.getErrorMessages(),
          "Sample data import failed");
      // OBException(String, Throwable) unwraps the cause and throws a NullPointerException on a
      // null one — which an import that only reports error messages returns — hiding the message.
      Throwable cause = result.getException();
      throw cause == null ? new OBException(message) : new OBException(message, cause);
    }
    flush();

    AcctSchema ledger = accountingWiringService.resolveImportedLedger(client);
    if (ledger == null) {
      throw new OBException("No accounting schema found for client " + clientId
          + "; cannot provision the sample data posting accounts");
    }
    accountingWiringService.provisionSampleDataPostingAccounts(client, ledger);
    renumberPartnerIdentifiers(clientId, orgId);
    alignDocumentSequences(clientId);
    flush();
    log.info("Sample data imported for client {}/org {}: {} rows", clientId, orgId,
        result.getInsertedObjects().size());
    return Outcome.IMPORTED;
  }

  private static void validateArguments(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    if (StringUtils.isAnyBlank(clientId, orgId, adminUserId, adminRoleId)) {
      throw new IllegalArgumentException(
          "clientId, orgId, adminUserId and adminRoleId are required to import the sample data");
    }
  }

  protected OBContext captureCurrentContext() {
    return OBContext.getOBContext();
  }

  protected void applyExecutionContext(String adminUserId, String adminRoleId, String clientId,
      String orgId) {
    OBContext.setOBContext(adminUserId, adminRoleId, clientId, orgId);
  }

  protected void restoreExecutionContext(OBContext previousContext) {
    OBContext.setOBContext(previousContext);
  }

  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void exitAdminMode() {
    OBContext.restorePreviousMode();
  }

  protected Client resolveClient(String clientId) {
    return OBDal.getInstance().get(Client.class, clientId);
  }

  protected Organization resolveOrganization(String orgId) {
    return OBDal.getInstance().get(Organization.class, orgId);
  }

  protected boolean isAlreadyImported(String clientId) {
    Object count = OBDal.getInstance().getSession()
        .createNativeQuery(PRESENCE_SQL)
        .setParameter(PARAM_CLIENT_ID, clientId)
        .setParameterList(PARAM_MARKER_IDS,
            new ArrayList<>(OnboardingSampleDataDefinition.PRESENCE_MARKER_BUSINESS_PARTNER_IDS))
        .uniqueResult();
    return count instanceof Number && ((Number) count).longValue() > 0;
  }

  protected ImportResult importXml(Client client, Organization organization, String xml) {
    return DataImportService.getInstance().importDataFromXML(client, organization, xml, null);
  }

  /**
   * Replaces the stand-in identifiers with real sequence numbers. Fails — and so rolls the sample
   * data back — if any partner is left with the stand-in, e.g. because the tenant has no identifier
   * sequence for its business organization: a visible placeholder is worse than no sample data.
   */
  protected void renumberPartnerIdentifiers(String clientId, String orgId) {
    OBDal.getInstance().getSession()
        .createNativeQuery(RENUMBER_PARTNER_IDENTIFIERS_SQL)
        .setParameter(PARAM_CLIENT_ID, clientId)
        .setParameter(PARAM_ORG_ID, orgId)
        .setParameter(PARAM_PENDING, OnboardingSampleDataDefinition.PENDING_IDENTIFIER)
        .executeUpdate();
    Object pending = OBDal.getInstance().getSession()
        .createNativeQuery(PENDING_PARTNER_IDENTIFIERS_SQL)
        .setParameter(PARAM_CLIENT_ID, clientId)
        .setParameter(PARAM_PENDING, OnboardingSampleDataDefinition.PENDING_IDENTIFIER)
        .uniqueResult();
    if (pending instanceof Number && ((Number) pending).longValue() > 0) {
      throw new OBException(pending + " sample business partner(s) could not be numbered: no active"
          + " EM_Etgo_Identifier sequence for client " + clientId + "/org " + orgId);
    }
  }

  protected void alignDocumentSequences(String clientId) {
    int doctypeSequences = OBDal.getInstance().getSession()
        .createNativeQuery(ALIGN_DOCTYPE_SEQUENCES_SQL)
        .setParameter(PARAM_CLIENT_ID, clientId)
        .executeUpdate();
    int tableCounters = OBDal.getInstance().getSession()
        .createNativeQuery(ALIGN_TABLE_COUNTER_SEQUENCES_SQL)
        .setParameter(PARAM_CLIENT_ID, clientId)
        .executeUpdate();
    log.debug("Aligned {} document-type and {} table-counter sequence(s) for client {}",
        doctypeSequences, tableCounters, clientId);
  }

  protected void flush() {
    OBDal.getInstance().flush();
  }
}
