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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

/**
 * Stamps a freshly-provisioned tenant with its BASELINE row in the System-owned data-fix ledger
 * ({@code ETGO_DATA_FIX_HISTORY}). This is the LIVE preventive-front counterpart to the corrective
 * runner's Phase-0 DETECTED sweep (see {@code schema_forge/cli/src/data-fixes/run.js}).
 *
 * <p>It is invoked as the final action of {@code EtendoGoJwtServlet.ensureOnboardingDataset}, before
 * {@code commitDalChanges("onboarding")}, so the baseline row is part of the same atomic onboarding
 * commit. This service is the single source of truth for baseline registration.</p>
 *
 * <h3>Row shape (non-negotiable, System-owned)</h3>
 * {@code ad_client_id='0'}, {@code ad_org_id='0'} (the ledger is System-owned so only the System
 * Administrator sees every tenant's history in one grid), the new tenant in the dedicated
 * {@code remediated_client_id} FK, {@code fix_id='__baseline__'}, {@code status='BASELINE'},
 * {@code applied_utc=ONBOARDING_PROVISIONED_THROUGH} (a hardcoded CUT — NOT {@code now()} — that
 * represents the last data-fix already incorporated into this version of onboarding; bump it when
 * a new gap is closed on the preventive front). PK generated DB-side via {@code get_uuid()} — never hand-typed.
 *
 * <h3>Idempotency &amp; DETECTED conservation</h3>
 * The insert uses {@code ON CONFLICT ON CONSTRAINT etgo_dfh_tenant_fix_un DO NOTHING}. If a baseline
 * row already exists for this tenant (e.g. a DETECTED row left by the legacy sweep) it is conserved,
 * not overwritten — DETECTED remains the baseline. The 0-row outcome is expected and benign.
 *
 * <h3>Transaction / failure semantics (shared connection)</h3>
 * This runs on the shared DAL connection ({@code OBDal.getInstance().getConnection()}) that the rest
 * of onboarding commits via {@code OBDal.commitAndClose()}. In PostgreSQL a statement error aborts
 * the whole transaction, so swallowing a SQL error here would poison the final onboarding commit.
 * Therefore the contract is:
 * <ul>
 *   <li><b>Expected case</b> ({@code ON CONFLICT} → 0 rows): non-fatal, logged at INFO, no throw.</li>
 *   <li><b>Genuine error</b> (near-impossible — no SELECT dependency, all literals + the just-created
 *       client FK): rethrown as {@link OBException}, never swallowed. The caller lets it propagate to
 *       {@code handleOnboarding}'s catch, which performs a clean {@code rollbackDalChanges}.</li>
 * </ul>
 * This honors "registering the baseline must not abort a healthy onboarding" for every realistic
 * outcome, while avoiding the unsafe swallow-on-poisoned-connection anti-pattern.
 */
public class OnboardingBaselineService {

  private static final Logger log = LogManager.getLogger(OnboardingBaselineService.class);

  /** Sentinel fix id that marks a tenant's baseline/cutoff row (one per tenant via the UNIQUE constraint). */
  private static final String BASELINE_FIX_ID = "__baseline__";

  /** Ledger status for a tenant that finished provisioning clean. */
  private static final String BASELINE_STATUS = "BASELINE";

  /** System owner shape — the ledger is System-owned regardless of the tenant being created. */
  private static final String SYSTEM_ID = "0";

  /**
   * The timestamp of the last data-fix that this version of onboarding already provisions natively.
   * New tenants get this as their BASELINE applied_utc, so the corrective runner skips every fix
   * at-or-before this cutoff (they were never needed — onboarding was already correct when the
   * tenant was created).
   *
   * <p><b>BUMP THIS</b> each time a new gap is closed on the preventive front (i.e. a new
   * Onboarding*Service is added that provisions the same data as the corresponding corrective fix).
   * Use the exact UTC timestamp prefix of the last incorporated .sql file, e.g.:
   * {@code "20260617T120000Z"} matches {@code 20260617T120000Z__R7-tax-accounts.sql}.</p>
   *
   * Current watermark: R26 admin-identity-real-org (2026-08-26).
   *
   * <p><b>Note (2026-08-26, ETP-4999):</b> gap M1 — the self-registration provisioning chain
   * ({@code InitialClientSetup} then, separately and later, {@code
   * EtendoGoJwtServlet#createOrganization}) left the onboarding admin's {@code
   * Default_Ad_Client_ID}/{@code Default_Ad_Org_ID}/{@code Default_M_Warehouse_ID}/{@code
   * EM_SMFSWS_Default_WS_Role_ID} all {@code NULL} and their sole {@code AD_User_Roles} row
   * stuck at the root org {@code '0'} — confirmed live on dozens of self-registered tenants,
   * zero exceptions. The first breaks SWS login/environment-switch warehouse resolution ({@code
   * SecureWebServicesUtils.generateToken()} → {@code SMFSWS_OrgHasNoRole}); the second breaks a
   * self-registered admin inviting themselves into their own real org ({@code
   * CompanyInvitationDalHelper#hasActiveRoleForOrganization}'s exact-org match against {@code
   * AD_User_Roles}). Closed by {@code OnboardingAdminIdentityService}, wired as the new step
   * right before this baseline stamp (after the accounting-wiring steps grant the admin role
   * {@code AD_Role_OrgAccess} for the real org). Bumped to R26's own timestamp, {@code
   * 2026-08-26T12:00:00Z}.</p>
   *
   * <p><b>Note (2026-08-11, ETP-4854):</b> gap K1 — {@code AD_Client.Acctdim_Centrally_Maintained}
   * was hardcoded to {@code true} for every new client by classic {@code InitialSetupUtility},
   * locking every tenant out of the flat, per-dimension accounting-dimension visibility mechanism
   * that Etendo GO's own "Dimensiones contables" screen ({@code
   * GeneralLedgerConfigurationHandler#applyDimensionChanges}) actually writes to — the fine-grained
   * {@code AD_Client.<Dim>_Acctdim_*} matrix the {@code true} default routes to has no Etendo GO
   * screen at all. Closed by {@code OnboardingAcctdimCentrallyMaintainedService}, wired as the new
   * step right before this baseline stamp. Bumped to R23's own timestamp, {@code
   * 2026-08-11T12:00:00Z}.</p>
   *
   * <p><b>Note (2026-08-05, ETP-4743):</b> bumped from R21's {@code 2026-08-05T12:00:00Z} (see
   * the ETP-4720 note below) to R22's {@code 2026-08-05T14:00:00Z}, per this merge block's
   * resolution of the conflict the ETP-4743 branch itself anticipated on this line. Gap A2c —
   * {@code FIN_FINANCIAL_ACCOUNT} and {@code M_WAREHOUSE} are bulk-imported by the dataset
   * importer with triggers disabled, so neither ever got its {@code *_Acct} row via the standard
   * core AFTER-INSERT triggers. ETP-4565 already shipped the preventive fix for this
   * ({@code FIN_FINANCIAL_ACCOUNT_ACCT_SQL} / {@code WAREHOUSE_ACCT_SQL} in
   * {@code OnboardingAccountingWiringService}, called from {@code provisionEntityPostingAccounts})
   * but deliberately did NOT bump this constant at the time, since the corrective {@code .sql}
   * twin did not exist yet (bumping the CUT without its matching fix already in the repo would
   * silently skip the gap for new tenants). ETP-4743 adds that corrective fix
   * ({@code R22-fin-account-warehouse-acct}) for already-onboarded tenants, so this bump now
   * closes the loop: new tenants are already provisioned correctly by the live ETP-4565 code, and
   * the runner correctly skips R22 for them via this watermark.</p>
   *
   * <p><b>Note (2026-07-06):</b> the sibling in-flight branch {@code feat/bp-category-preventive}
   * (ETP-4402) independently bumps this same constant to {@code 2026-07-01T12:00:00Z} for its
   * {@code R9-bp-category-seed} fix, and this same branch previously bumped it to
   * {@code 2026-07-06T12:00:00Z} for {@code R10-accounting-schema-dimensions}. Multiple in-flight
   * branches touch this single line — expect merge conflicts when they converge; always resolve to
   * the LATEST timestamp so no fix's cutoff is lost.</p>
   *
   * <p><b>Note (2026-07-30, ETP-4737):</b> bumped from R13's {@code 2026-07-08T10:00:00Z} to
   * R17's {@code 2026-07-30T18:00:00Z}. Fixes R14/R15/R16 in between are dataset-only or
   * non-provisioning (no CUT bump needed per their own doc — see
   * {@code onboarding-and-datafixes-map.md} §4); R17 is the first fix since R13 whose preventive
   * counterpart is a new onboarding action (the two "Factura Rectificativa" doc types/sequences),
   * so this is the first bump since R13.</p>
   *
   * <p><b>Note (2026-08-03, ETP-4761):</b> gap I1 — the bundled locators in
   * {@code M_LOCATOR.xml} now ship {@code M_INVENTORYSTATUS_ID='2'} ("Available") instead of
   * {@code '0'} ("Undefined-OverIssue"), so a new tenant is no longer born with storage bins that
   * allow negative stock. That preventive front alone put the watermark at
   * {@code 2026-08-03T16:00:00Z} (R19).</p>
   *
   * <p><b>Note (2026-08-03, ETP-4760):</b> gap J1 — {@code M_COSTING_RULE} added to
   * {@link OnboardingDatasetDefinition}'s {@code INCLUDED_TABLES} and its bundled sample row fixed
   * to the Standard algorithm (was Average), so a new tenant is born with one active, validated
   * Standard costing rule instead of zero rules. This is the later of the two, hence the value
   * below.</p>
   *
   * <p><b>Note (2026-08-03, merge block ETP-4766):</b> the two notes above landed on separate
   * branches, each bumping this constant (R19 → {@code 16:00:00Z}, R20 → {@code 18:00:00Z}).
   * Resolved to the LATEST per the rule above; the watermark now covers BOTH preventive fronts.
   * R18 (stuck-average-cost-anchor / ETP-4736, {@code 2026-08-03T14:00:00Z}) also falls below the
   * cutoff even though it deliberately shipped NO preventive front — this is intentional and
   * harmless: a newborn tenant has no products or transactions, so R18's {@code @check} would
   * resolve to {@code SKIPPED_NOT_NEEDED} anyway, the same terminal state as being skipped. Should
   * gap H3 ever surface later in that tenant's life, R18 must be forced with
   * {@code --fix R18-stuck-average-cost-anchor --client <id>} — which is equally true for any
   * tenant, since the runner never revisits an already-PROCESSED fix.
   * R17 (rectificativa-doctype-sequence / ETP-4737, {@code 2026-07-30T18:00:00Z}) also merged in
   * here and likewise falls below the cutoff — correctly so: it DOES ship a preventive front, so a
   * newborn tenant is already provisioned with the two "Factura Rectificativa" doc types and their
   * {@code REC-} sequences and must skip the corrective fix.</p>
   *
   * <p><b>Note (2026-08-05, ETP-4720):</b> gap A2b generalized — 5 {@code C_BP_Group_Acct} columns
   * ({@code WriteOff_Rev_Acct}/{@code DoubtfulDebt_Acct}/{@code BadDebtExpense_Acct}/
   * {@code BadDebtRevenue_Acct}/{@code AllowanceForDoubtful_Acct}) that neither the core
   * {@code c_bp_group_trg()} trigger nor {@code OnboardingAccountingWiringService.BP_GROUP_ACCT_SQL}
   * ever populated — confirmed live on a tenant onboarded just 6 days before diagnosis, so this was
   * an ONGOING preventive gap, not only legacy drift. Closed by
   * {@code OnboardingAccountingWiringService#patchBpGroupAcctMissingColumns}, wired as the new last
   * provisioning step before this baseline stamp. Bumped to R21's own timestamp,
   * {@code 2026-08-05T12:00:00Z}. The other 6 columns R21's corrective fix also covers
   * (NotInvoicedRevenue/NotInvoicedReceivables/UnEarnedRevenue/PayDiscount_Exp/PayDiscount_Rev/
   * V_Liability_Services) are NOT part of this preventive fix — their source,
   * {@code C_AcctSchema_Default}, is itself NULL fleet-wide (an R11-adjacent gap, out of this
   * ticket's scope); R21's own {@code @check} already no-ops on them today and will self-heal once
   * that separate gap closes, with no onboarding change needed here.</p>
   */
  private static final Instant ONBOARDING_PROVISIONED_THROUGH = Instant.parse("2026-08-26T12:00:00Z");

  private static final String SQL_INSERT_BASELINE = ""
      + "INSERT INTO etgo_data_fix_history ("
      + "  etgo_data_fix_history_id, ad_client_id, ad_org_id, isactive,"
      + "  created, createdby, updated, updatedby,"
      + "  remediated_client_id, fix_id, status, applied_utc, rows_affected, detail"
      + ") VALUES ("
      + "  get_uuid(), ?, ?, 'Y',"
      + "  now(), ?, now(), ?,"
      + "  ?, ?, ?, ?, 0, NULL"
      + ") ON CONFLICT ON CONSTRAINT etgo_dfh_tenant_fix_un DO NOTHING";

  /**
   * Registers the BASELINE ledger row for the given tenant on the shared DAL connection.
   *
   * @param clientId the freshly-provisioned tenant (becomes {@code remediated_client_id})
   * @throws OBException if {@code clientId} is missing or a genuine SQL error occurs (so the caller
   *     can roll back the shared onboarding transaction cleanly)
   */
  public void registerBaseline(String clientId) {
    if (clientId == null || clientId.isEmpty()) {
      throw new OBException(
          "Cannot register data-fix baseline: onboarding context has no client id");
    }

    try {
      // Flush pending DAL state so the ledger insert runs against a consistent shared connection.
      OBDal.getInstance().flush();

      Connection conn = OBDal.getInstance().getConnection();
      int inserted;
      try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_BASELINE)) {
        ps.setString(   1, SYSTEM_ID);   // ad_client_id — System-owned
        ps.setString(   2, SYSTEM_ID);   // ad_org_id    — System
        ps.setString(   3, SYSTEM_ID);   // createdby    — System
        ps.setString(   4, SYSTEM_ID);   // updatedby    — System
        ps.setString(   5, clientId);    // remediated_client_id — the new tenant
        ps.setString(   6, BASELINE_FIX_ID);
        ps.setString(   7, BASELINE_STATUS);
        ps.setTimestamp(8, Timestamp.from(ONBOARDING_PROVISIONED_THROUGH)); // applied_utc — fixed CUT
        inserted = ps.executeUpdate();
      }

      if (inserted > 0) {
        log.info("Registered BASELINE data-fix row for new tenant '{}'", clientId);
      } else {
        // ON CONFLICT DO NOTHING fired: a baseline row (BASELINE or DETECTED) already exists.
        // Expected and benign — the existing row is conserved.
        log.info("Baseline data-fix row already present for tenant '{}' — conserved, not overwritten",
            clientId);
      }
    } catch (Exception e) {
      // Never swallow on the shared connection: a real SQL error has already aborted the tx, so we
      // rethrow and let handleOnboarding roll back cleanly instead of poisoning the final commit.
      throw new OBException(
          "Failed to register data-fix baseline for tenant '" + clientId + "': " + e.getMessage(),
          e);
    }
  }
}
