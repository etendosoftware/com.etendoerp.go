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

package com.etendoerp.go.schemaforge;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.assetmgmt.Amortization;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;

/**
 * NeoHandler for the {@code amortizationLine} entity (table {@code A_Amortizationline}) of the
 * {@code assets} spec.
 *
 * <p>ETP-4981: {@code DELETE /sws/neo/assets/amortizationLine/{id}} on a line whose amortization
 * plan is already confirmed falls straight to default CRUD, which lets the DB trigger
 * {@code a_amortizationline_trg} raise {@code @20501@} ("Document posted/processed"). NEO Headless
 * has no generic translation for a raw PL/pgSQL trigger exception, so it bubbles up as an opaque
 * HTTP 500 with no usable message for the frontend — the delete silently fails with no feedback.
 *
 * <p><b>Root cause, verified against the trigger source (not guessed):</b> {@code A_Amortizationline}
 * carries no status column of its own. {@code a_amortizationline_trg} looks up
 * {@code Processed}/{@code Posted} on the *parent* {@code A_Amortization} header (resolved via the
 * line's {@code A_Amortization_ID} FK) and blocks INSERT/DELETE when either is {@code 'Y'}:
 * <pre>
 *   IF (TG_OP = 'INSERT' OR TG_OP = 'DELETE') THEN
 *     IF (coalesce(v_Processed, 'N') = 'Y' OR coalesce(v_Posted, 'N') = 'Y') THEN
 *       RAISE EXCEPTION '%', '@20501@';
 *     END IF;
 *   END IF;
 * </pre>
 * This mirrors the exact check {@link com.etendoerp.go.schemaforge.AmortizationPlanService} already
 * uses on the sibling {@code Asset.processed} flag ({@code "Y".equals(asset.getProcessed())}).
 *
 * <p>This handler pre-empts the trigger: on {@code DELETE}, it loads the target
 * {@link AmortizationLine}, resolves its parent {@link Amortization}, and — when the parent is
 * confirmed ({@code processed = 'Y'}) or posted ({@code posted = 'Y'}) — short-circuits with a
 * {@code 409 Conflict} carrying a descriptive message, matching the state-conflict convention used
 * elsewhere in this module (e.g. {@code AmortizationPlanService}'s
 * {@code "Asset already has a generated amortization plan"}, {@code FinancialAccountHandler}'s
 * blocked-delete responses). When the plan is still pending, {@code handle()} returns {@code null}
 * and the default CRUD service performs the actual delete.
 *
 * <p>All other endpoints and HTTP methods pass through to the default service unchanged.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'amortizationLineHandler'} on the ETGO_SF_ENTITY record
 * for spec {@code assets}, entity {@code amortizationLine}. Wired from {@code decisions.json} —
 * {@code entities.amortizationLine.javaQualifier} — by {@code push-to-neo.js} (Schema Forge side,
 * ETP-4981 companion change).
 */
@Named("amortizationLineHandler")
public class AmortizationLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(AmortizationLineHandler.class);

  private static final String HTTP_DELETE = "DELETE";
  private static final String STATUS_YES = "Y";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    if (!HTTP_DELETE.equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    String recordId = context.getRecordId();
    if (recordId == null || recordId.isEmpty()) {
      return null;
    }
    try {
      AmortizationLine line = OBDal.getInstance().get(AmortizationLine.class, recordId);
      if (line == null) {
        // Let default CRUD handle the not-found case.
        return null;
      }
      Amortization amortization = line.getAmortization();
      if (amortization == null) {
        // Orphan line (should not normally happen) — nothing to guard against, let it delete.
        return null;
      }
      boolean processed = STATUS_YES.equals(amortization.getProcessed());
      boolean posted = STATUS_YES.equals(amortization.getPosted());
      if (processed || posted) {
        log.debug("AmortizationLineHandler: blocked delete of confirmed line {} (amortization {}, "
            + "processed={}, posted={})", recordId, amortization.getId(), processed, posted);
        return NeoResponse.error(409,
            "Cannot delete this amortization line: the amortization plan has already been "
                + "confirmed" + (posted ? " and posted" : "") + ". Reverse or reopen the plan first.");
      }
      return null;
    } catch (Exception e) {
      // Fail-open: on any unexpected error, let the request continue to default CRUD — the DB
      // trigger remains the backstop, so worst case the caller sees the same behavior as before
      // this handler existed (a 500), never a false block on a legitimately deletable line.
      log.warn("AmortizationLineHandler: could not evaluate delete guard for line {}: {}",
          recordId, e.getMessage(), e);
      return null;
    }
  }
}
