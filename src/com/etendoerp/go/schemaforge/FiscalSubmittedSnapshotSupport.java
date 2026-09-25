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

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;

/**
 * ETP-5438 submission snapshot handling for {@code ETGO_Fiscal_Decl}
 * ({@link FiscalDeclCrudHandler#PROPERTY_SUBMITTED_SNAPSHOT}): taking it when a declaration
 * enters the submitted family, clearing it on reactivation, validating it against the column,
 * reading it back, and looking up the latest one for a natural key. Extracted from
 * {@link FiscalDeclCrudHandler} to keep that class under the SonarQube {@code java:S1448}
 * method-count threshold, following the module's existing {@code *Support} pattern
 * ({@link Fiscal303SourcesSupport}, {@link Fiscal349ViesSupport}).
 */
class FiscalSubmittedSnapshotSupport {

  private static final Logger log = LogManager.getLogger(FiscalSubmittedSnapshotSupport.class);

  /**
   * Computes the submission snapshot for a declaration — the figures of the model's read
   * endpoint ({@code /fiscal303/boxes}, {@code /fiscal349/operators}) without per-invoice rows
   * ({@link AbstractFiscalHandler#computeSubmittedSnapshot}). Wired by
   * {@link AbstractFiscalHandler#linkSubmittedSnapshotProviders}, which dispatches on
   * {@code model} to the matching fiscal handler: the declaration table has no access to the
   * per-model compute code itself.
   */
  @FunctionalInterface
  interface SnapshotProvider {
    /**
     * Computes the snapshot for one declaration's natural key.
     *
     * @param model  the declaration's {@code ETGO_Fiscal_Decl.model} code ({@code "303"},
     *               {@code "349"}, ...)
     * @param year   the declaration's fiscal year
     * @param period the declaration's period code ({@code "T1"}, {@code "01"}, ...)
     * @return the snapshot payload, or {@code null} when {@code model} has no snapshot support
     *         (a model other than 303/349) — the transition then proceeds without one.
     * @throws Exception when the computation fails; the submission is rejected in that case.
     */
    @SuppressWarnings("java:S112")
    JSONObject compute(String model, int year, String period) throws Exception;
  }

  private final FiscalDeclCrudHandler declarations;
  private final NeoServlet servlet;
  private SnapshotProvider provider;

  FiscalSubmittedSnapshotSupport(FiscalDeclCrudHandler declarations, NeoServlet servlet) {
    this.declarations = declarations;
    this.servlet = servlet;
  }

  /** See {@link SnapshotProvider}. {@code null} (the default) disables snapshots. */
  void setProvider(SnapshotProvider provider) {
    this.provider = provider;
  }

  /**
   * Keeps {@link FiscalDeclCrudHandler#PROPERTY_SUBMITTED_SNAPSHOT} in step with a status
   * transition:
   * <ul>
   *   <li>INTO {@link FiscalDeclCrudHandler#SUBMITTED_STATUSES} from a non-submitted status
   *       (first presentation, manual Registrar/Presentar on either model): takes the snapshot
   *       via {@link #takeSubmittedSnapshot}. When it cannot be computed the whole request is
   *       rejected with a 500 and nothing is written — a declaration is never presented without
   *       its snapshot.</li>
   *   <li>OUT of the submitted family ("Reactivar declaración" → {@code draft}): clears it, so a
   *       later re-presentation takes a fresh one.</li>
   * </ul>
   * A submitted → submitted transition never reaches this point
   * ({@code FiscalDeclCrudHandler#rejectRepresentation} already answered 409), and a
   * non-submitted → non-submitted one leaves the column untouched.
   *
   * @param previousStatus the declaration's status before this request ({@code null} on create)
   * @return {@code false} if the request was rejected (the error response was already sent)
   */
  boolean applyTransition(BaseOBObject decl, String previousStatus, String newStatus, String id,
      HttpServletResponse response) throws IOException {
    boolean wasSubmitted = previousStatus != null
        && FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(previousStatus);
    boolean willBeSubmitted = newStatus != null
        && FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(newStatus);
    if (wasSubmitted && !willBeSubmitted) {
      decl.set(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT, null);
      return true;
    }
    if (!willBeSubmitted || wasSubmitted) {
      return true;
    }
    try {
      takeSubmittedSnapshot(decl);
      return true;
    } catch (Exception e) {
      log.error("Could not compute the submission snapshot for declaration {}", id, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "The declaration could not be submitted: its figures could not be computed ("
              + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName())
              + ").");
      return false;
    }
  }

  /**
   * Computes the submission snapshot for {@code decl} through the wired {@link SnapshotProvider}
   * and stores it on the record (no commit — the caller's transaction persists it together with
   * the status change). A no-op when no provider is wired or the provider has no snapshot support
   * for the declaration's model.
   *
   * @throws Exception when the provider fails to compute it
   */
  @SuppressWarnings("java:S112")
  void takeSubmittedSnapshot(BaseOBObject decl) throws Exception {
    if (provider == null) {
      return;
    }
    JSONObject snapshot = provider.compute(
        FiscalDeclCrudHandler.asString(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL)),
        FiscalDeclCrudHandler.asInt(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_YEAR)),
        FiscalDeclCrudHandler.asString(decl.get(FiscalDeclCrudHandler.PROPERTY_PERIOD)));
    if (snapshot != null) {
      decl.set(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT, snapshot.toString());
    }
  }

  /**
   * The persisted submission snapshot of the MOST RECENT declaration (same "latest DECL_SEQ
   * wins" rule as {@link FiscalDeclCrudHandler#findLatestDeclarationStatus}) for the natural key,
   * or {@code null} when that declaration is not submitted, has no snapshot (a legacy declaration
   * presented before the column existed), or the stored value is not parseable JSON. Used by the
   * {@code boxes}/{@code operators} reads to serve a presented declaration from its snapshot
   * instead of recomputing it; {@code null} makes them fall back to the live compute.
   */
  JSONObject findLatestSubmittedSnapshot(String clientId, String orgId, String model, long year,
      String period) {
    BaseOBObject latest = declarations.findLatestDeclaration(clientId, orgId, model, year, period);
    if (latest == null || !FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(
        FiscalDeclCrudHandler.asString(latest.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)))) {
      return null;
    }
    return parseSubmittedSnapshot(latest);
  }

  /**
   * Validates {@code snapshot} against the declaration entity's own {@code submittedSnapshot}
   * property — the same check {@code BaseOBObject#set} runs (AD length, domain type), without
   * assigning anything (ETP-5438 QA BUG-1). Throws when the entity would reject it, including
   * when the property is missing from the runtime model (the column not deployed yet). A
   * declaration with no entity metadata (a unit-test double) has nothing to validate against.
   */
  static void validateSubmittedSnapshot(BaseOBObject decl, String snapshot) {
    Entity entity = decl.getEntity();
    if (entity == null) {
      return;
    }
    entity.getProperty(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT).checkIsValidValue(snapshot);
  }

  /**
   * Parses the stored snapshot JSON string, or {@code null} when it is blank or unparseable.
   * Unlike the manual-data parsing this never degrades to an empty object: {@code null} means
   * "no snapshot", which makes every reader fall back to the live compute — an empty object would
   * instead freeze the declaration on no figures at all.
   */
  static JSONObject parseSubmittedSnapshot(BaseOBObject decl) {
    String raw = FiscalDeclCrudHandler.asString(
        decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT));
    if (StringUtils.isBlank(raw)) {
      return null;
    }
    try {
      return new JSONObject(raw);
    } catch (JSONException e) {
      log.warn("Ignoring unparseable submitted snapshot on declaration {}", decl.getId());
      return null;
    }
  }
}
