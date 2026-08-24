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

import java.util.Set;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.plm.ProductCategory;

/**
 * NeoHandler for the Product Category header entity.
 *
 * <p>ETP-4670: only one {@link ProductCategory} can be marked as the client's default category.
 * Unlike the analogous Price List "default" guard (see {@link PriceListHeaderHandler}), which
 * only blocks <em>deactivating</em> an existing default, this handler BLOCKS the save outright
 * when the incoming request tries to mark a category as default while another category of the
 * same client already holds that flag — the old default is never silently unmarked.
 *
 * <p><b>Scope is per-client, not per-organization</b> (QA finding): categories can live in a
 * "real" organization or in the wildcard organization {@code '0'} (org {@code *}), which is
 * visible/inherited across every organization of the same client. A per-organization check would
 * miss the conflict between a category in a real org and one in the wildcard org even though both
 * are visibly "the default" to the end user within that client — reproduced with "Otros" (real
 * org) vs. "Bebidas" (wildcard org {@code 0}), same client. Scoping by client instead of
 * organization sidesteps the org-hierarchy question entirely and covers exactly that case.
 *
 * <p>Living at the NEO Headless layer (rather than as an {@code EntityPersistenceEventObserver})
 * ensures this GO-specific behavior does not affect Etendo Classic / Enterprise users that
 * operate directly on the AD windows.
 *
 * <p>ETP-4967: also hides any category flagged {@code em_etgo_issystemcategory = 'Y'} from GET
 * responses — see {@link #afterHandle}.
 */
@Named("productCategoryDefaultHandler")
public class ProductCategoryDefaultHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProductCategoryDefaultHandler.class);

  private static final String FIELD_DEFAULT = "default";
  private static final String FIELD_ID = "id";
  private static final String MSG_CANNOT_SET_MULTIPLE_DEFAULT =
      "ETGO_ProductCategoryCannotSetMultipleDefault";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_GET = "GET";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!METHOD_POST.equals(method) && !METHOD_PATCH.equals(method) && !METHOD_PUT.equals(method)) {
      return null;
    }
    if (!isSettingDefault(context.getRequestBody())) {
      return null;
    }
    return blockConflictingDefault(context);
  }

  /**
   * Detects whether the incoming request body explicitly turns {@code default} on.
   */
  private static boolean isSettingDefault(JSONObject body) {
    if (body == null || !body.has(FIELD_DEFAULT)) {
      return false;
    }
    Object value = body.opt(FIELD_DEFAULT);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      return "true".equalsIgnoreCase((String) value) || "Y".equalsIgnoreCase((String) value);
    }
    return false;
  }

  /**
   * Blocks the request when another {@link ProductCategory} of the same client is already marked
   * as default. The current record (on update) is excluded from the conflict check so a no-op
   * re-save of the existing default is allowed through.
   */
  private NeoResponse blockConflictingDefault(NeoContext context) {
    String recordId = context.getRecordId();
    String clientId = resolveClientId(context, recordId);
    if (clientId == null || clientId.isEmpty()) {
      return null;
    }

    OBCriteria<ProductCategory> crit = OBDal.getInstance().createCriteria(ProductCategory.class);
    crit.add(Restrictions.eq(ProductCategory.PROPERTY_CLIENT + ".id", clientId));
    crit.add(Restrictions.eq(ProductCategory.PROPERTY_DEFAULT, true));
    if (recordId != null && !recordId.isEmpty()) {
      crit.add(Restrictions.ne(ProductCategory.PROPERTY_ID, recordId));
    }
    crit.setMaxResults(1);

    if (!crit.list().isEmpty()) {
      // messageBD reads the AD_Message base text (English) — same catalog pattern as
      // PriceListHeaderHandler. Translation to the active locale happens on the FRONTEND, not
      // here: tools/app-shell/src/lib/backendErrors.js maps this exact English string to an
      // i18n key. That match is exact-string (see BACKEND_ERROR_MAP), so the AD_Message MSGTEXT
      // for ETGO_ProductCategoryCannotSetMultipleDefault must stay English and stay
      // byte-for-byte identical to the map's key — do NOT add an AD_Message_Trl es_ES row for
      // this message, it would silently break the frontend match.
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          OBMessageUtils.messageBD(MSG_CANNOT_SET_MULTIPLE_DEFAULT));
    }
    return null;
  }

  /**
   * Resolves the client to scope the conflict check to: the existing record's client on update,
   * or the current request's client context on create (where there is no existing record yet).
   */
  private static String resolveClientId(NeoContext context, String recordId) {
    if (recordId != null && !recordId.isEmpty()) {
      ProductCategory existing = OBDal.getInstance().get(ProductCategory.class, recordId);
      if (existing != null && existing.getClient() != null) {
        return existing.getClient().getId();
      }
      return null;
    }
    if (context.getObContext() != null && context.getObContext().getCurrentClient() != null) {
      return context.getObContext().getCurrentClient().getId();
    }
    return null;
  }

  /**
   * ETP-4967: strips categories flagged {@code em_etgo_issystemcategory = 'Y'} (see
   * {@link SystemCategoryIds}) from GET responses (list and single-record alike —
   * {@code response.data} has the same shape either way) before they reach the UI, so an
   * internal category like "Discounts" never shows up in the "Categoría del producto" window.
   * Mirrors {@link DiscountLineFilter#filterFromResponse}'s exact shape.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!METHOD_GET.equals(context.getHttpMethod())) {
      return null;
    }
    NeoResponse previousResult = context.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previousResult.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper == null) {
        return null;
      }
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      String clientId = resolveContextClientId(context);
      if (clientId == null || clientId.isEmpty()) {
        return null;
      }
      Set<String> hiddenIds = SystemCategoryIds.resolve(clientId);
      if (hiddenIds.isEmpty()) {
        return null;
      }
      JSONArray filtered = new JSONArray();
      boolean removed = false;
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject row = dataArr.optJSONObject(i);
        if (row == null) {
          continue;
        }
        if (hiddenIds.contains(row.optString(FIELD_ID, ""))) {
          removed = true;
        } else {
          filtered.put(row);
        }
      }
      if (!removed) {
        return null;
      }
      responseWrapper.put("data", filtered);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("Could not filter hidden product categories from GET response: {}", e.getMessage());
      return null;
    }
  }

  /**
   * The current request's client — same resolution {@link #resolveClientId} uses for the create
   * case, extracted here so {@code afterHandle} does not need an existing record id.
   */
  private static String resolveContextClientId(NeoContext context) {
    if (context.getObContext() != null && context.getObContext().getCurrentClient() != null) {
      return context.getObContext().getCurrentClient().getId();
    }
    return null;
  }
}
