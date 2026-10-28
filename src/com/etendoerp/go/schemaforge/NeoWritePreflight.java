/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.util.NeoValidationErrorResponseBuilder;

/**
 * The checks a REST CRUD write passes BEFORE any customization runs (ETP-5785).
 *
 * <p><b>Why before.</b> A customization's {@code handle()} runs ahead of the default CRUD step and
 * may persist on its own (e.g. {@code ContactsLocationAddressHandler} writes a
 * {@code C_BPartner_Location} under the partner named by {@code parentId}). A check placed in the
 * default step therefore never sees those writes. Every REST CRUD write reaches exactly one of the two
 * callers of this class, each before it dispatches to a customization:
 * <ul>
 *   <li>{@code NeoCrudHandler.dispatchCrudRequestInternal} — REST {@code POST}/{@code PUT}/
 *       {@code PATCH}/{@code DELETE} on {@code /sws/neo/{spec}/{entity}}, with or without a customization;</li>
 *   <li>{@code BatchService.createRecord} — each create of REST {@code /sws/neo/batch} and of MCP
 *       {@code etendo_batch}.</li>
 * </ul>
 * The default step itself no longer checks, so every write is checked exactly once. MCP
 * {@code etendo_create} / {@code etendo_update} persist through their own pipeline and run the same
 * reference check before their own customization dispatch.
 *
 * <p>This is the single pre-dispatch point for REST CRUD writes: a further write check that must
 * hold whatever the customization does belongs here.
 *
 * <p><b>What it checks today.</b>
 * <ul>
 *   <li>{@link NeoReferenceOwnership}: every reference the request sets and its {@code parentId}
 *       (body or query string) must be a row of the caller's client or of System.</li>
 *   <li>{@link NeoReferenceActiveness} (ETP-5802): every reference the request sets must not point
 *       at an inactive row. Checked only once ownership passes, and only on the same caller-only
 *       field sets — {@code parentId} is not re-checked for activeness, since a parent is addressed
 *       structurally, not set as an ordinary FK/TableDir value.</li>
 * </ul>
 * The body is read, never modified: API keys are resolved to DAL property names on a copy, and the
 * server-owned tenant fields, which every write path drops, are left out.
 */
final class NeoWritePreflight {

  private static final String PARAM_PARENT_ID = "parentId";
  private static final String DATA_WRAPPER = "data";

  private NeoWritePreflight() {
  }

  /**
   * The refusal for a write that must not reach any customization, or {@code null} to proceed.
   *
   * @param context the CRUD request context; non-write methods always proceed
   * @return the response to send instead of dispatching, or {@code null}
   */
  static NeoResponse refusalFor(NeoContext context) {
    NeoResponse targetRefusal = targetRecordRefusal(context);
    if (targetRefusal != null) {
      return targetRefusal;
    }
    if (context == null || !isCreateOrUpdate(context.getHttpMethod())
        || OBContext.getOBContext() == null) {
      // No session means no request to check (NeoReferenceOwnership fails open there too).
      return null;
    }
    Tab adTab = context.getAdTab();
    if (adTab == null || adTab.getTable() == null) {
      return null;
    }
    String bodyParent = bodyParentId(context.getRequestBody());
    String queryParent = queryParentId(context.getQueryParams());
    List<JSONObject> fieldSets = dalKeyedCopies(context, adTab);
    String refusal = ownershipRefusalFor(adTab, fieldSets, bodyParent, queryParent);
    if (refusal != null) {
      return toResponse(refusal);
    }
    return activenessRefusalFor(adTab, fieldSets);
  }

  /**
   * The refusal for a PUT/PATCH/DELETE targeting a record {@link NeoActionRecordGuard} blocks, or
   * {@code null} to proceed with the rest of the preflight.
   *
   * @param context the CRUD request context, possibly {@code null}
   * @return the response to send instead of dispatching, or {@code null}
   */
  private static NeoResponse targetRecordRefusal(NeoContext context) {
    if (context == null || !("PUT".equals(context.getHttpMethod())
        || "PATCH".equals(context.getHttpMethod()) || "DELETE".equals(context.getHttpMethod()))) {
      return null;
    }
    return NeoActionRecordGuard.writeRefusalFor(context.getSfEntity(), context.getRecordId());
  }

  /**
   * The {@link NeoReferenceOwnership} refusal message for the given field sets and parents, or
   * {@code null} when every reference the request sets belongs to the caller's client or System.
   *
   * @param adTab the AD tab the write targets
   * @param fieldSets the DAL-keyed copies of the request body, caller-only fields
   * @param bodyParent the {@code parentId} read from the request body, or {@code null}
   * @param queryParent the {@code parentId} read from the query string, or {@code null}
   * @return the ownership refusal message, or {@code null} to proceed
   */
  private static String ownershipRefusalFor(Tab adTab, List<JSONObject> fieldSets,
      String bodyParent, String queryParent) {
    String refusal = NeoReferenceOwnership.refuseUnownedReferences(adTab, fieldSets.get(0),
        bodyParent != null ? bodyParent : queryParent);
    for (int i = 1; refusal == null && i < fieldSets.size(); i++) {
      refusal = NeoReferenceOwnership.refuseUnownedReferences(adTab, fieldSets.get(i), null);
    }
    if (refusal == null && bodyParent != null && queryParent != null
        && !Objects.equals(bodyParent, queryParent)) {
      // A customization may read either; both must be the caller's.
      refusal = NeoReferenceOwnership.refuseUnownedReferences(adTab, null, queryParent);
    }
    return refusal;
  }

  /**
   * The {@link NeoReferenceActiveness} refusal (ETP-5802) for the first field set whose reference
   * points at an inactive row, or {@code null} when none does.
   *
   * <p>Checked on the same DAL-keyed, caller-only field sets as ownership above — an update that
   * never touches an already-inactive reference elsewhere on the record is not re-validated (CP-6).
   *
   * @param adTab the AD tab the write targets
   * @param fieldSets the DAL-keyed copies of the request body, caller-only fields
   * @return the response to send instead of dispatching, or {@code null} to proceed
   */
  private static NeoResponse activenessRefusalFor(Tab adTab, List<JSONObject> fieldSets) {
    for (JSONObject fieldSet : fieldSets) {
      NeoReferenceActiveness.InactiveReference inactive =
          NeoReferenceActiveness.findInactiveReference(adTab, fieldSet);
      if (inactive != null) {
        return NeoInactiveReferenceResponse.build(inactive);
      }
    }
    return null;
  }

  private static boolean isCreateOrUpdate(String method) {
    return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
  }

  /**
   * The request body keyed by DAL property name, as the field filter would remap it, without
   * touching the request: the customization and the default step still get the body as sent.
   *
   * <p>Normally one object. When the body carries a field under both its API key and its DAL name,
   * the second value goes into an extra object so that both are checked — the default step keeps
   * one of them, but a customization may read either.
   */
  private static List<JSONObject> dalKeyedCopies(NeoContext context, Tab adTab) {
    List<JSONObject> copies = new ArrayList<>();
    copies.add(new JSONObject());
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return copies;
    }
    JSONObject data = body.optJSONObject(DATA_WRAPPER);
    JSONObject source = data != null ? data : body;
    NeoFieldFilter filter = NeoFieldFilter.forEntity(context.getSfEntity(),
        adTab.getTable().getName());
    Iterator<?> keys = source.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (PARAM_PARENT_ID.equals(key) || NeoServerOwnedFields.isServerOwnedKey(key)) {
        continue;
      }
      putInFirstFree(copies, filter.resolveWritablePropName(key), source.opt(key));
    }
    return copies;
  }

  private static void putInFirstFree(List<JSONObject> copies, String propertyName, Object value) {
    JSONObject target = null;
    for (JSONObject copy : copies) {
      if (!copy.has(propertyName)) {
        target = copy;
        break;
      }
    }
    if (target == null) {
      target = new JSONObject();
      copies.add(target);
    }
    try {
      target.put(propertyName, value);
    } catch (JSONException e) {
      throw new IllegalStateException("Could not copy the request body", e);
    }
  }

  private static String bodyParentId(JSONObject body) {
    if (body == null) {
      return null;
    }
    String value = body.optString(PARAM_PARENT_ID, null);
    return StringUtils.isBlank(value) ? null : value;
  }

  private static String queryParentId(Map<String, String> queryParams) {
    String value = queryParams == null ? null : queryParams.get(PARAM_PARENT_ID);
    return StringUtils.isBlank(value) ? null : value;
  }

  /**
   * The same answer the default step gives when core reports the unknown reference itself: the
   * validation-error body through {@link NeoValidationErrorResponseBuilder}, a 400.
   */
  private static NeoResponse toResponse(String refusal) {
    try {
      return NeoValidationErrorResponseBuilder.build(
          new JSONObject(refusal).getJSONObject(JsonConstants.RESPONSE_RESPONSE));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not read the refusal body", e);
    }
  }
}
