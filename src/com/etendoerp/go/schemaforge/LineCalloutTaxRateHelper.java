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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * Shared {@link NeoHandler#afterCallout(NeoContext)} implementation for transaction
 * line entities that expose the {@code C_Tax_ID} foreign key (sales-order lines,
 * purchase-order lines, sales-quotation lines, sales-invoice lines, purchase-invoice
 * lines).
 *
 * <p>Etendo Classic's {@code SL_Order_Amt} / {@code SL_Invoice_Amt} callouts run
 * when the user changes the line tax but they do not echo a {@code tax} update back
 * (the user already set it). That means the existing helper that piggybacks on
 * {@code tax} updates to publish the rate cannot fire. This helper closes the gap:
 * when the trigger is the tax FK and the response does not already carry
 * {@code tax} or {@code taxRate}, it loads the {@link TaxRate} and produces a
 * {@code taxRate} synthetic update so the frontend can recompute line amounts
 * client-side.
 *
 * <p>Used from {@link OrderLineHandler#afterCallout(NeoContext)} and
 * {@link InvoiceLineHandler#afterCallout(NeoContext)}. Keeping the logic in this
 * helper avoids duplicating it in each handler while still letting each handler
 * own its other CRUD-specific behavior.
 */
final class LineCalloutTaxRateHelper {

  private static final Logger log = LogManager.getLogger(LineCalloutTaxRateHelper.class);
  private static final String C_TAX_ID = "C_Tax_ID";

  private LineCalloutTaxRateHelper() {
  }

  /**
   * Two-fold enrichment for callouts on transaction lines:
   * <ul>
   *   <li>When the response already carries a {@code tax} update, normalize
   *       {@code tax$_identifier} to the DAL identifier of the new tax (the
   *       plain {@link TaxRate#getName() name}). Etendo Classic callouts often
   *       publish a composite label like {@code "Test - Standard"} that does
   *       not match what GET endpoints return on saved rows; mutating the
   *       identifier in place keeps the addRow and the saved rows visually
   *       consistent.</li>
   *   <li>When the trigger field is the tax FK and the underlying callout did
   *       not echo back {@code tax}, return a {@link NeoResponse} carrying a
   *       synthetic {@code taxRate} update so the frontend can recompute line
   *       amounts client-side.</li>
   * </ul>
   *
   * <p>Returns {@code null} when no enrichment applies (the dispatcher merges
   * a non-null result without overwriting; in-place mutations on the previous
   * response are visible regardless of the merge).
   */
  static NeoResponse augmentTaxRate(NeoContext context) {
    if (context == null
        || !NeoEndpointType.CALLOUT.equals(context.getEndpointType())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    String fieldName = body.optString("field", null);
    Object triggerValue = body.opt("value");

    NeoResponse previous = context.getPreviousResult();
    JSONObject existingUpdates = previous != null && previous.getBody() != null
        ? previous.getBody().optJSONObject("updates")
        : null;

    // Path A: response already carries a tax update (e.g. SL_Order_Product set
    // tax for the new product). Normalize the identifier in place so the addRow
    // shows the same label that GET will return after save.
    if (existingUpdates != null && existingUpdates.has("tax")) {
      String taxIdInResponse = existingUpdates.optJSONObject("tax") != null
          ? existingUpdates.optJSONObject("tax").optString("value", null)
          : null;
      if (StringUtils.isNotBlank(taxIdInResponse) && !"null".equals(taxIdInResponse)) {
        TaxRate taxEntity = loadTaxRate(taxIdInResponse);
        if (taxEntity != null && StringUtils.isNotBlank(taxEntity.getName())) {
          try {
            JSONObject idEntry = new JSONObject();
            idEntry.put("value", taxEntity.getName());
            existingUpdates.put("tax$_identifier", idEntry);
          } catch (Exception e) {
            log.debug("Could not normalize tax$_identifier: {}", e.getMessage());
          }
        }
      }
      // taxRate is already injected by NeoCalloutService.injectTaxRateIfPresent
      // when the response carries tax; nothing else to do here.
      return null;
    }

    // Path B: trigger is the tax FK and the response does not carry tax.
    if (StringUtils.isBlank(fieldName) || triggerValue == null) {
      return null;
    }
    if (existingUpdates != null && existingUpdates.has("taxRate")) {
      return null;
    }
    if (!triggerIsTaxColumn(context.getAdTab(), fieldName)) {
      return null;
    }
    TaxRate taxEntity = loadTaxRate(String.valueOf(triggerValue));
    if (taxEntity == null || taxEntity.getRate() == null) {
      return null;
    }

    try {
      JSONObject responseBody = new JSONObject();
      JSONObject updates = new JSONObject();
      JSONObject rateEntry = new JSONObject();
      rateEntry.put("value", taxEntity.getRate().doubleValue());
      updates.put("taxRate", rateEntry);
      responseBody.put("updates", updates);
      responseBody.put("combos", new JSONObject());
      return NeoResponse.ok(responseBody);
    } catch (Exception e) {
      log.debug("Could not build taxRate enrichment: {}", e.getMessage());
      return null;
    }
  }

  private static TaxRate loadTaxRate(String taxId) {
    if (StringUtils.isBlank(taxId) || "null".equals(taxId)) {
      return null;
    }
    try {
      return OBDal.getInstance().get(TaxRate.class, taxId);
    } catch (Exception e) {
      log.debug("Could not load TaxRate '{}': {}", taxId, e.getMessage());
      return null;
    }
  }

  /**
   * @return true when {@code fieldName} resolves to the {@code C_Tax_ID} column on
   *         the given tab's table.
   */
  private static boolean triggerIsTaxColumn(Tab adTab, String fieldName) {
    if (adTab == null || adTab.getTable() == null) {
      return false;
    }
    String tableId = adTab.getTable().getId();
    try {
      @SuppressWarnings("unchecked")
      List<Column> columns = (List<Column>) OBDal.getInstance()
          .createCriteria(Column.class)
          .add(Restrictions.eq(Column.PROPERTY_TABLE + ".id", tableId))
          .add(Restrictions.eq(Column.PROPERTY_ACTIVE, true))
          .list();
      Column matched = matchByDalProperty(tableId, columns, fieldName);
      if (matched == null) {
        matched = matchByDbName(columns, fieldName);
      }
      return matched != null && C_TAX_ID.equalsIgnoreCase(matched.getDBColumnName());
    } catch (Exception e) {
      log.debug("Could not resolve trigger column for '{}': {}", fieldName, e.getMessage());
      return false;
    }
  }

  private static Column matchByDalProperty(String tableId, List<Column> columns, String fieldName) {
    Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(tableId);
    if (dalEntity == null) {
      return null;
    }
    for (Column col : columns) {
      Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
      if (prop != null && prop.getName().equals(fieldName)) {
        return col;
      }
    }
    return null;
  }

  private static Column matchByDbName(List<Column> columns, String fieldName) {
    for (Column col : columns) {
      if (col.getDBColumnName().equalsIgnoreCase(fieldName)) {
        return col;
      }
    }
    return null;
  }

}
