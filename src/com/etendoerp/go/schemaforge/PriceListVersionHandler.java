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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * NeoHandler for the {@code priceListVersion} entity.
 *
 * <p>Enforces the Etendo Go invariant <i>one price list = one version</i> at the GO API
 * boundary by rejecting any POST that would create a second version for a price list that
 * already has one. This intentionally lives at the NEO Headless layer (not as an
 * {@code EntityPersistenceEventObserver}) so Etendo Classic / Enterprise users keep their
 * native ability to manage multiple versions natively — the rule only applies to traffic
 * coming through the GO API.
 *
 * <p>{@link PriceListEventHandler} still auto-creates the first version on price list
 * insert via {@code OBDal.save()}, which does not pass through NEO and is therefore not
 * affected by this handler.
 */
@Named("priceListVersionHandler")
public class PriceListVersionHandler implements NeoHandler {

  private static final String FIELD_PRICE_LIST = "priceList";

  private final Logger log = LogManager.getLogger(getClass());

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"POST".equals(context.getHttpMethod())) {
      return null;
    }
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    String priceListId = body.optString(FIELD_PRICE_LIST, null);
    if (priceListId == null || priceListId.isEmpty()) {
      return null;
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    if (priceList == null) {
      return null;
    }
    String existingVersionId = PriceListVersionResolver.findSingleVersionId(priceList);
    if (existingVersionId == null) {
      return null;
    }
    log.warn("Rejected attempt to create a second PriceListVersion for price list '{}' "
        + "via NEO API; existing version: {}", priceList.getName(), existingVersionId);
    return NeoResponse.error(409,
        "This price list already has a version. Etendo Go uses a single version per price list.");
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }
}
