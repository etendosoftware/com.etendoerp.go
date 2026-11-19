/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Asks the entity's own SELECTOR customization whether it offers a value (ETP-6377).
 *
 * <p>The app's selector for a field is not always the generic one. An entity can serve a field's
 * candidate list from its {@link NeoHandler} on the {@link NeoExtensionSurface#SELECTOR} surface —
 * the payment method of the document headers and of contacts does, and lists every active method
 * of the document's direction whether or not a financial account uses it (ETP-5238). The write
 * check of ETP-6176 asked the generic selector only, so on a tenant with no financial account it
 * refused every payment method the dropdown had just offered.
 *
 * <p>This class runs the same hook chain the REST {@code GET .../selector/{field}} runs
 * ({@code NeoHookDispatcher.executeHookChain}): the pre-hook, and when it answers, the post-hook on
 * its answer. The customization reads its paging and direction from the current HTTP request, so
 * for the duration of the call the request is replaced by a synthetic one carrying the merged
 * record as parameters plus the page asked for, and then put back.
 *
 * <p>Only a pre-hook that produces the candidate list decides. When the customization declines
 * the field (returns {@code null}) the generic selector stays the judge, as before.
 */
final class NeoCustomizedSelectorCheck {

  /** What the customization said about the value. */
  enum Answer {
    /** The customization listed the value. */
    OFFERED,
    /** The customization served the whole list and the value was not in it. */
    NOT_OFFERED,
    /** The customization answered with an error, or its list could not be read to the end. */
    UNVERIFIABLE,
    /** No customization serves this field: the generic selector decides. */
    NOT_CUSTOMIZED
  }

  static final int PAGE_SIZE = 1000;
  static final int MAX_PAGES = 20;
  private static final String PARAM_LIMIT = "limit";
  private static final String PARAM_OFFSET = "offset";
  private static final String ITEMS = "items";
  private static final String HAS_MORE = "hasMore";

  private NeoCustomizedSelectorCheck() {
  }

  /**
   * @param sfEntity  the Schema Forge entity written; {@code null} means no customization
   * @param fieldName the DAL property name of the reference, as the selector endpoint names it
   * @param valueId   the id the write sets
   * @param context   the merged record the generic selector also receives
   * @return the customization's answer, or {@link Answer#NOT_CUSTOMIZED} when it has none
   */
  static Answer offers(SFEntity sfEntity, String fieldName, String valueId,
      Map<String, String> context) {
    if (sfEntity == null || sfEntity.getETGOSFSpec() == null || StringUtils.isBlank(fieldName)
        || StringUtils.isBlank(valueId)) {
      return Answer.NOT_CUSTOMIZED;
    }
    RequestContext requestContext = RequestContext.get();
    HttpServletRequest previousRequest = requestContext.getRequest();
    VariablesSecureApp previousVars = previousRequest == null ? null
        : requestContext.getVariablesSecureApp();
    try {
      return readPages(requestContext, sfEntity, fieldName, valueId, context);
    } finally {
      requestContext.setRequest(previousRequest);
      if (previousVars != null) {
        requestContext.setVariableSecureApp(previousVars);
      }
    }
  }

  private static Answer readPages(RequestContext requestContext, SFEntity sfEntity,
      String fieldName, String valueId, Map<String, String> context) {
    for (int page = 0; page < MAX_PAGES; page++) {
      Map<String, String> params = pageParams(context, page * PAGE_SIZE);
      requestContext.setRequest(new SyntheticHttpServletRequest(asArrays(params), new HashMap<>()));
      NeoResponse response = dispatch(sfEntity, fieldName, params);
      if (response == null) {
        return page == 0 ? Answer.NOT_CUSTOMIZED : Answer.UNVERIFIABLE;
      }
      if (response.getHttpStatus() >= 400 || response.getBody() == null) {
        return Answer.UNVERIFIABLE;
      }
      if (lists(response.getBody(), valueId)) {
        return Answer.OFFERED;
      }
      if (!response.getBody().optBoolean(HAS_MORE, false)) {
        return Answer.NOT_OFFERED;
      }
    }
    return Answer.UNVERIFIABLE;
  }

  /** The pre-hook's candidate list, refined by the post-hook; {@code null} when none is served. */
  private static NeoResponse dispatch(SFEntity sfEntity, String fieldName,
      Map<String, String> params) {
    NeoContext hookContext = NeoContext.builder()
        .specName(sfEntity.getETGOSFSpec().getName())
        .entityName(sfEntity.getName())
        .httpMethod("GET")
        .adTab(sfEntity.getADTab())
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName(fieldName)
        .queryParams(params)
        .build();
    NeoExtensionRequest request = NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(sfEntity.getETGOSFSpec().getName())
        .entityName(sfEntity.getName())
        .surface(NeoExtensionSurface.SELECTOR)
        .channel(NeoExtensionChannel.REST_SINGLE)
        .context(hookContext)
        .build();
    NeoExtensionResult pre = NeoExtensionDispatcher.dispatch(request);
    if (pre.customization() == null || pre.response() == null) {
      return null;
    }
    NeoExtensionResult post = NeoExtensionDispatcher.dispatch(
        request.post(pre.customization()).withPreviousResult(pre.response()));
    return post.response() != null ? post.response() : pre.response();
  }

  private static Map<String, String> pageParams(Map<String, String> context, int offset) {
    Map<String, String> params = new HashMap<>();
    if (context != null) {
      params.putAll(context);
    }
    params.put(PARAM_LIMIT, String.valueOf(PAGE_SIZE));
    params.put(PARAM_OFFSET, String.valueOf(offset));
    return params;
  }

  private static Map<String, String[]> asArrays(Map<String, String> params) {
    Map<String, String[]> arrays = new HashMap<>();
    params.forEach((key, value) -> arrays.put(key, new String[] { value }));
    return arrays;
  }

  private static boolean lists(JSONObject body, String valueId) {
    JSONArray items = body.optJSONArray(ITEMS);
    int count = items == null ? 0 : items.length();
    for (int i = 0; i < count; i++) {
      JSONObject item = items.optJSONObject(i);
      if (item != null && valueId.equals(item.optString("id", null))) {
        return true;
      }
    }
    return false;
  }
}
