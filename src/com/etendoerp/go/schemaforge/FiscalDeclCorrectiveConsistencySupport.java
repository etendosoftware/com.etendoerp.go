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
 * ETP-5725 (reopened QA review, Sonar java:S1448) — the corrective/ordinaria consistency rule
 * ("a corrective declaration needs a presented ordinaria to correct" and its mirror, "only one
 * presented ordinaria per period"), extracted out of {@link FiscalDeclCrudHandler} to keep that
 * class under the SonarQube method-count threshold (it had grown to 46 methods, limit 35, mixing
 * this cluster with the reactivation-guard cluster and the core CRUD flow). Extracted the same
 * way {@link FiscalSubmittedSnapshotSupport} already was, and the same way
 * {@code Fiscal349BoxesHandler} splits off {@code Fiscal349ViesSupport}/
 * {@code Fiscal349GenerateSupport} — one {@code *Support} class per cohesive responsibility,
 * holding a reference back to the {@code owner} for the primitives it still needs.
 *
 * <p>{@link #checkCorrectiveConsistency} is called from FOUR entry points, none of them private
 * to this class: {@link FiscalDeclCrudHandler#handleDeclPut} (via {@link
 * #rejectCorrectiveConsistencyViolation} below), {@link FiscalDeclCrudHandler#handleDeclPost}
 * (CREATE), {@link AbstractFiscalHandler#guardCorrectiveConsistency} (the {@code generate}
 * file-generation reads for both models), and {@link Fiscal303SubmissionSupport#handleSubmit}
 * (the real AEAT telematic filing, 303-only). See {@link #checkCorrectiveConsistency}'s own
 * javadoc for the full rationale of each.
 */
class FiscalDeclCorrectiveConsistencySupport {

  private final FiscalDeclCrudHandler owner;

  FiscalDeclCorrectiveConsistencySupport(FiscalDeclCrudHandler owner) {
    this.owner = owner;
  }

  /**
   * ETP-5725 (reopened QA fix) — the shared business rule behind Bug 1 ("a corrective needs a
   * presented ordinaria to correct") and its mirror ("only one presented ordinaria per period"),
   * factored into ONE method so every entry point that can leave a declaration's corrective/
   * ordinaria identification inconsistent with its siblings' presented statuses enforces it the
   * SAME way — {@link #rejectCorrectiveConsistencyViolation} (save/present, via {@code PUT
   * /fiscal{model}/declarations}), {@link AbstractFiscalHandler#guardCorrectiveConsistency}
   * (the {@code generate} file-generation reads for both models, which have no declaration id
   * of their own to exclude — see that method's own javadoc), {@link
   * Fiscal303SubmissionSupport#handleSubmit} (the real AEAT telematic filing, 303-only), and
   * {@link FiscalDeclCrudHandler#handleDeclPost} (CREATE, {@code POST
   * /fiscal{model}/declarations} — a declaration created directly with a {@code
   * SUBMITTED_STATUSES} status is "already submitted" from its very first write, so {@link
   * #rejectCorrectiveConsistencyViolation}'s own early-exit on an already-submitted {@code
   * previousStatus} can never run on it afterward; this call is what actually enforces the rule
   * in that case — see {@code handleDeclPost}'s own comment). Originally two near-duplicate
   * guards (one per direction); unified once "Guardar" and "Generar fichero" needed the identical
   * check and more call sites made the duplication worse rather than better.
   *
   * @param excludeId the id of the declaration this check is FOR, excluded from its own sibling
   *                   search so a row doesn't see itself as a sibling of itself. May be
   *                   {@code null} ({@code generate}/CREATE have no declaration id of their own —
   *                   the caller resolves the LATEST declaration's id instead, if one exists).
   * @param effectivelyCorrective whether the identification THIS check is evaluating (the PUT
   *                              body's own {@code manualData.identification}, or the
   *                              {@code generate}/{@code submit} request's own corrective/
   *                              ordinaria param) marks it as rectificativa (303) / sustitutiva
   *                              (349).
   * @return a human-readable violation message if the rule is broken, or {@code null} when the
   *         state is valid and the caller may proceed.
   */
  String checkCorrectiveConsistency(String clientId, String orgId, String model, long year,
      String period, String excludeId, boolean effectivelyCorrective) {
    boolean presentedOrdinariaSiblingExists =
        hasPresentedOrdinariaSibling(excludeId, clientId, orgId, model, year, period);
    if (effectivelyCorrective && !presentedOrdinariaSiblingExists) {
      return "Cannot save/present a corrective declaration (rectificativa/sustitutiva) without a "
          + "presented ordinaria declaration for the same period";
    }
    if (!effectivelyCorrective && presentedOrdinariaSiblingExists) {
      return "Cannot save/present as ordinaria: another ordinaria declaration is already "
          + "presented for this period";
    }
    return null;
  }

  /**
   * ETP-5725 (reopened QA fix) — enforces {@link #checkCorrectiveConsistency} on EVERY PUT that
   * leaves (or finds) the declaration NOT already in {@code SUBMITTED_STATUSES} — i.e. "Guardar"
   * (a plain manualData save, no status change at all: {@code body} carries no {@code status} key
   * and this fires regardless) AND "Registrar/Presentar" (the genuine first-time transition INTO
   * {@code SUBMITTED_STATUSES}) ALIKE, closing the gap QA found: the save path's PUT body never
   * carries a {@code status} key (see {@code persistEditableFields}/{@code persistManualData} on
   * the frontend), so the original version of this guard — gated on {@code body.has(STATUS_KEY)}
   * — never fired for "Guardar", only for "Registrar/Presentar". A plain save could therefore
   * PERSIST a corrective-without-ordinaria (or duplicate-ordinaria) state even though presenting
   * it was already correctly blocked.
   *
   * <p>Skips entirely once the declaration is ALREADY in {@code SUBMITTED_STATUSES} ({@code
   * previousStatus}) — re-presentation is a different, already-blocked case ({@link
   * FiscalDeclCrudHandler#rejectRepresentation}), and a reactivation (submitted → draft) is a
   * different rule entirely ({@link FiscalDeclReactivationSupport#rejectReactivationWithSibling});
   * neither needs this check re-run.
   *
   * @return {@code true} if the PUT was rejected (a 409 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  boolean rejectCorrectiveConsistencyViolation(BaseOBObject decl, JSONObject body,
      String previousStatus, String id, HttpServletResponse response) throws Exception {
    if (FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(previousStatus)) {
      return false;
    }
    boolean effectivelyCorrective = isEffectivelyCorrective(decl, body);
    String clientId = FiscalDeclCrudHandler.getRelatedId(decl, FiscalDeclCrudHandler.PROPERTY_CLIENT);
    String orgId = FiscalDeclCrudHandler.getRelatedId(decl, FiscalDeclCrudHandler.PROPERTY_ORGANIZATION);
    String model = FiscalDeclCrudHandler.asString(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL));
    long year = FiscalDeclCrudHandler.asLong(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_YEAR));
    String period = FiscalDeclCrudHandler.asString(decl.get(FiscalDeclCrudHandler.PROPERTY_PERIOD));
    String excludeId = decl.getId() != null ? String.valueOf(decl.getId()) : null;
    String violation = checkCorrectiveConsistency(clientId, orgId, model, year, period, excludeId,
        effectivelyCorrective);
    if (violation != null) {
      owner.servlet.sendError(response, HttpServletResponse.SC_CONFLICT, violation + ": " + id);
      return true;
    }
    return false;
  }

  /**
   * {@code true} when AT LEAST ONE declaration sharing the given natural key — OTHER than
   * {@code excludeId}, if given — is currently in {@code SUBMITTED_STATUSES} AND is itself a
   * genuine ordinaria (not rectificativa/sustitutiva). Used by {@link
   * #checkCorrectiveConsistency}, shared by every one of its callers (PUT save/present, both
   * models' {@code generate}, the AEAT {@code submit}, and CREATE).
   */
  private boolean hasPresentedOrdinariaSibling(String excludeId, String clientId, String orgId,
      String model, long year, String period) {
    OBQuery<BaseOBObject> query =
        FiscalDeclCrudHandler.naturalKeyQuery(clientId, orgId, model, year, period);
    for (BaseOBObject existing : query.list()) {
      if (isPresentedOrdinariaSibling(existing, excludeId)) {
        return true;
      }
    }
    return false;
  }

  /**
   * ETP-5725 (reopened QA review, Sonar java:S135) — the single-condition body of {@link
   * #hasPresentedOrdinariaSibling}'s loop, extracted so that loop has zero {@code break}/{@code
   * continue} statements instead of the two {@code continue}s Sonar flagged (more than one
   * {@code break}/{@code continue} per loop). Same checks, same order, same result.
   */
  private boolean isPresentedOrdinariaSibling(BaseOBObject existing, String excludeId) {
    if (excludeId != null && excludeId.equals(idOf(existing))) {
      return false;
    }
    String status = FiscalDeclCrudHandler.asString(
        existing.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS));
    if (!FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(status)) {
      return false;
    }
    JSONObject persistedIdent = FiscalDeclCrudHandler.parseManualData(existing)
        .optJSONObject(FiscalDeclCrudHandler.IDENTIFICATION_KEY);
    return !identFlagTrue(persistedIdent, FiscalDeclCrudHandler.RECTIFICATIVA_KEY)
        && !identFlagTrue(persistedIdent, FiscalDeclCrudHandler.SUSTITUTIVA_KEY);
  }

  /** {@code candidate}'s id as a {@code String}, or {@code null} when it has none yet. */
  private static String idOf(BaseOBObject candidate) {
    return candidate.getId() != null ? String.valueOf(candidate.getId()) : null;
  }

  /**
   * Resolves whether {@code decl} is EFFECTIVELY marked as a correction (303 {@code
   * rectificativa} / 349 {@code sustitutiva}) for the purposes of {@link
   * #rejectCorrectiveConsistencyViolation} — the incoming PUT body's own {@code
   * manualData.identification}, when the caller sent one in THIS same request, takes precedence
   * over the already-persisted value; otherwise falls back to what is already stored on {@code
   * decl}. In the real flow ({@code FmModel303Page.jsx}/{@code FmModel349Page.jsx}'s {@code
   * persistEditableFields}) the identification is flushed in its OWN prior PUT before the
   * status-changing one reaches here, so the persisted branch is what normally fires — the body
   * branch exists so a single combined PUT (status + manualData together) is equally covered.
   *
   * <p>Package-private (not {@code private}): also called from {@link
   * FiscalDeclReactivationSupport#rejectReactivationWithSibling} to resolve the TYPE of the
   * declaration being reactivated.
   */
  boolean isEffectivelyCorrective(BaseOBObject decl, JSONObject body) {
    JSONObject bodyIdent = owner.extractIdentification(body);
    JSONObject ident = bodyIdent != null ? bodyIdent
        : FiscalDeclCrudHandler.parseManualData(decl).optJSONObject(FiscalDeclCrudHandler.IDENTIFICATION_KEY);
    return identFlagTrue(ident, FiscalDeclCrudHandler.RECTIFICATIVA_KEY)
        || identFlagTrue(ident, FiscalDeclCrudHandler.SUSTITUTIVA_KEY);
  }

  /**
   * Reads a boolean-ish identification flag (303's {@code rectificativa} is a real boolean;
   * 349's {@code sustitutiva} has been observed as either a boolean {@code true} or the string
   * {@code "Y"} — mirrors the exact two-shape check {@code FmListPage.jsx}'s {@code
   * resolveDeclTypeLabel} already applies on the frontend for the same field).
   *
   * <p>Package-private (not {@code private}): also called from {@link
   * FiscalDeclReactivationSupport#findReactivationBlockReason}.
   */
  static boolean identFlagTrue(JSONObject ident, String key) {
    if (ident == null || !ident.has(key) || ident.isNull(key)) {
      return false;
    }
    Object value = ident.opt(key);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      return "true".equalsIgnoreCase((String) value) || "Y".equalsIgnoreCase((String) value);
    }
    return false;
  }
}
