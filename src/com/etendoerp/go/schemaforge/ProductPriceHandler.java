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

import java.util.List;

import javax.inject.Named;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * NEO Handler for the {@code price} entity (M_ProductPrice tab).
 *
 * <p>Injects missing defaults before a POST (create) so that the generic
 * {@code NeoDefaultsService} does not need to know about ProductPrice specifics:
 * <ul>
 *   <li>{@code product} — filled from the parent product ID when absent.</li>
 *   <li>{@code priceLimit} — derived from {@code listPrice} or {@code standardPrice}.</li>
 *   <li>{@code priceListVersion} — resolved to the default active sales price list version
 *       for the current client/org when absent.</li>
 * </ul>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'productPriceHandler'} on the
 * {@code ETGO_SF_ENTITY} record for the {@code price} entity.
 */
@Named("productPriceHandler")
public class ProductPriceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProductPriceHandler.class);
  private static final String PRICE_LIMIT = "priceLimit";

  @Override
  public NeoResponse handle(NeoContext ctx) {
    if (ctx.getEndpointType() != NeoEndpointType.CRUD
        || !"POST".equals(ctx.getHttpMethod())) {
      return null;
    }

    JSONObject body = ctx.getRequestBody();
    if (body == null) {
      return null;
    }

    try {
      // Inject product from parent when missing
      String parentId = ctx.getQueryParams() != null
          ? (String) ctx.getQueryParams().get("parentId") : null;
      if (StringUtils.isNotBlank(parentId) && !body.has("product")) {
        body.put("product", parentId);
      }

      // Derive priceLimit from listPrice or standardPrice when missing
      if (!body.has(PRICE_LIMIT)) {
        if (body.has("listPrice")) {
          body.put(PRICE_LIMIT, body.opt("listPrice"));
        } else if (body.has("standardPrice")) {
          body.put(PRICE_LIMIT, body.opt("standardPrice"));
        }
      }

      // Resolve default sales price list version when missing
      if (!body.has("priceListVersion")) {
        String versionId = resolveDefaultSalesPriceListVersionId(ctx.getObContext());
        if (StringUtils.isNotBlank(versionId)) {
          body.put("priceListVersion", versionId);
        }
      }
    } catch (Exception e) {
      log.warn("Could not enrich ProductPrice defaults: {}", e.getMessage());
    }

    // Return null so the default service proceeds with the enriched body
    return null;
  }

  /**
   * Resolve the ID of the most recent active sales price list version for the
   * current client/org using OBDal — org-specific versions take priority over
   * shared (org=0) ones.
   */
  private String resolveDefaultSalesPriceListVersionId(OBContext obContext) {
    if (obContext == null || obContext.getCurrentClient() == null) {
      return null;
    }

    String clientId = obContext.getCurrentClient().getId();
    String orgId = obContext.getCurrentOrganization() != null
        ? obContext.getCurrentOrganization().getId() : "0";

    try {
      OBContext.setAdminMode();
      try {
        // Try org-specific version first; if not found, fall back to shared org=0
        String[] orgsToTry = "0".equals(orgId)
            ? new String[]{ "0" }
            : new String[]{ orgId, "0" };

        for (String targetOrg : orgsToTry) {
          OBCriteria<PriceListVersion> crit = OBDal.getInstance()
              .createCriteria(PriceListVersion.class);
          crit.add(Restrictions.eq(PriceListVersion.PROPERTY_CLIENT + ".id", clientId));
          crit.add(Restrictions.eq(PriceListVersion.PROPERTY_ORGANIZATION + ".id", targetOrg));
          crit.add(Restrictions.eq(PriceListVersion.PROPERTY_ACTIVE, true));
          crit.createAlias(PriceListVersion.PROPERTY_PRICELIST, "pl");
          crit.add(Restrictions.eq("pl." + PriceList.PROPERTY_ACTIVE, true));
          crit.add(Restrictions.eq("pl." + PriceList.PROPERTY_SALESPRICELIST, true));
          crit.addOrder(Order.desc(PriceListVersion.PROPERTY_VALIDFROMDATE));
          crit.setMaxResults(1);

          List<PriceListVersion> results = crit.list();
          if (!results.isEmpty()) {
            return results.get(0).getId();
          }
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not resolve default sales price list version: {}", e.getMessage());
    }

    return null;
  }
}
