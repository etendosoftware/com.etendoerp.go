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
package com.etendoerp.go.rest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingAccountingWiringService;
import com.etendoerp.go.onboarding.OnboardingAcctdimCentrallyMaintainedService;
import com.etendoerp.go.onboarding.OnboardingAdminIdentityService;
import com.etendoerp.go.onboarding.OnboardingBaselineService;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingDatasetImportService;
import com.etendoerp.go.onboarding.OnboardingFiscalDataSetupService;
import com.etendoerp.go.onboarding.OnboardingForceTestModeService;
import com.etendoerp.go.onboarding.OnboardingMarkOrgReadyService;
import com.etendoerp.go.onboarding.OnboardingOrgInfoService;
import com.etendoerp.go.onboarding.OnboardingPeriodControlService;
import com.etendoerp.go.onboarding.OnboardingProgressSink;
import com.etendoerp.go.onboarding.OnboardingSequenceGeneratorService;
import com.etendoerp.go.onboarding.OnboardingWarehouseAddressService;
import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * The tenant-provisioning chain, independent of HTTP (ETP-5389).
 *
 * <p>Everything here used to live in {@code EtendoGoJwtServlet} and wrote straight into the
 * onboarding response's {@code PrintWriter}. It was moved, not rewritten: every step keeps its
 * order, its progress keys and messages, its rollback-or-not behaviour and its error handling, with
 * the writer replaced by an {@link OnboardingProgressSink}. That is what lets the onboarding
 * endpoint and the tenant pool filler run the <em>same</em> chain — a pooled tenant is built by
 * exactly the code that builds a classic one.
 *
 * <p>The service fields are package-visible so the servlet can hand over its own instances (tests
 * swap them on the servlet); the no-argument constructor gives the production defaults, which is
 * what the pool filler uses.
 */
public class OnboardingProvisioningChain {

  private static final Logger log = LogManager.getLogger(OnboardingProvisioningChain.class);

  /**
   * Revision of this chain, stamped on every pooled tenant (see {@code TenantPoolConfig}). A
   * pooled tenant built by a different revision is retired instead of claimed, so a deploy that
   * changes onboarding never hands out a tenant built by the previous code. <b>Bump it whenever a
   * step is added, removed, reordered or changes what it provisions.</b>
   */
  public static final String CHAIN_REVISION = "2026-09-24.1";

  /**
   * The provisioning version a pooled tenant is stamped with and claimed by: this chain's revision
   * plus the data-fix baseline cutoff. Either moving retires every pooled tenant built before it.
   *
   * @return e.g. {@code 2026-09-24.1@2026-09-02T12:00:00Z}
   */
  public static String provisioningVersion() {
    return CHAIN_REVISION + "@" + OnboardingBaselineService.provisionedThrough();
  }

  static final String PROGRESS_IN_PROGRESS = "in_progress";
  static final String PROGRESS_DONE = "done";
  static final String PROGRESS_ERROR = "error";
  static final String PROGRESS_CLIENT = "client";
  static final String PROGRESS_ORGANIZATION = "organization";
  static final String PROGRESS_DATASET = "dataset";
  private static final String PROGRESS_ACCOUNTING = "accounting";
  private static final String PROGRESS_PERIOD_CONTROL = "periodControl";
  private static final String PROGRESS_SEQUENCES = "sequences";
  private static final String PROGRESS_FISCAL = "fiscal";
  private static final String PROGRESS_ORG_READY = "orgReady";
  static final String PROGRESS_ORG_INFO = "orgInfo";
  static final String PROGRESS_WAREHOUSE_ADDRESS = "warehouseAddress";
  private static final String PROGRESS_BASELINE = "baseline";
  private static final String PROGRESS_COSTING_SCHEDULE = "costingSchedule";
  private static final String PROGRESS_BP_GROUP_ACCT_PATCH = "bpGroupAcctPatch";
  private static final String PROGRESS_ACCTDIM_VISIBILITY = "acctdimVisibility";
  private static final String PROGRESS_ADMIN_IDENTITY = "adminIdentity";
  private static final String PROGRESS_FORCE_TEST_MODE = "forceTestMode";
  private static final String LEGAL_WITH_ACCOUNTING_ORG_TYPE_ID = "1";
  static final String ERROR_CODE_CLIENT_CREATION_FAILED = "CLIENT_CREATION_FAILED";
  static final String ERROR_CODE_ORG_CREATION_FAILED = "ORG_CREATION_FAILED";

  OnboardingDatasetImportService onboardingDatasetImportService =
      new OnboardingDatasetImportService();
  OnboardingAccountingWiringService onboardingAccountingWiringService =
      new OnboardingAccountingWiringService();
  OnboardingPeriodControlService onboardingPeriodControlService =
      new OnboardingPeriodControlService();
  OnboardingSequenceGeneratorService onboardingSequenceGeneratorService =
      new OnboardingSequenceGeneratorService();
  OnboardingMarkOrgReadyService onboardingMarkOrgReadyService =
      new OnboardingMarkOrgReadyService();
  OnboardingFiscalDataSetupService onboardingFiscalDataSetupService =
      new OnboardingFiscalDataSetupService();
  OnboardingOrgInfoService onboardingOrgInfoService = new OnboardingOrgInfoService();
  OnboardingWarehouseAddressService onboardingWarehouseAddressService =
      new OnboardingWarehouseAddressService();
  OnboardingAcctdimCentrallyMaintainedService onboardingAcctdimCentrallyMaintainedService =
      new OnboardingAcctdimCentrallyMaintainedService();
  OnboardingAdminIdentityService onboardingAdminIdentityService =
      new OnboardingAdminIdentityService();
  OnboardingBaselineService onboardingBaselineService = new OnboardingBaselineService();
  OnboardingForceTestModeService onboardingForceTestModeService =
      new OnboardingForceTestModeService();
  OnboardingCostingScheduleService onboardingCostingScheduleService =
      new OnboardingCostingScheduleService();

  /** The signup form's fiscal/address data the {@code orgInfo} step persists. */
  public record OrgInfoInput(String countryCode, String address, String taxId) {
  }

  /** The admin identity the chain runs under once the client exists. */
  public static final class AdminContext {
    String adminUserId;
    String adminRoleId;
    String starOrgId;

    public String getAdminUserId() {
      return adminUserId;
    }

    public String getAdminRoleId() {
      return adminRoleId;
    }

    public String getStarOrgId() {
      return starOrgId;
    }
  }

  /**
   * Creates the client through {@link InitialClientSetup} and returns the exact
   * {@code AD_Client_ID} it recorded in the session. Never resolves the client by name afterwards:
   * a second name lookup could return a different client than the one just provisioned.
   *
   * <p>Note {@link InitialClientSetup} commits on its own: from here on the client survives a
   * rollback of the rest of the chain, which is what the name-based resume path relies on.
   *
   * @return the created client id, or {@code null} once an error has been reported to the sink
   */
  public String createClient(OnboardingProgressSink sink, VariablesSecureApp vars,
      String currencyId, String clientName, String clientUser, String adminPassword) {
    InitialClientSetup clientSetup = new InitialClientSetup();
    OBError clientResult = clientSetup.createClient(vars, currencyId, clientName, clientUser,
        adminPassword, "", "Account", "Calendar", false, null, false, false, false,
        false, false);
    if (!"Success".equals(clientResult.getType())) {
      // InitialClientSetup reports failures as UNRESOLVED AD message keys ("@CreateClientFailed@")
      // whose text says nothing about the actual cause — the real exception only reaches the
      // server log. Keep the raw value here for diagnostics and hand the client a stable code it
      // can localize (ETP-4665).
      String errorMsg = clientResult.getMessage() != null
          ? clientResult.getMessage()
          : "Client creation failed";
      log.error("Client creation failed for '{}': {}", clientName, errorMsg);
      sink.progress(PROGRESS_CLIENT, PROGRESS_ERROR, errorMsg);
      sink.result(false, errorMsg, ERROR_CODE_CLIENT_CREATION_FAILED);
      return null;
    }
    String createdClientId = StringUtils.trimToNull(vars.getSessionValue("AD_Client_ID"));
    if (createdClientId == null) {
      String errorMessage = "Client creation succeeded but did not return the created client ID";
      log.error("Client creation for '{}' returned success without AD_Client_ID in session",
          clientName);
      sink.progress(PROGRESS_CLIENT, PROGRESS_ERROR, errorMessage);
      sink.result(false, errorMessage, ERROR_CODE_CLIENT_CREATION_FAILED);
      return null;
    }
    sink.progress(PROGRESS_CLIENT, PROGRESS_DONE, "Client created successfully");
    return createdClientId;
  }

  /**
   * Resolves the client's admin user/role and "*" organization, switches the {@link OBContext} to
   * that admin, and marks the admin as the tenant owner (best-effort, idempotent — ETP-4830).
   *
   * @return the admin context, or {@code null} once an error has been reported to the sink
   */
  public AdminContext resolveAdminContext(OnboardingProgressSink sink, String clientId) {
    AdminContext data = new AdminContext();
    var adminUserRole = EtendoGoJwtDalHelper.findClientAdminUserRole(clientId);
    if (adminUserRole != null) {
      data.adminRoleId = adminUserRole.getRole().getId();
      data.adminUserId = adminUserRole.getUserContact().getId();
    }
    if (data.adminRoleId == null || data.adminUserId == null) {
      sink.progress(PROGRESS_ORGANIZATION, PROGRESS_ERROR,
          "Could not find admin role for new client");
      sink.result(false, "Admin role not found — client may be incomplete", null);
      return null;
    }
    data.starOrgId = EtendoGoJwtSupport.findStarOrgId(clientId);
    OBContext.setOBContext(data.adminUserId, data.adminRoleId, clientId, data.starOrgId);
    markTenantOwnerBestEffort(clientId, data.adminUserId);
    return data;
  }

  /**
   * ETP-4830 — flags {@code adminUserId} as {@code clientId}'s owner (see {@link
   * OwnerSupport#markAsOwnerIfNoneExists}), the very first time this resolves for a brand-new
   * client: at this exact point in the provisioning chain (right after {@link #createClient}
   * created the client's real, single {@code AD_User} and BEFORE {@link
   * #importOnboardingDataset} brings in the GOClient sample dataset's own {@code AD_User} rows —
   * see {@code referencedata/sampledata/GOClient/AD_USER.xml}), {@code adminUserId} is
   * unambiguously the one true founder, never a bundled sample/demo user. {@link
   * OwnerSupport#markAsOwnerIfNoneExists} is itself idempotent (no-op once an owner already
   * exists for the client), so calling this on every resumed/retried onboarding pass — this
   * method runs on both the create AND the resume path — is safe and never re-assigns or moves
   * ownership.
   *
   * <p>Best-effort by design (ETP-4830 scope decision): a failure here must never fail the
   * onboarding chain — every owner-protection check downstream ({@code
   * UserRoleAssignmentHandler}/{@code UserRoleCompositionService}) already treats a
   * false/unset {@code is_owner} as "guard never triggers", so a tenant that failed to get an
   * owner marked here simply ships with no owner-lock yet, exactly like every pre-existing
   * tenant from before this column existed.</p>
   */
  void markTenantOwnerBestEffort(String clientId, String adminUserId) {
    try {
      OwnerSupport.markAsOwnerIfNoneExists(clientId, adminUserId);
    } catch (RuntimeException e) {
      log.warn("markTenantOwnerBestEffort: failed to flag owner for client {} user {}: {}",
          clientId, adminUserId, e.getMessage(), e);
    }
  }

  /**
   * Creates the client's legal organization through {@link InitialOrgSetup} and sets its
   * {@code SocialName}.
   *
   * @return {@code true} on success; {@code false} once an error has been reported to the sink
   */
  public boolean createOrganization(OnboardingProgressSink sink, String clientName,
      String clientId, String starOrgId, String currencyId) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    if (client == null) {
      sink.progress(PROGRESS_ORGANIZATION, PROGRESS_ERROR, "Could not load client entity");
      sink.result(false, "Client entity not found in DAL", null);
      return false;
    }
    InitialOrgSetup orgSetup = new InitialOrgSetup(client);
    // Onboarding imports accounting-ready sample data after the organization exists.
    // For fresh clients there is no ready package organization yet, so forcing accounting
    // during InitialOrgSetup would fail before dataset import can run.
    OBError orgResult = orgSetup.createOrganization(clientName, "",
        LEGAL_WITH_ACCOUNTING_ORG_TYPE_ID, starOrgId, null, "", "", false, null, currencyId,
        false, false, false, false, false);
    if (!"Success".equals(orgResult.getType())) {
      // Same as createClient: InitialOrgSetup yields raw AD keys such as "@CreateOrgFailed@".
      String errorMsg = orgResult.getMessage() != null
          ? orgResult.getMessage()
          : "Organization creation failed";
      log.error("Organization creation failed for '{}': {}", clientName, errorMsg);
      sink.progress(PROGRESS_ORGANIZATION, PROGRESS_ERROR, errorMsg);
      sink.result(false, errorMsg, ERROR_CODE_ORG_CREATION_FAILED);
      return false;
    }
    // ETP-4749: AD_Org.SocialName ("Nombre comercial" in the Organization settings window)
    // was never set anywhere in the onboarding flow — InitialOrgSetup/InitialSetupUtility
    // (Etendo core) only set Name/SearchKey. The wizard has no separate "trade name" field,
    // so reuse the same clientName already used for Name — it already resolves to the
    // user's Full Name for Freelancers (CompanyStep.jsx has no Company Name field for
    // that business type). A missing SocialName write here must not fail an otherwise
    // successful organization creation; log and move on.
    applySocialName(clientId, clientName);
    sink.progress(PROGRESS_ORGANIZATION, PROGRESS_DONE, "Organization created successfully");
    return true;
  }

  /**
   * Sets {@code AD_Org.SocialName} from the onboarding {@code clientName}, once, right after
   * organization creation succeeds. Deliberately NOT part of {@link OnboardingOrgInfoService}'s
   * idempotent reconcile chain (which re-runs on every resumed/retried onboarding call): a
   * resumed tenant may already have had its "Nombre comercial" edited by hand in the
   * Organization settings window, and re-running this on every retry would silently overwrite
   * that edit. Organization creation itself only happens once (guarded by
   * {@code organizationExists()} in the servlet's {@code ensureOrganization}), so this call site
   * shares the same one-time guarantee.
   *
   * @return {@code true} when the organization was found and updated; {@code false} otherwise
   *     (logged, non-fatal — the organization itself was already created successfully).
   */
  boolean applySocialName(String clientId, String clientName) {
    Organization org = EtendoGoJwtDalHelper.findFirstOrganization(clientId);
    if (org == null) {
      log.warn("applySocialName: no organization found for client {} right after creation",
          clientId);
      return false;
    }
    org.setSocialName(clientName);
    OBDal.getInstance().save(org);
    OBDal.getInstance().flush();
    return true;
  }

  /** @return the client's first non-"*" organization id, or {@code null} when it has none */
  public String resolveOrganizationId(String clientId) {
    Organization organization = EtendoGoJwtDalHelper.findFirstOrganization(clientId);
    return organization != null ? organization.getId() : null;
  }

  /**
   * Runs the tenant-provisioning chain under a reconcile model (ETP-4428): every step is
   * idempotent or self-guarding, so the full chain runs unconditionally. On a retry after a
   * partial failure this repairs whatever is missing and no-ops what already exists. Previously
   * the dataset/accounting/period-control steps were gated on whether the organization had just
   * been created, which left a resumed tenant (client+org survive the rollback, dataset does not)
   * without seed data, ledger or fiscal periods.
   */
  public boolean ensureOnboardingDataset(OnboardingProgressSink sink, String clientId,
      String orgId, String adminUserId, String adminRoleId, OrgInfoInput orgInfo) {
    if (!importOnboardingDataset(sink, clientId, orgId)) {
      return false;
    }
    if (!wireAccounting(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!wirePeriodControl(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!generateOnboardingSequences(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!markOrgReady(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!setupFiscalData(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!wireOrgInfo(sink, clientId, orgId, adminUserId, adminRoleId, orgInfo)) {
      return false;
    }
    // Depends on AD_ORGINFO already being located by wireOrgInfo above.
    if (!wireWarehouseAddress(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!scheduleCostingBackground(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    // ETP-4720: patch the 5 C_BP_Group_Acct columns neither the core c_bp_group_trg() trigger nor
    // OnboardingAccountingWiringService's own BP_GROUP_ACCT_SQL populate. Runs LAST among the
    // provisioning steps (right before the data-fix baseline) since it only needs C_BP_Group and
    // C_AcctSchema_Default, both already provisioned by step 1 -- see
    // OnboardingAccountingWiringService#patchBpGroupAcctMissingColumns for the full root-cause
    // explanation and its lockstep corrective twin (R21-bp-group-acct-remaining-columns.sql).
    if (!patchBpGroupAcctMissingColumns(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    // ETP-4854 (gap K1): force flat, per-dimension accounting-dimension visibility for the new
    // tenant. Runs AFTER the accounting-wiring steps (which created this client's
    // C_AcctSchema_Element rows, all defaulting isactive='Y') and BEFORE the baseline stamp — see
    // OnboardingAcctdimCentrallyMaintainedService for the full root-cause explanation and its
    // lockstep corrective twin (R23-acctdim-centrally-maintained.sql).
    if (!forceFlatAccountingDimensionVisibility(sink, clientId)) {
      return false;
    }
    // ETP-4999 (gap M1): wire the onboarding admin's own session defaults to the REAL business
    // org, not the root/wildcard '0' InitialClientSetup left them at. Runs AFTER the org and its
    // warehouse both exist (step 1) and BEFORE the baseline stamp — see
    // OnboardingAdminIdentityService for the full root-cause explanation (including why this does
    // NOT touch AD_User_Roles) and its lockstep corrective twin (R26-admin-identity-real-org.sql).
    if (!wireAdminIdentity(sink, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    // ETP-5117 (gap N1): force SII/TicketBAI/VeriFactu into test/sandbox mode for Demo/free
    // tenants, so no manual step in Classic is needed to trial the fiscal submission modules.
    // Runs AFTER the org exists (needed as the new preference row's visibility scope) and BEFORE
    // the baseline stamp — see OnboardingForceTestModeService for the full explanation (including
    // why it must never touch the System-level default preference row) and its lockstep
    // corrective twin (R31-force-test-mode-demo-tenants.sql).
    if (!forceTestModeForFreeTenant(sink, clientId, orgId)) {
      return false;
    }
    // Final action before commitDalChanges: stamp the tenant's data-fix baseline so it lands in the
    // same atomic onboarding commit. A genuine SQL error propagates (not caught here) so the outer
    // handleOnboarding catch rolls back cleanly; the expected ON CONFLICT->0-rows case is benign.
    //
    // The baseline applied_utc is a hardcoded CUT (ONBOARDING_PROVISIONED_THROUGH in
    // OnboardingBaselineService), NOT now(). It represents the last corrective data-fix that
    // this version of onboarding already provisions natively, so the runner skips all fixes
    // at-or-before that cutoff for freshly-onboarded tenants.
    //
    // WHEN ADDING A NEW ONBOARDING SERVICE (gap fix): bump ONBOARDING_PROVISIONED_THROUGH to the
    // timestamp of the corresponding .sql fix in cli/src/data-fixes/sql/. See the gap-closing
    // workflow in docs/etendo-ad/onboarding-and-datafixes-map.md §0. Bump CHAIN_REVISION too.
    return registerBaseline(sink, clientId);
  }

  boolean importOnboardingDataset(OnboardingProgressSink sink, String clientId, String orgId) {
    sink.progress(PROGRESS_DATASET, PROGRESS_IN_PROGRESS, "Importing onboarding dataset...");
    try {
      onboardingDatasetImportService.importDataset(clientId, orgId);
      sink.progress(PROGRESS_DATASET, PROGRESS_DONE, "Onboarding dataset imported");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding dataset import", e, log);
      return fail(sink, PROGRESS_DATASET, e, "Onboarding dataset import failed");
    }
  }

  boolean wireAccounting(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_ACCOUNTING, PROGRESS_IN_PROGRESS,
        "Wiring organization general ledger...");
    try {
      onboardingAccountingWiringService.wire(clientId, orgId, adminUserId, adminRoleId);
      sink.progress(PROGRESS_ACCOUNTING, PROGRESS_DONE, "Organization general ledger wired");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding accounting wiring", e, log);
      return fail(sink, PROGRESS_ACCOUNTING, e, "Organization accounting wiring failed");
    }
  }

  boolean wirePeriodControl(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_PERIOD_CONTROL, PROGRESS_IN_PROGRESS,
        "Enabling fiscal period control...");
    try {
      onboardingPeriodControlService.wire(clientId, orgId, adminUserId, adminRoleId);
      sink.progress(PROGRESS_PERIOD_CONTROL, PROGRESS_DONE, "Fiscal period control enabled");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding period-control wiring", e, log);
      return fail(sink, PROGRESS_PERIOD_CONTROL, e, "Organization period-control wiring failed");
    }
  }

  boolean generateOnboardingSequences(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_SEQUENCES, PROGRESS_IN_PROGRESS,
        "Generating organization sequences...");
    try {
      int count = onboardingSequenceGeneratorService.generateSequences(clientId, orgId, adminUserId,
          adminRoleId);
      sink.progress(PROGRESS_SEQUENCES, PROGRESS_DONE,
          "Organization sequences generated: " + count);
      return true;
    } catch (Exception e) {
      return fail(sink, PROGRESS_SEQUENCES, e, "Organization sequence generation failed");
    }
  }

  boolean markOrgReady(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_ORG_READY, PROGRESS_IN_PROGRESS, "Marking organization as ready...");
    try {
      onboardingMarkOrgReadyService.markOrgReady(clientId, orgId, adminUserId, adminRoleId);
      sink.progress(PROGRESS_ORG_READY, PROGRESS_DONE, "Organization is ready");
      return true;
    } catch (Exception e) {
      log.error("Error marking organization as ready", e);
      return fail(sink, PROGRESS_ORG_READY, e, "Mark org ready failed");
    }
  }

  boolean setupFiscalData(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_FISCAL, PROGRESS_IN_PROGRESS, "Setting up fiscal data...");
    try {
      onboardingFiscalDataSetupService.setup(clientId, orgId, adminUserId, adminRoleId);
      sink.progress(PROGRESS_FISCAL, PROGRESS_DONE, "Fiscal data ready");
      return true;
    } catch (Exception e) {
      log.error("Error during fiscal data setup", e);
      return fail(sink, PROGRESS_FISCAL, e, "Fiscal data setup failed");
    }
  }

  boolean wireOrgInfo(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId, OrgInfoInput orgInfo) {
    sink.progress(PROGRESS_ORG_INFO, PROGRESS_IN_PROGRESS, "Setting up organization address...");
    try {
      String countryCode = orgInfo != null ? orgInfo.countryCode() : null;
      String address = orgInfo != null ? orgInfo.address() : null;
      String taxId = orgInfo != null ? orgInfo.taxId() : null;
      onboardingOrgInfoService.ensureOrgInfo(clientId, orgId, adminUserId, adminRoleId,
          countryCode, address, taxId);
      sink.progress(PROGRESS_ORG_INFO, PROGRESS_DONE, "Organization address ready");
      return true;
    } catch (Exception e) {
      log.error("Error during organization info setup", e);
      return fail(sink, PROGRESS_ORG_INFO, e, "Organization info setup failed");
    }
  }

  boolean wireWarehouseAddress(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_WAREHOUSE_ADDRESS, PROGRESS_IN_PROGRESS,
        "Aligning warehouse address...");
    try {
      onboardingWarehouseAddressService.alignDefaultWarehouseAddress(clientId, orgId, adminUserId,
          adminRoleId);
      sink.progress(PROGRESS_WAREHOUSE_ADDRESS, PROGRESS_DONE, "Warehouse address aligned");
      return true;
    } catch (Exception e) {
      log.error("Error during warehouse-address alignment", e);
      return fail(sink, PROGRESS_WAREHOUSE_ADDRESS, e, "Warehouse address alignment failed");
    }
  }

  /**
   * Patches any {@code C_BP_Group_Acct} row still missing one of the 5 columns that neither the
   * core {@code c_bp_group_trg()} trigger nor {@code OnboardingAccountingWiringService}'s own
   * {@code BP_GROUP_ACCT_SQL} populate (ETP-4720) — see
   * {@code OnboardingAccountingWiringService#patchBpGroupAcctMissingColumns} for the full
   * explanation and its corrective twin ({@code R21-bp-group-acct-remaining-columns.sql}).
   */
  boolean patchBpGroupAcctMissingColumns(OnboardingProgressSink sink, String clientId,
      String orgId, String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_BP_GROUP_ACCT_PATCH, PROGRESS_IN_PROGRESS,
        "Patching business-partner group posting accounts...");
    try {
      onboardingAccountingWiringService.patchBpGroupAcctMissingColumns(clientId, orgId,
          adminUserId, adminRoleId);
      sink.progress(PROGRESS_BP_GROUP_ACCT_PATCH, PROGRESS_DONE,
          "Business-partner group posting accounts patched");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding bp-group-acct patch", e, log);
      return fail(sink, PROGRESS_BP_GROUP_ACCT_PATCH, e,
          "Business-partner group posting-account patch failed");
    }
  }

  /**
   * Forces flat, per-dimension accounting-dimension visibility ({@code Acctdim_Centrally_Maintained
   * = 'N'}) for the new tenant, backfilling {@code C_AcctSchema_Element.isactive} first so the
   * flip does not change what the tenant would otherwise see (ETP-4854, gap K1) — see
   * {@link OnboardingAcctdimCentrallyMaintainedService} for the full explanation.
   */
  boolean forceFlatAccountingDimensionVisibility(OnboardingProgressSink sink, String clientId) {
    sink.progress(PROGRESS_ACCTDIM_VISIBILITY, PROGRESS_IN_PROGRESS,
        "Configuring accounting-dimension visibility...");
    try {
      onboardingAcctdimCentrallyMaintainedService.forceFlatAccountingDimensionVisibility(clientId);
      sink.progress(PROGRESS_ACCTDIM_VISIBILITY, PROGRESS_DONE,
          "Accounting-dimension visibility configured");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding acctdim-visibility", e, log);
      return fail(sink, PROGRESS_ACCTDIM_VISIBILITY, e,
          "Accounting-dimension visibility configuration failed");
    }
  }

  /**
   * Wires the onboarding admin's session defaults to the real business organization (ETP-4999,
   * gap M1) — see {@link OnboardingAdminIdentityService} for the full explanation and its
   * corrective twin ({@code R26-admin-identity-real-org.sql}).
   */
  boolean wireAdminIdentity(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_ADMIN_IDENTITY, PROGRESS_IN_PROGRESS,
        "Wiring admin identity to organization...");
    try {
      onboardingAdminIdentityService.wireAdminIdentity(clientId, orgId, adminUserId, adminRoleId);
      sink.progress(PROGRESS_ADMIN_IDENTITY, PROGRESS_DONE, "Admin identity wired");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding admin-identity wiring", e, log);
      return fail(sink, PROGRESS_ADMIN_IDENTITY, e, "Admin identity wiring failed");
    }
  }

  /**
   * Forces SII/TicketBAI/VeriFactu submissions into test/sandbox mode for a Demo/free tenant
   * (ETP-5117, gap N1) — see {@link OnboardingForceTestModeService} for the full explanation and
   * its corrective twin ({@code R31-force-test-mode-demo-tenants.sql}).
   */
  boolean forceTestModeForFreeTenant(OnboardingProgressSink sink, String clientId, String orgId) {
    sink.progress(PROGRESS_FORCE_TEST_MODE, PROGRESS_IN_PROGRESS,
        "Configuring fiscal test mode...");
    try {
      onboardingForceTestModeService.forceTestModeForFreeTenant(clientId, orgId);
      sink.progress(PROGRESS_FORCE_TEST_MODE, PROGRESS_DONE, "Fiscal test mode configured");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding force-test-mode", e, log);
      return fail(sink, PROGRESS_FORCE_TEST_MODE, e, "Fiscal test mode configuration failed");
    }
  }

  /**
   * Registers the tenant's data-fix baseline row (the LIVE preventive counterpart of the corrective
   * runner's DETECTED sweep) as the final onboarding action before the commit.
   *
   * <p>Unlike the other steps, a genuine SQL failure here is NOT caught-and-returned-false: it
   * propagates so the caller's catch performs a clean {@code rollbackDalChanges}.
   * Swallowing it would poison the shared transaction and abort the otherwise-successful commit.
   * The expected {@code ON CONFLICT DO NOTHING} → 0-rows outcome never throws (DETECTED conserved).</p>
   */
  boolean registerBaseline(OnboardingProgressSink sink, String clientId) {
    sink.progress(PROGRESS_BASELINE, PROGRESS_IN_PROGRESS, "Registering data-fix baseline...");
    onboardingBaselineService.registerBaseline(clientId);
    sink.progress(PROGRESS_BASELINE, PROGRESS_DONE, "Data-fix baseline registered");
    return true;
  }

  /**
   * Creates the per-client costing schedule, backed by core's "Costing Background process"
   * (idempotent). Onboarding already imports a VALIDATED costing rule, so without this schedule the
   * rule sits there and no cost is ever calculated. Non-fatal: a missing costing schedule is worth
   * a log line, never a failed environment creation. The Quartz job is activated after the commit
   * (see the servlet's {@code executeOnboardingProvisioning}); even if that activation does not
   * run, the {@code SCH} row is picked up on the next scheduler initialization.
   */
  boolean scheduleCostingBackground(OnboardingProgressSink sink, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sink.progress(PROGRESS_COSTING_SCHEDULE, PROGRESS_IN_PROGRESS,
        "Scheduling automatic cost calculation...");
    try {
      onboardingCostingScheduleService.scheduleCostingBackground(clientId, orgId, adminUserId,
          adminRoleId);
      sink.progress(PROGRESS_COSTING_SCHEDULE, PROGRESS_DONE,
          "Automatic cost calculation scheduled");
    } catch (Exception e) {
      log.warn("Could not schedule cost calculation for client {}: {}", clientId, e.getMessage());
      sink.progress(PROGRESS_COSTING_SCHEDULE, PROGRESS_DONE,
          "Automatic cost calculation skipped");
    }
    return true;
  }

  /** Reports a failed step (error event + final result) and answers {@code false}. */
  private static boolean fail(OnboardingProgressSink sink, String step, Exception e,
      String fallbackMessage) {
    String errorMessage = e.getMessage() != null ? e.getMessage() : fallbackMessage;
    sink.progress(step, PROGRESS_ERROR, errorMessage);
    sink.result(false, errorMessage, null);
    return false;
  }
}
