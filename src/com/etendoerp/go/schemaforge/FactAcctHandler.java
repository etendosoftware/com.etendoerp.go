package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.accounting.AccountingFact;

/**
 * NeoHandler that serves Fact_Acct (accounting entries) for a parent document.
 *
 * Intercepts GET list requests for the "accounting" entity. Because Fact_Acct
 * uses a generic Record_ID + AD_Table_ID pattern instead of a direct FK to the
 * parent document, NEO's default parent-child filtering (via
 * ApplicationUtils.getParentProperty) returns null and produces empty results.
 *
 * This handler queries AccountingFact directly using:
 *   recordID = parentId  (from queryParams)
 *   table.id = adTableId (318 for C_Invoice, 319 for M_InOut)
 */
@Named("factAcctHandler")
public class FactAcctHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FactAcctHandler.class);
  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

  /** AD_Table_ID for each supported document type (spec name → table ID). */
  private static final Map<String, String> TABLE_ID_BY_SPEC = Map.of(
      "purchase-invoice", "318",
      "goods-receipt",    "319"
  );

  @Override
  public NeoResponse handle(NeoContext context) {
    // Only intercept CRUD GET list requests (no recordId = list)
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    if (!"GET".equals(context.getHttpMethod())) {
      return null;
    }
    if (context.getRecordId() != null) {
      return null;
    }

    String parentId = context.getQueryParams() != null
        ? context.getQueryParams().get("parentId")
        : null;

    if (parentId == null || parentId.isEmpty()) {
      return emptyResponse();
    }

    String adTableId = TABLE_ID_BY_SPEC.get(context.getSpecName());
    if (adTableId == null) {
      log.warn("[FactAcctHandler] Unknown specName: {}", context.getSpecName());
      return emptyResponse();
    }

    try {
      List<AccountingFact> entries = OBDal.getInstance()
          .createQuery(AccountingFact.class,
              "as af where af.recordID = :recordId and af.table.id = :tableId")
          .setNamedParameter("recordId", parentId)
          .setNamedParameter("tableId", adTableId)
          .list();

      JSONArray data = new JSONArray();
      for (AccountingFact af : entries) {
        JSONObject row = new JSONObject();
        row.put("id", af.getId());

        // Account: "VALUE - NAME" (GL account code + name)
        if (af.getAccount() != null) {
          String value = af.getAccount().getSearchKey();
          String name = af.getAccount().getName();
          row.put("account", value + " - " + name);
        } else {
          row.put("account", "");
        }

        // Accounting date
        if (af.getAccountingDate() != null) {
          row.put("accountingDate", DATE_FORMAT.format(af.getAccountingDate()));
        } else {
          row.put("accountingDate", JSONObject.NULL);
        }

        // Posting type
        row.put("postingType", af.getPostingType() != null ? af.getPostingType() : "");

        // Debit / Credit amounts
        BigDecimal debit = af.getDebit();
        BigDecimal credit = af.getCredit();
        row.put("debit", debit != null ? debit : BigDecimal.ZERO);
        row.put("credit", credit != null ? credit : BigDecimal.ZERO);

        // Description
        row.put("description", af.getDescription() != null ? af.getDescription() : "");

        data.put(row);
      }

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("[FactAcctHandler] Error fetching accounting entries for parentId={}", parentId, e);
      return NeoResponse.error(500, "Error fetching accounting entries: " + e.getMessage());
    }
  }

  private NeoResponse emptyResponse() {
    try {
      JSONObject responseData = new JSONObject();
      responseData.put("data", new JSONArray());
      responseData.put("count", 0);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      return NeoResponse.error(500, e.getMessage());
    }
  }
}
