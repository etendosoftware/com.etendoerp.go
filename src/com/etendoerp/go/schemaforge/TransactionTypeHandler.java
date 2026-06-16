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

import java.text.Normalizer;
import java.util.Locale;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.ETGOTransactionType;

/**
 * Pre-hook for the user-definable <b>transaction type</b> lookup (ETP-4099).
 *
 * <p>Transaction types are created on the fly from the searchable selector in the
 * match-rule modal — there is no maintenance window. The lookup is served by generic
 * NEO Headless W CRUD over {@link ETGOTransactionType}; this handler is registered via
 * {@code @Named("transaction-type")} (matching {@code ETGO_SF_ENTITY.Java_Qualifier}) and
 * runs <b>before</b> the generic CRUD.
 *
 * <p>On a write it:
 * <ul>
 *   <li>requires a non-blank {@code name} (max {@value #NAME_MAX_LENGTH}) — HTTP 400</li>
 *   <li>derives the {@code searchKey} (the AD {@code Value}) from the name as an
 *       uppercase, accent-stripped slug when the caller did not supply one, and injects it
 *       into the request body so the generic CRUD persists it (the create form only sends a
 *       name)</li>
 *   <li>rejects a duplicate {@code searchKey} within the client — HTTP 409</li>
 * </ul>
 * Returning {@code null} lets the generic CRUD proceed with the (possibly mutated) body.
 */
@Named("transaction-type")
public class TransactionTypeHandler extends AbstractNeoHandler {

  private static final Logger log = LogManager.getLogger(TransactionTypeHandler.class);

  private static final String SPEC = "transaction-type";

  private static final String F_NAME = "name";
  private static final String F_SEARCH_KEY = "searchKey";

  private static final int NAME_MAX_LENGTH = 60;
  private static final int VALUE_MAX_LENGTH = 60;
  private static final String DEFAULT_SLUG = "TYPE";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!SPEC.equals(context.getSpecName())) {
      return null;
    }
    if (!isWriteMethod(context.getHttpMethod())) {
      // GET / DELETE flow straight through to generic CRUD.
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      // Let the generic CRUD produce the canonical "missing body" error.
      return null;
    }

    try {
      enterAdminMode();
      return validateAndEnrich(body, context.getRecordId());
    } catch (JSONException e) {
      log.error("transaction-type hook error", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      exitAdminMode();
    }
  }

  /**
   * Validates the name and injects a derived {@code searchKey} before persistence.
   * Returns {@code null} when the write may proceed, or a {@link NeoResponse} error.
   */
  NeoResponse validateAndEnrich(JSONObject body, String recordId) throws JSONException {
    String name = optTrimmed(body, F_NAME);
    if (StringUtils.isBlank(name)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is required");
    }
    if (name.length() > NAME_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is too long");
    }

    String value = optTrimmed(body, F_SEARCH_KEY);
    if (StringUtils.isBlank(value)) {
      value = slugify(name);
    }
    if (value.length() > VALUE_MAX_LENGTH) {
      value = value.substring(0, VALUE_MAX_LENGTH);
    }

    if (searchKeyExists(value, recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "A transaction type with this key already exists");
    }

    // Inject the derived key so the generic CRUD persists it (Value is required in DB).
    body.put(F_SEARCH_KEY, value);
    return null;
  }

  /**
   * Builds an uppercase, accent-stripped slug from the display name, collapsing any run of
   * non-alphanumeric characters into a single underscore (e.g. "Comisión bancaria" → "COMISION_BANCARIA").
   */
  static String slugify(String name) {
    // Possessive quantifiers (++) prevent backtracking; StringUtils.strip trims the
    // separator underscores without a regex (clearer than an anchored "^_+|_+$" alternation).
    String stripped = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
        .replaceAll("\\p{M}++", "");
    String collapsed = stripped.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]++", "_");
    String slug = StringUtils.strip(collapsed, "_");
    return slug.isEmpty() ? DEFAULT_SLUG : slug;
  }

  /** True when another transaction type already uses {@code value} as its search key. */
  boolean searchKeyExists(String value, String recordId) {
    OBCriteria<ETGOTransactionType> criteria = OBDal.getInstance().createCriteria(ETGOTransactionType.class);
    criteria.add(Restrictions.eq(ETGOTransactionType.PROPERTY_SEARCHKEY, value));
    if (StringUtils.isNotBlank(recordId)) {
      criteria.add(Restrictions.ne(ETGOTransactionType.PROPERTY_ID, recordId));
    }
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }
}
