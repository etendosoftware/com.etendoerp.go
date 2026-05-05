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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * NeoHandler for the Price List header entity.
 *
 * <p>GET post-hook: injects {@code priceListVersion} (the single version id) into
 * each price list record so the frontend can locate product prices in one fetch
 * instead of round-tripping through {@code priceListVersion?parentId=...}.
 */
@Named("priceListHeaderHandler")
public class PriceListHeaderHandler implements NeoHandler {

  private static final String FIELD_PRICE_LIST_VERSION = "priceListVersion";

  private final Logger log = LogManager.getLogger(getClass());

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
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
      if (context.getRecordId() != null) {
        JSONObject rec = dataArr.getJSONObject(0);
        rec.put(FIELD_PRICE_LIST_VERSION, resolveVersionId(rec.optString("id", null)));
      } else {
        annotateBatch(dataArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error annotating priceListVersion on price list response", e);
      return null;
    }
  }

  private void annotateBatch(JSONArray dataArr) throws Exception {
    List<String> priceListIds = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString("id", null);
      if (id != null && !id.isEmpty()) {
        priceListIds.add(id);
      }
    }
    if (priceListIds.isEmpty()) {
      return;
    }
    Map<String, String> versionByPriceListId = PriceListVersionResolver
        .findSingleVersionIds(priceListIds);
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      String plId = rec.optString("id", null);
      rec.put(FIELD_PRICE_LIST_VERSION,
          plId != null ? versionByPriceListId.getOrDefault(plId, "") : "");
    }
  }

  private String resolveVersionId(String priceListId) {
    if (priceListId == null || priceListId.isEmpty()) {
      return "";
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    String versionId = PriceListVersionResolver.findSingleVersionId(priceList);
    return versionId != null ? versionId : "";
  }
}
