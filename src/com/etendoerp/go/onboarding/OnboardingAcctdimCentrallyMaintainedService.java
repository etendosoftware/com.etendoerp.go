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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

/**
 * Forces {@code AD_Client.Acctdim_Centrally_Maintained = 'N'} (flat, per-dimension accounting
 * -dimension visibility) for every freshly-onboarded tenant — ETP-4854, gap K1.
 *
 * <h3>Root cause</h3>
 * Classic {@code InitialSetupUtility#createClient} (invoked by {@code InitialClientSetup}, which
 * the live onboarding chain calls from {@code EtendoGoJwtServlet#resolveOrCreateClient}, upstream
 * of this step) hardcodes {@code newClient.setAcctdimCentrallyMaintained(true)} for EVERY new
 * client. Under {@code 'Y'}, {@code DimensionDisplayUtility} resolves accounting-dimension field
 * visibility from the fine-grained {@code AD_Client.<Dim>_Acctdim_IsEnable/Header/Lines/Breakdown}
 * matrix — a classic multi-entity feature Etendo GO never built a screen for. Under {@code 'N'},
 * it resolves from the flat {@code C_AcctSchema_Element.IsActive} flag per dimension, which IS
 * what {@code GeneralLedgerConfigurationHandler#applyDimensionChanges} (the "Dimensiones
 * contables" screen) writes. So a tenant born {@code 'Y'} has a screen that is a functional no-op
 * for it (see {@code docs/etendo-ad/onboarding-gaps.md} gap K1 for the full trace).
 *
 * <h3>Why the backfill, not just the flip</h3>
 * Flat {@code 'N'} mode has no level distinction — ONE flag governs Header, Lines AND Breakdown
 * simultaneously for a dimension, whereas {@code C_AcctSchema_Element.isactive} defaults to
 * {@code 'Y'} on every row the accounting-wiring step created earlier in this same chain,
 * regardless of the client's (mostly {@code 'N'}) {@code <Dim>_Acctdim_IsEnable} defaults. Flipping
 * the flag WITHOUT backfilling would therefore make CostCenter/User1/User2/Project fields suddenly
 * visible for a brand-new tenant that never asked for them (mirrors the exact regression found —
 * and repaired — for already-onboarded tenants by the corrective twin,
 * {@code R23-acctdim-centrally-maintained.sql}). This service applies the IDENTICAL "effective =
 * IsEnable='Y' AND (Header='Y' OR Lines='Y' OR Breakdown='Y')" mapping as that SQL fix, in lockstep,
 * so both fronts stay consistent — see that file's own header comment for the full derivation.
 *
 * <h3>Safety</h3>
 * Confirmed by reading every consumer of this flag (classic core: {@code DimensionDisplayUtility},
 * {@code LoginUtils}, {@code InitialSetupUtility}; Etendo GO: {@code NeoDisplayLogicHelper}, a
 * faithful mirror of the classic logic) — no security/accounting-posting/compliance code path
 * reads it. It governs ONLY whether an accounting-dimension input field is shown or hidden on a
 * form.
 */
public class OnboardingAcctdimCentrallyMaintainedService {

  private static final Logger log =
      LogManager.getLogger(OnboardingAcctdimCentrallyMaintainedService.class);

  private static final String SYSTEM_ID = "0";

  /**
   * Backfills {@code C_AcctSchema_Element.isactive} per elementtype (across every accounting
   * schema the tenant owns) from the client's current effective per-dimension visibility, mirroring
   * {@code R23-acctdim-centrally-maintained.sql} step 1 exactly. Only the 7 dimensions configurable
   * from the Client window (Organization/Project/BPartner/Product/CostCenter/User1/User2) are
   * touched; the mandatory Account (AC) element is never part of this map and is left untouched.
   */
  private static final String SQL_BACKFILL_ELEMENTS = ""
      + "WITH dim_effective AS ("
      + "  SELECT 'OO'::varchar(2) AS elementtype,"
      + "         (org_acctdim_isenable = 'Y'"
      + "           AND (org_acctdim_header = 'Y' OR org_acctdim_lines = 'Y' OR org_acctdim_breakdown = 'Y')) AS effective"
      + "  FROM ad_client WHERE ad_client_id = ?"
      + "  UNION ALL"
      + "  SELECT 'PJ',"
      + "         (project_acctdim_isenable = 'Y'"
      + "           AND (project_acctdim_header = 'Y' OR project_acctdim_lines = 'Y' OR project_acctdim_breakdown = 'Y'))"
      + "  FROM ad_client WHERE ad_client_id = ?"
      + "  UNION ALL"
      + "  SELECT 'BP',"
      + "         (bpartner_acctdim_isenable = 'Y'"
      + "           AND (bpartner_acctdim_header = 'Y' OR bpartner_acctdim_lines = 'Y' OR bpartner_acctdim_breakdown = 'Y'))"
      + "  FROM ad_client WHERE ad_client_id = ?"
      + "  UNION ALL"
      + "  SELECT 'PR',"
      + "         (product_acctdim_isenable = 'Y'"
      + "           AND (product_acctdim_header = 'Y' OR product_acctdim_lines = 'Y' OR product_acctdim_breakdown = 'Y'))"
      + "  FROM ad_client WHERE ad_client_id = ?"
      + "  UNION ALL"
      + "  SELECT 'CC',"
      + "         (costcenter_acctdim_isenable = 'Y'"
      + "           AND (costcenter_acctdim_header = 'Y' OR costcenter_acctdim_lines = 'Y' OR costcenter_acctdim_breakdown = 'Y'))"
      + "  FROM ad_client WHERE ad_client_id = ?"
      + "  UNION ALL"
      + "  SELECT 'U1',"
      + "         (user1_acctdim_isenable = 'Y'"
      + "           AND (user1_acctdim_header = 'Y' OR user1_acctdim_lines = 'Y' OR user1_acctdim_breakdown = 'Y'))"
      + "  FROM ad_client WHERE ad_client_id = ?"
      + "  UNION ALL"
      + "  SELECT 'U2',"
      + "         (user2_acctdim_isenable = 'Y'"
      + "           AND (user2_acctdim_header = 'Y' OR user2_acctdim_lines = 'Y' OR user2_acctdim_breakdown = 'Y'))"
      + "  FROM ad_client WHERE ad_client_id = ?"
      + ")"
      + " UPDATE c_acctschema_element e"
      + "    SET isactive = CASE WHEN de.effective THEN 'Y' ELSE 'N' END,"
      + "        updated = now(), updatedby = ?"
      + "   FROM dim_effective de"
      + "  WHERE e.ad_client_id = ?"
      + "    AND e.elementtype = de.elementtype"
      + "    AND e.isactive IS DISTINCT FROM (CASE WHEN de.effective THEN 'Y' ELSE 'N' END)";

  /** Flips the mode flag itself — the second, atomic half of the same fix. */
  private static final String SQL_FLIP_FLAG = ""
      + "UPDATE ad_client"
      + "   SET acctdim_centrally_maintained = 'N', updated = now(), updatedby = ?"
      + " WHERE ad_client_id = ?"
      + "   AND acctdim_centrally_maintained = 'Y'";

  /**
   * Backfills the tenant's {@code C_AcctSchema_Element} rows and flips
   * {@code Acctdim_Centrally_Maintained} to {@code 'N'} on the shared DAL connection, atomically
   * with the rest of onboarding.
   *
   * @param clientId the freshly-provisioned tenant
   * @throws OBException if {@code clientId} is missing or a genuine SQL error occurs (so the
   *     caller can convert it into a graceful onboarding failure)
   */
  public void forceFlatAccountingDimensionVisibility(String clientId) {
    if (clientId == null || clientId.isEmpty()) {
      throw new OBException(
          "Cannot force flat accounting-dimension visibility: onboarding context has no client id");
    }

    try {
      // Flush pending DAL state (the accounting-wiring steps earlier in the chain created the
      // C_AcctSchema_Element rows this backfill reads/writes) so the native statements below see
      // a consistent shared connection.
      OBDal.getInstance().flush();

      Connection conn = OBDal.getInstance().getConnection();

      int touchedElements;
      try (PreparedStatement ps = conn.prepareStatement(SQL_BACKFILL_ELEMENTS)) {
        for (int i = 1; i <= 7; i++) {
          ps.setString(i, clientId);
        }
        ps.setString(8, SYSTEM_ID);
        ps.setString(9, clientId);
        touchedElements = ps.executeUpdate();
      }

      int flipped;
      try (PreparedStatement ps = conn.prepareStatement(SQL_FLIP_FLAG)) {
        ps.setString(1, SYSTEM_ID);
        ps.setString(2, clientId);
        flipped = ps.executeUpdate();
      }

      log.info(
          "Forced flat accounting-dimension visibility for new tenant '{}': {} element row(s) "
              + "backfilled, centrally-maintained flag flipped: {}",
          clientId, touchedElements, flipped > 0);
    } catch (Exception e) {
      throw new OBException(
          "Failed to force flat accounting-dimension visibility for tenant '" + clientId + "': "
              + e.getMessage(), e);
    }
  }
}
