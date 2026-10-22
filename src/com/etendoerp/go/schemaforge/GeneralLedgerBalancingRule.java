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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaGL;

/**
 * ETP-5724: in the General Ledger Configuration, a balancing switch that is on requires its account.
 * Core posting ({@code AcctSchema}) silently treats "on, no account" as off, so the row is refused
 * instead. Judged on the state the save would leave, so a row that is already broken blocks any save
 * that touches it.
 *
 * <p>Two write paths store the values differently, so each is checked the way it stores them:
 * the aggregate ({@link GeneralLedgerConfigurationHandler}, {@code optBoolean}) on the entity after
 * its setters ran ({@link #check(AcctSchemaGL)}); the generic {@code Cuentas generales} CRUD
 * (core {@code JsonToDataConverter}) on the request body merged with the stored row
 * ({@link #checkUpdate(NeoContext)}). Both answer the {@code MISSING_REQUIRED_FIELDS} 400.</p>
 *
 * <p>On {@code C_AcctSchema_GL} the API keys equal the DAL property names.</p>
 */
public final class GeneralLedgerBalancingRule {

  /** {switch, account} property pairs. */
  private static final String[][] PAIRS = {
      { AcctSchemaGL.PROPERTY_SUSPENSEBALANCINGUSE, AcctSchemaGL.PROPERTY_SUSPENSEBALANCING },
      { AcctSchemaGL.PROPERTY_CURRENCYBALANCINGUSE, AcctSchemaGL.PROPERTY_CURRENCYBALANCINGACCT } };

  private static final String DATA_ENVELOPE = "data";

  private GeneralLedgerBalancingRule() {
  }

  /**
   * Checks the row as it would be stored (aggregate path, after its setters ran).
   *
   * @param row the general accounts row, with the request's changes already applied
   * @return the 400, or {@code null} when the row is valid
   */
  public static NeoResponse check(AcctSchemaGL row) {
    List<String> missing = new ArrayList<>();
    for (String[] pair : PAIRS) {
      if (Boolean.TRUE.equals(row.get(pair[0])) && row.get(pair[1]) == null) {
        missing.add(pair[1]);
      }
    }
    return refusal(missing);
  }

  /**
   * Pre-hook check of a generic PUT/PATCH on {@code Cuentas generales}: each value is the body's when
   * its key is sent, else the stored one. A row the caller cannot read is left to the default path
   * (its 404).
   *
   * @param context the hook context of the {@code Cuentas generales} request
   * @return the 400, or {@code null} to continue
   */
  public static NeoResponse checkUpdate(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD
        || !("PUT".equals(context.getHttpMethod()) || "PATCH".equals(context.getHttpMethod()))) {
      return null;
    }
    AcctSchemaGL stored;
    OBContext.setAdminMode(true);
    try {
      stored = TenantOwnership.loadOwned(AcctSchemaGL.class, context.getRecordId());
    } finally {
      OBContext.restorePreviousMode();
    }
    if (stored == null) {
      return null;
    }
    JSONObject body = context.getRequestBody() == null ? new JSONObject() : context.getRequestBody();
    // REST hands the pre-hook the raw body, which may wrap the fields like NeoFieldFilter accepts.
    if (body.optJSONObject(DATA_ENVELOPE) != null) {
      body = body.optJSONObject(DATA_ENVELOPE);
    }
    List<String> missing = new ArrayList<>();
    for (String[] pair : PAIRS) {
      boolean on = body.has(pair[0]) ? isTrue(body.opt(pair[0])) : Boolean.TRUE.equals(stored.get(pair[0]));
      boolean hasAccount = body.has(pair[1]) ? isPresent(body.opt(pair[1])) : stored.get(pair[1]) != null;
      if (on && !hasAccount) {
        missing.add(pair[1]);
      }
    }
    return refusal(missing);
  }

  /** Boolean as core's JsonToDataConverter reads it: "Y" is true, other strings parse as booleans. */
  static boolean isTrue(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return value instanceof String && ("Y".equals(value) || Boolean.parseBoolean((String) value));
  }

  /** A foreign key value: a non-blank id, either bare or as {@code {"id": ...}}. */
  static boolean isPresent(Object value) {
    Object id = value instanceof JSONObject ? ((JSONObject) value).opt("id") : value;
    return id != null && id != JSONObject.NULL && StringUtils.isNotBlank(String.valueOf(id));
  }

  private static NeoResponse refusal(List<String> missing) {
    if (missing.isEmpty()) {
      return null;
    }
    List<String> reasons = new ArrayList<>();
    for (String[] pair : PAIRS) {
      if (missing.contains(pair[1])) {
        reasons.add(pair[1] + " is required while " + pair[0] + " is true");
      }
    }
    return NeoCrudHandler.buildMissingRequiredFieldsResponse(missing, String.join("; ", reasons));
  }
}
