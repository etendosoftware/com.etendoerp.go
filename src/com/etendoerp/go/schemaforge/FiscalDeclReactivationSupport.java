/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBQuery;

/**
 * ETP-5725 (reopened QA review, Sonar java:S1448) — "Reactivar declaración" guards, extracted out
 * of {@link FiscalDeclCrudHandler} to keep that class under the SonarQube method-count threshold
 * (see {@link FiscalDeclCorrectiveConsistencySupport}'s own javadoc for the full extraction
 * rationale — this class follows the identical pattern, holding a reference back to the {@code
 * owner} plus, for the corrective/ordinaria identity check {@link #rejectReactivationWithSibling}
 * needs, a reference to the sibling {@link FiscalDeclCorrectiveConsistencySupport} instance).
 */
class FiscalDeclReactivationSupport {

  private final FiscalDeclCrudHandler owner;
  private final FiscalDeclCorrectiveConsistencySupport correctiveConsistencySupport;

  FiscalDeclReactivationSupport(FiscalDeclCrudHandler owner,
      FiscalDeclCorrectiveConsistencySupport correctiveConsistencySupport) {
    this.owner = owner;
    this.correctiveConsistencySupport = correctiveConsistencySupport;
  }

  /**
   * Guards "Reactivar declaración" (ETP-5338), which reverts a presented declaration back to
   * draft via the PUT path ({@code status: "draft"}). Defense in depth, mirroring {@code
   * FiscalDeclCrudHandler#handleDeclDelete}'s draft-only guard: the frontend already hides the
   * Reactivar action for {@code aeat_telematic} declarations ({@code FmRowActions.jsx} / {@code
   * FmListPage.jsx}), but this is what actually prevents one from being reopened regardless of
   * what the client sends — reactivating a declaration that was genuinely filed with the AEAT
   * would desync this table from what Hacienda has on record, which is unrecoverable from here.
   *
   * @return {@code true} if the PUT was rejected (a 409 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  boolean rejectTelematicReactivation(BaseOBObject decl, JSONObject body, String id,
      HttpServletResponse response) throws Exception {
    boolean hasStatus = body.has(FiscalDeclCrudHandler.STATUS_KEY);
    String status = hasStatus ? body.getString(FiscalDeclCrudHandler.STATUS_KEY) : null;
    if (!hasStatus || !FiscalDeclCrudHandler.DEFAULT_STATUS.equals(status)) {
      return false;
    }
    String currentSubmissionMethod = FiscalDeclCrudHandler.asString(
        decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD));
    if (!FiscalDeclCrudHandler.SUBMISSION_METHOD_AEAT_TELEMATIC.equals(currentSubmissionMethod)) {
      return false;
    }
    owner.servlet.sendError(response, HttpServletResponse.SC_CONFLICT,
        "Cannot reactivate a declaration filed via AEAT telematic submission: " + id);
    return true;
  }

  /**
   * ETP-5725 (reopened QA fix) — Bug 2/4: blocks "Reactivar declaración" (status back to {@code
   * draft}) whenever ANY other declaration — draft or presented alike — already exists for the
   * same natural key ({@code AD_CLIENT_ID, AD_ORG_ID, MODEL, FISCAL_YEAR, PERIOD}) — but the rule
   * is ASYMMETRIC, not "any sibling blocks either declaration". See {@link
   * #findReactivationBlockReason} for the exact per-case logic; this method only resolves {@code
   * decl}'s own corrective/ordinaria identity and delegates the sibling scan to it.
   *
   * <p>QA's original repro (349): declaration A (ordinaria) presented, declaration B (correctly
   * auto-defaulted Sustitutiva) presented for the same period. Reactivating A used to succeed and
   * revert it to draft while a presented corrective sibling (B) was left pointing at a period
   * whose ordinaria no longer exists — an AEAT-invalid state (a corrective with nothing to
   * correct, the same invariant {@code FiscalDeclCorrectiveConsistencySupport
   * #rejectCorrectiveConsistencyViolation} enforces going forward). That is Bug 2/4: reactivating
   * the ORDINARIA while a PRESENTED corrective sibling exists must be blocked.
   *
   * <p>A LATER manual-testing fix (same ticket, same day) found the first version of this guard
   * had over-corrected: it blocked reactivating EITHER declaration whenever ANY sibling existed,
   * which wrongly blocked reactivating the CORRECTIVE itself while its ordinaria sibling was
   * presented — but a corrective's ordinaria stays valid underneath it regardless (the
   * corrective references it, "Identificador declaración anterior"; revising/resubmitting the
   * correction doesn't invalidate the base declaration it corrects). Only a DRAFT sibling (of
   * either declaration, any type) is unsafe regardless of which one is being reactivated — it
   * would violate the existing one-draft-per-period rule (ETP-5272) the moment the reactivated
   * row also becomes a draft.
   *
   * <p>This guard is model-agnostic by construction: it branches on corrective/ordinaria
   * identity (structural, read off {@code manualData.identification}), never on which
   * model/entity is involved.
   *
   * @return {@code true} if the PUT was rejected (a 409 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  boolean rejectReactivationWithSibling(BaseOBObject decl, JSONObject body,
      String previousStatus, String id, HttpServletResponse response) throws Exception {
    boolean hasStatus = body.has(FiscalDeclCrudHandler.STATUS_KEY);
    String status = hasStatus ? body.getString(FiscalDeclCrudHandler.STATUS_KEY) : null;
    if (!hasStatus || !FiscalDeclCrudHandler.DEFAULT_STATUS.equals(status)
        || !FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(previousStatus)) {
      return false;
    }
    String clientId = FiscalDeclCrudHandler.getRelatedId(decl, FiscalDeclCrudHandler.PROPERTY_CLIENT);
    String orgId = FiscalDeclCrudHandler.getRelatedId(decl, FiscalDeclCrudHandler.PROPERTY_ORGANIZATION);
    String model = FiscalDeclCrudHandler.asString(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL));
    long year = FiscalDeclCrudHandler.asLong(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_YEAR));
    String period = FiscalDeclCrudHandler.asString(decl.get(FiscalDeclCrudHandler.PROPERTY_PERIOD));
    boolean declIsCorrective = correctiveConsistencySupport.isEffectivelyCorrective(decl, body);
    String reason = findReactivationBlockReason(decl, declIsCorrective, clientId, orgId, model,
        year, period);
    if (reason != null) {
      owner.servlet.sendError(response, HttpServletResponse.SC_CONFLICT,
          "Cannot reactivate [reason=" + reason + "]: another declaration already exists for "
              + "this period: " + id);
      return true;
    }
    return false;
  }

  /**
   * ETP-5725 (reopened QA fix, corrected per later manual testing) — the asymmetric sibling scan
   * behind {@link #rejectReactivationWithSibling}:
   * <ul>
   *   <li>A DRAFT sibling (any type — ordinaria or corrective) ALWAYS blocks, regardless of
   *       {@code declIsCorrective} — tagged {@code "sibling_exists"}.</li>
   *   <li>Otherwise, a PRESENTED corrective sibling blocks ONLY when {@code decl} itself is NOT
   *       corrective (i.e. {@code decl} is the ordinaria) — tagged {@code
   *       "corrective_sibling_presented"}. Reactivating the corrective itself is NEVER blocked by
   *       a presented sibling, including its own presented ordinaria: the ordinaria stays valid
   *       as the base declaration underneath it.</li>
   * </ul>
   * {@code servlet.sendError}'s message only ever reaches the frontend as plain English text
   * ({@code error.message}, not a structured field — this package has no JSON-body {@code
   * sendError} overload), so the returned reason is tagged into that message as a stable,
   * machine-parseable {@code [reason=...]} marker that {@code FmListPage.jsx}'s {@code
   * handleConfirmReactivate} extracts via {@code parseServerMessage} + a small regex, instead of
   * either guessing from the status code alone (409 is ambiguous between the two reasons) or
   * hand-rolling a second JSON error shape.
   *
   * @return the block reason, or {@code null} when reactivation may proceed.
   */
  private String findReactivationBlockReason(BaseOBObject decl, boolean declIsCorrective,
      String clientId, String orgId, String model, long year, String period) {
    OBQuery<BaseOBObject> query =
        FiscalDeclCrudHandler.naturalKeyQuery(clientId, orgId, model, year, period);
    boolean draftSiblingExists = false;
    boolean presentedCorrectiveSiblingExists = false;
    for (BaseOBObject existing : query.list()) {
      if (isSameDeclaration(existing, decl)) {
        continue;
      }
      String siblingStatus = FiscalDeclCrudHandler.asString(
          existing.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS));
      if (FiscalDeclCrudHandler.DEFAULT_STATUS.equals(siblingStatus)) {
        draftSiblingExists = true;
      } else if (FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(siblingStatus)) {
        JSONObject siblingIdent = FiscalDeclCrudHandler.parseManualData(existing)
            .optJSONObject(FiscalDeclCrudHandler.IDENTIFICATION_KEY);
        if (FiscalDeclCorrectiveConsistencySupport.identFlagTrue(siblingIdent,
            FiscalDeclCrudHandler.RECTIFICATIVA_KEY)
            || FiscalDeclCorrectiveConsistencySupport.identFlagTrue(siblingIdent,
                FiscalDeclCrudHandler.SUSTITUTIVA_KEY)) {
          presentedCorrectiveSiblingExists = true;
        }
      }
    }
    if (draftSiblingExists) {
      return "sibling_exists";
    }
    if (!declIsCorrective && presentedCorrectiveSiblingExists) {
      return "corrective_sibling_presented";
    }
    return null;
  }

  /** {@code true} when {@code candidate} and {@code decl} are the same persisted row. */
  private static boolean isSameDeclaration(BaseOBObject candidate, BaseOBObject decl) {
    return candidate.getId() != null && candidate.getId().equals(decl.getId());
  }
}
