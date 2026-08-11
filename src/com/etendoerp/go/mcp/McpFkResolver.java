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

package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * Resolves FK-by-name values in a {@code neo_create}/{@code neo_update} body (IMP-4).
 * <p>
 * Historically every foreign-key field required the exact 32-char record id, forcing an agent to
 * call {@code neo_selectors} first even for an obvious single-match lookup (e.g.
 * {@code businessPartner: "Acme Corp"}). This resolves such human search strings server-side via
 * the same {@link NeoSelectorService#querySelectorByColumn} path {@code neo_selectors} uses,
 * leaving an already-valid id untouched.
 * <p>
 * <b>Id-first (ETP-4793 / IMP-15).</b> "Already-valid id" cannot be decided by shape alone: every
 * Etendo {@code _ID} column is a {@code VARCHAR}, and legacy master data (currency, UOM, document
 * type, tax rate) still carries short numeric ids such as {@code "102"} for EUR. Matching only the
 * 32-char hex form sent those values down the name path, where no record is literally *named*
 * {@code "102"}, so the very id {@code neo_defaults} had just returned came back as a 422. Each
 * candidate value is therefore probed as a record id of the target entity first, and only falls
 * through to the selector lookup when no record carries it. The residual ambiguity — a display name
 * that happens to equal some record's id — resolves to that record, which is what the caller meant.
 * <p>
 * <b>Context-dependent selectors (ETP-4793 / IMP-22).</b> Some selectors only have a candidate set
 * relative to a sibling field: {@code partnerAddress} lists the locations <i>of a given</i>
 * {@code businessPartner}, a tax rate depends on {@code orderDate} and {@code priceList}. This class
 * used to run with context built from {@code adTab} alone, so those selectors saw the unfiltered set
 * or none at all — {@code neo_create} rejected the byte-identical {@code $_identifier} that
 * {@code neo_selectors} with a {@code recordContext} had just returned. The body's own sibling fields
 * are now fed in as that context via {@code McpSelectorContextHelper#withBodyContext}.
 * <p>
 * That requires <b>dependency order</b>, which was the reason the earlier note gave for not doing it:
 * {@code partnerAddress} is only resolvable once {@code businessPartner} holds an id, and a body may
 * present them in any order — or both as display names. Rather than model the dependency graph, this
 * resolves in <b>repeated passes, deferring failures</b>: each pass rebuilds the context from the
 * fields settled so far and retries the rest, and a field's error is only returned once a whole pass
 * makes no progress. A graph is unnecessary because dependency order is discovered by trying — the
 * fields resolvable without sibling context settle first and become the context for the rest.
 * <p>
 * <b>Cost.</b> Worst case is O(n²) selector calls for {@code n} FK-by-name values in one body, and
 * only when the order is adversarial; typical bodies carry one to three and settle in one or two
 * passes. Values that are already ids never reach a selector at all.
 */
final class McpFkResolver {

  private McpFkResolver() {
  }

  private static final Pattern ID_PATTERN = Pattern.compile("[0-9A-Fa-f]{32}");
  private static final String KEY_ITEMS = "items";
  private static final String KEY_ID = "id";
  private static final String KEY_CANDIDATES = "candidates";
  private static final String KEY_FIELD = "field";
  private static final int SELECTOR_LIMIT = 10;

  /** @return {@code true} when {@code value} already looks like a 32-char hex Etendo id. */
  static boolean looksLikeId(String value) {
    return value != null && ID_PATTERN.matcher(value).matches();
  }

  /** The three outcomes of resolving a search string against a selector's match count. */
  enum Outcome {
    NOT_FOUND, RESOLVED, AMBIGUOUS
  }

  /** Pure decision: how many selector matches map to which outcome (DAL-free, unit-testable). */
  static Outcome decideOutcome(int matchCount) {
    if (matchCount == 0) {
      return Outcome.NOT_FOUND;
    }
    return matchCount == 1 ? Outcome.RESOLVED : Outcome.AMBIGUOUS;
  }

  /**
   * Resolves every FK-by-name value in {@code body}, replacing it in place with the matched
   * record id. Values that are not FK fields, not strings, empty, or already an id are left
   * untouched.
   *
   * @param body          the create/update body, keyed by canonical DAL property name (already
   *                      passed through {@code mapFieldsToDalProperties})
   * @param dalEntity     the DAL entity backing the tab's table
   * @param adTab         the tab, used to resolve each FK's {@code AD_Column}
   * @param contextParams selector context params (see the class-level limitation note)
   * @param log           caller's logger, for warn/debug tracing
   * @return {@code null} on success (body updated in place, possibly unchanged), or a structured
   *         {@code not_found}/{@code ambiguous_fk} error object the caller must return as-is
   */
  static JSONObject resolveFkNames(JSONObject body, Entity dalEntity, Tab adTab,
      Map<String, String> contextParams, Logger log) throws JSONException {
    return resolveFkNames(body, dalEntity, adTab, contextParams, log, value -> false);
  }

  /**
   * Same as {@link #resolveFkNames(JSONObject, Entity, Tab, Map, Logger)}, but skips values the
   * caller knows are not resolvable yet.
   * <p>
   * Added for {@code neo_batch} (IMP-15): a batch body may carry {@code "$ref:<opId>"} placeholders
   * that {@code BatchService} substitutes with a real recordId only once the referenced op has run.
   * Sending those to the selector would report a spurious {@code not_found} for a value that is
   * about to become a valid id.
   *
   * @param skipValue predicate over the raw string value; {@code true} leaves it untouched
   */
  static JSONObject resolveFkNames(JSONObject body, Entity dalEntity, Tab adTab,
      Map<String, String> contextParams, Logger log, Predicate<String> skipValue)
      throws JSONException {
    if (body == null || dalEntity == null) {
      return null;
    }
    List<String> keys = new ArrayList<>();
    Iterator<String> it = body.keys();
    while (it.hasNext()) {
      keys.add(it.next());
    }

    // Pass 0 — the cheap checks only, so that every field already holding an id is available as
    // context before the first selector call, and never costs one.
    Set<String> pending = new LinkedHashSet<>();
    Set<String> unusableAsContext = new LinkedHashSet<>();
    for (String key : keys) {
      classify(body, dalEntity, log, key, skipValue, pending, unusableAsContext);
    }
    return resolveByDependencyOrder(body, dalEntity, adTab, contextParams, log, pending,
        unusableAsContext);
  }

  /**
   * Sorts one body key into "needs a selector lookup" or "cannot be used as selector context",
   * using only checks that cost no selector call.
   * <p>
   * A key lands in neither set when its value is already a usable record id — that is the case the
   * IMP-22 context synthesis depends on, and it is the common one: an agent that resolved
   * {@code businessPartner} via {@code neo_selectors} sends the id, and {@code partnerAddress} in the
   * same body then resolves against it on the very first pass.
   */
  private static void classify(JSONObject body, Entity dalEntity, Logger log, String key,
      Predicate<String> skipValue, Set<String> pending, Set<String> unusableAsContext) {
    Property prop = dalEntity.getProperty(key, false);
    if (prop == null || prop.isPrimitive() || prop.getTargetEntity() == null) {
      return;
    }
    Object rawValue = body.opt(key);
    if (!(rawValue instanceof String)) {
      return;
    }
    String search = (String) rawValue;
    // An empty value carries no context, and a "$ref:" placeholder is a batch id that does not exist
    // yet — copying either into a selector param would narrow the candidate set to nothing.
    if (search.isEmpty() || skipValue.test(search)) {
      unusableAsContext.add(key);
      return;
    }
    // Order matters: the cheap shape check first (no DAL hit), then the id probe. See the class
    // javadoc for why the shape check alone is not enough.
    if (looksLikeId(search) || existsAsRecordId(prop, search, log)) {
      return;
    }
    pending.add(key);
  }

  /**
   * Resolves the pending FK names in repeated passes, rebuilding the selector context from the
   * fields settled so far and returning a field's error only once a whole pass makes no progress.
   * <p>
   * "No progress" is the termination condition rather than a pass counter because it is the same
   * condition that makes an error trustworthy: if nothing else in the body moved, no additional
   * context is coming, so the {@code not_found} this field reports is final rather than an artefact
   * of resolution order. Reporting it on the first pass — the pre-IMP-22 behaviour — is what turned a
   * resolvable {@code partnerAddress} into a 422.
   */
  private static JSONObject resolveByDependencyOrder(JSONObject body, Entity dalEntity, Tab adTab,
      Map<String, String> contextParams, Logger log, Set<String> pending,
      Set<String> unusableAsContext) throws JSONException {
    while (!pending.isEmpty()) {
      Set<String> excluded = new LinkedHashSet<>(pending);
      excluded.addAll(unusableAsContext);
      Map<String, String> passContext =
          McpSelectorContextHelper.withBodyContext(contextParams, body, excluded);

      boolean progressed = false;
      JSONObject firstError = null;
      for (String key : new ArrayList<>(pending)) {
        JSONObject error = lookupOneField(body, dalEntity, adTab, passContext, log, key,
            unusableAsContext);
        if (error == null) {
          pending.remove(key);
          progressed = true;
        } else if (firstError == null) {
          firstError = error;
        }
      }
      if (!progressed) {
        return firstError;
      }
    }
    return null;
  }

  /**
   * One selector lookup for one field, against the context of the current pass.
   *
   * @return {@code null} when the field is settled — either resolved to an id, or abandoned because
   *         nothing can be done with it (no {@code AD_Column}, selector unavailable), in which case
   *         it is recorded as unusable context since its value is still a search string
   */
  private static JSONObject lookupOneField(JSONObject body, Entity dalEntity, Tab adTab,
      Map<String, String> contextParams, Logger log, String key, Set<String> unusableAsContext)
      throws JSONException {
    String search = body.optString(key);
    Column column = McpSchemaFieldBuilder.findColumn(adTab, key, dalEntity);
    if (column == null) {
      log.debug("FK-by-name: no AD_Column resolved for '{}', leaving value as-is", key);
      unusableAsContext.add(key);
      return null;
    }

    NeoResponse selectorResponse = NeoSelectorService.querySelectorByColumn(
        column, key, search, SELECTOR_LIMIT, 0, contextParams);
    if (selectorResponse.getHttpStatus() >= 400 || selectorResponse.getBody() == null) {
      log.warn("FK-by-name: selector lookup failed for '{}'='{}' (status {}), leaving value as-is",
          key, search, selectorResponse.getHttpStatus());
      unusableAsContext.add(key);
      return null;
    }

    JSONArray items = selectorResponse.getBody().optJSONArray(KEY_ITEMS);
    int matchCount = items == null ? 0 : items.length();
    switch (decideOutcome(matchCount)) {
      case NOT_FOUND:
        return buildNotFoundError(key, search);
      case AMBIGUOUS:
        return buildAmbiguousError(key, search, items);
      case RESOLVED:
      default:
        body.put(key, items.getJSONObject(0).optString(KEY_ID));
        return null;
    }
  }

  /**
   * Probe {@code value} as a record id of the FK's target entity (IMP-15).
   * <p>
   * Uses {@code OBDal#get(String, Object)} rather than {@code exists}, so the tenant's read-access
   * rules still apply: an id in a table this role cannot read reports {@code false} and falls
   * through to the name path, exactly as before this probe existed. Any exception (unreadable
   * entity, id type the target's PK cannot hold) is deliberately swallowed to the same effect —
   * the worst case of this method is the pre-IMP-15 behaviour, never a new failure mode.
   *
   * @return {@code true} when a record with that id exists and is readable
   */
  private static boolean existsAsRecordId(Property prop, String value, Logger log) {
    String targetEntity = prop.getTargetEntity().getName();
    try {
      return OBDal.getInstance().get(targetEntity, value) != null;
    } catch (Exception e) {
      log.debug("FK id probe for '{}' on {} failed, falling back to the name path: {}", value,
          targetEntity, e.getMessage());
      return false;
    }
  }

  private static JSONObject buildNotFoundError(String field, String search) throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_NOT_FOUND);
    // IMP-15: never say "pass the exact record id instead" — the id path already ran and found
    // nothing, so that advice was emitted to agents that had passed a real (legacy numeric) id.
    error.put(McpConstants.KEY_DETAIL,
        "No match for '" + field + "'='" + search + "': it is neither the id of an existing record "
            + "nor a value any selector matched. Use neo_selectors to find a valid one.");
    error.put(KEY_FIELD, field);
    return error;
  }

  private static JSONObject buildAmbiguousError(String field, String search, JSONArray items)
      throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_AMBIGUOUS_FK);
    error.put(McpConstants.KEY_DETAIL,
        "'" + field + "'='" + search + "' matched " + items.length() + " records. Pick one of "
            + "the candidates' ids, or narrow the search text.");
    error.put(KEY_FIELD, field);
    error.put(KEY_CANDIDATES, items);
    return error;
  }
}
