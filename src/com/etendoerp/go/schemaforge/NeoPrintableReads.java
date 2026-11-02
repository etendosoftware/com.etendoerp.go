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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * The NEO reads a printable's data builder makes, served by the very pipeline a REST GET goes
 * through (ETP-6013).
 *
 * <p>The browser's data builders ({@code buildInvoiceData} & co.) read
 * {@code GET /sws/neo/<spec>/<entity>/<id>} and {@code GET .../<entity>?parentId=<id>}. To print the
 * same document the server must see the same JSON — the same property names, the same
 * {@code $_identifier}s, the same READ-customization enrichments ({@code afterHandle}) and the same
 * field filtering. So instead of re-reading the records through DAL getters, which would be a second
 * mapping to keep in step, it dispatches a GET {@link NeoContext} through
 * {@link NeoServletSupport#handleWithHooks} with a servlet-less {@link NeoCrudHandler} — the batch
 * path's arrangement ({@code BatchService}) — and unwraps the answer the way {@code pdfUtils.js}
 * does ({@code fetchJson} / {@code fetchAll}).</p>
 *
 * <p>Runs in the caller's {@link OBContext} (its user, role, client and organization: the user
 * printing is the user who could open the preview) and, like {@code NeoServlet.service} runs every
 * NEO request, in admin mode for the whole read — the {@code ETGO_SF_*} definitions are not readable
 * by every role. The document itself was authorized for the caller before any read
 * ({@code NeoAttachmentTarget}).</p>
 */
final class NeoPrintableReads {

  private static final String PARAM_PARENT_ID = "parentId";

  private NeoPrintableReads() {
  }

  /**
   * One record, as {@code fetchJson} returns it.
   *
   * @return the record, or {@code null} when the read failed or found nothing (the browser throws on
   *         a non-2xx answer; the callers treat {@code null} as that failure)
   */
  static JSONObject readRecord(String specName, String entityName, String recordId) {
    JSONObject body = get(specName, entityName, recordId, Collections.emptyMap());
    return body == null ? null : unwrapOne(body);
  }

  /**
   * The children of a record, as {@code fetchAll(...?parentId=<id>)} returns them — the default
   * page, exactly what the browser reads.
   *
   * @return the rows, empty when the read failed
   */
  static JSONArray children(String specName, String entityName, String parentId) {
    return children(specName, entityName, parentId, Collections.emptyMap());
  }

  /**
   * The children of a record with the extra query parameters the browser sends (e.g. the movement
   * builders' {@code _startRow=0&_endRow=200}).
   *
   * @return the rows, empty when the read failed
   */
  static JSONArray children(String specName, String entityName, String parentId,
      Map<String, String> extraParams) {
    Map<String, String> params = new java.util.HashMap<>(extraParams);
    params.put(PARAM_PARENT_ID, parentId);
    JSONObject body = get(specName, entityName, null, params);
    return body == null ? new JSONArray() : unwrapAll(body);
  }

  private static JSONObject get(String specName, String entityName, String recordId,
      Map<String, String> queryParams) {
    SFSpec spec;
    SFEntity entity;
    OBContext.setAdminMode(true);
    try {
      spec = findSpec(specName);
      entity = spec == null ? null : findEntity(spec, entityName);
      if (entity == null || entity.getADTab() == null) {
        return null;
      }
      NeoContext context = NeoContext.builder()
          .specName(spec.getName())
          .entityName(entity.getName())
          .httpMethod("GET")
          .recordId(recordId)
          .queryParams(queryParams)
          .adTab(entity.getADTab())
          .sfEntity(entity)
          .obContext(OBContext.getOBContext())
          .endpointType(NeoEndpointType.CRUD)
          .build();
      NeoResponse response = NeoServletSupport.handleWithHooks(entity.getJavaQualifier(), context,
          new NeoCrudHandler(null), NeoExtensionChannel.REST_SINGLE);
      return response == null || response.getHttpStatus() >= 400 ? null : response.getBody();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** {@code d?.response?.data?.[0] ?? d?.response?.data ?? d}, for an object body. */
  static JSONObject unwrapOne(JSONObject body) {
    JSONObject response = body.optJSONObject("response");
    if (response != null) {
      JSONArray rows = response.optJSONArray("data");
      if (rows != null) {
        return rows.length() == 0 ? null : rows.optJSONObject(0);
      }
      JSONObject data = response.optJSONObject("data");
      if (data != null) {
        return data;
      }
    }
    return body;
  }

  /** {@code d?.response?.data ?? []}, for an object body. */
  static JSONArray unwrapAll(JSONObject body) {
    JSONObject response = body.optJSONObject("response");
    JSONArray rows = response == null ? null : response.optJSONArray("data");
    return rows == null ? new JSONArray() : rows;
  }

  private static SFSpec findSpec(String specName) {
    if (StringUtils.isBlank(specName)) {
      return null;
    }
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, specName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFSpec> found = criteria.list();
    return found.isEmpty() ? null : found.get(0);
  }

  private static SFEntity findEntity(SFSpec spec, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFEntity> found = criteria.list();
    return found.isEmpty() ? null : found.get(0);
  }
}
