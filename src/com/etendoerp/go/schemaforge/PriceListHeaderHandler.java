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

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListSchema;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * NeoHandler for the Price List header entity.
 *
 * <p>Owns all GO-specific behavior around the hidden {@link PriceListVersion}
 * that backs each {@link PriceList} in the simplified interface:
 *
 * <ul>
 *   <li><b>POST (create)</b>: auto-creates one {@link PriceListVersion} and its
 *       required {@link PriceListSchema} so product prices can be added immediately.</li>
 *   <li><b>PATCH / PUT (update)</b>: keeps the version name in sync with the price list name.</li>
 *   <li><b>GET</b>: injects {@code priceListVersion} (the single version id) into each
 *       record so the frontend can locate product prices in one fetch instead of round-tripping
 *       through {@code priceListVersion?parentId=...}.</li>
 * </ul>
 *
 * <p>Living at the NEO Headless layer (rather than as an
 * {@code EntityPersistenceEventObserver}) ensures these GO-specific behaviors do not
 * affect Etendo Classic / Enterprise users that operate directly on the AD windows.
 */
@Named("priceListHeaderHandler")
public class PriceListHeaderHandler implements NeoHandler {

  private static final String FIELD_PRICE_LIST_VERSION = "priceListVersion";
  private static final int MAX_SCHEMA_NAME_LENGTH = 60;
  private static final String SCHEMA_NAME_SUFFIX = " Schema";

  private final Logger log = LogManager.getLogger(getClass());

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    NeoResponse previousResult = context.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!"GET".equals(method) && !"POST".equals(method)
        && !"PATCH".equals(method) && !"PUT".equals(method)) {
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
      if ("POST".equals(method)) {
        ensureDefaultVersionForFirstRecord(dataArr);
      } else if ("PATCH".equals(method) || "PUT".equals(method)) {
        syncVersionNameForFirstRecord(dataArr);
      }
      annotateRecords(context, dataArr);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error post-processing price list response", e);
      return null;
    }
  }

  // ── version annotation (injects priceListVersion into the response) ───────

  private void annotateRecords(NeoContext context, JSONArray dataArr) throws Exception {
    if (context.getRecordId() != null || dataArr.length() == 1) {
      JSONObject rec = dataArr.getJSONObject(0);
      rec.put(FIELD_PRICE_LIST_VERSION, resolveVersionId(rec.optString("id", null)));
    } else {
      annotateBatch(dataArr);
    }
  }

  private void annotateBatch(JSONArray dataArr) throws Exception {
    List<JSONObject> records = extractRecords(dataArr);
    List<String> priceListIds = extractIds(records);
    if (priceListIds.isEmpty()) {
      return;
    }
    Map<String, String> versionByPriceListId = PriceListVersionResolver
        .findSingleVersionIds(priceListIds);
    for (JSONObject rec : records) {
      String plId = rec.optString("id", null);
      rec.put(FIELD_PRICE_LIST_VERSION,
          plId != null ? versionByPriceListId.getOrDefault(plId, "") : "");
    }
  }

  private static List<JSONObject> extractRecords(JSONArray dataArr) throws Exception {
    List<JSONObject> list = new ArrayList<>(dataArr.length());
    for (int i = 0; i < dataArr.length(); i++) {
      list.add(dataArr.getJSONObject(i));
    }
    return list;
  }

  private static List<String> extractIds(List<JSONObject> records) {
    List<String> ids = new ArrayList<>();
    for (JSONObject rec : records) {
      String id = rec.optString("id", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }

  private String resolveVersionId(String priceListId) {
    if (priceListId == null || priceListId.isEmpty()) {
      return "";
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    String versionId = PriceListVersionResolver.findSingleVersionId(priceList);
    return versionId != null ? versionId : "";
  }

  // ── version lifecycle (auto-create on POST, sync name on UPDATE) ─────────

  private void ensureDefaultVersionForFirstRecord(JSONArray dataArr) throws Exception {
    String priceListId = dataArr.getJSONObject(0).optString("id", null);
    if (priceListId == null || priceListId.isEmpty()) {
      return;
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    if (priceList == null) {
      return;
    }
    if (PriceListVersionResolver.findSingleVersion(priceList) != null) {
      return;
    }
    createDefaultVersion(priceList);
  }

  private void createDefaultVersion(PriceList priceList) {
    PriceListSchema schema = OBProvider.getInstance().get(PriceListSchema.class);
    schema.setNewOBObject(true);
    schema.setClient(priceList.getClient());
    schema.setOrganization(priceList.getOrganization());
    schema.setName(buildSchemaName(priceList.getName()));
    OBDal.getInstance().save(schema);

    PriceListVersion version = OBProvider.getInstance().get(PriceListVersion.class);
    version.setNewOBObject(true);
    version.setClient(priceList.getClient());
    version.setOrganization(priceList.getOrganization());
    version.setName(priceList.getName());
    version.setPriceList(priceList);
    version.setPriceListSchema(schema);
    version.setValidFromDate(java.sql.Date.valueOf(LocalDate.of(Year.now().getValue(), 1, 1)));
    OBDal.getInstance().save(version);

    log.debug("Auto-created price list version '{}' for price list '{}'",
        version.getName(), priceList.getName());
  }

  private static String buildSchemaName(String priceListName) {
    String schemaName = priceListName + SCHEMA_NAME_SUFFIX;
    if (schemaName.length() > MAX_SCHEMA_NAME_LENGTH) {
      schemaName = schemaName.substring(0, MAX_SCHEMA_NAME_LENGTH - 3) + "...";
    }
    return schemaName;
  }

  private void syncVersionNameForFirstRecord(JSONArray dataArr) throws Exception {
    String priceListId = dataArr.getJSONObject(0).optString("id", null);
    if (priceListId == null || priceListId.isEmpty()) {
      return;
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    if (priceList == null) {
      return;
    }
    PriceListVersion version = PriceListVersionResolver.findSingleVersion(priceList);
    if (version == null) {
      return;
    }
    if (!priceList.getName().equals(version.getName())) {
      version.setName(priceList.getName());
      OBDal.getInstance().save(version);
      log.debug("Synced price list version name to '{}'", priceList.getName());
    }
  }
}
