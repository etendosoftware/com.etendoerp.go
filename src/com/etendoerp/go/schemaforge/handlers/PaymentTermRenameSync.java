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
package com.etendoerp.go.schemaforge.handlers;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.Query;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.financialmgmt.payment.PaymentTermTrl;

import com.etendoerp.go.schemaforge.NeoContext;

/**
 * QA BUG-1 (ETP-5978): keeps the translated name of a renamed payment term in step with its base
 * name. The why — a translatable identifier, and a trigger that only flags the translations out of
 * date — is on {@link PaymentTermHandler#afterHandle}, which drives these steps: the pre-hook
 * remembers the new name, and after a successful write the translations and the echoed
 * {@code _identifier} get it.
 *
 * <p>A plain helper of {@link PaymentTermHandler}, which is the only binding. Not a bean: no CDI
 * scope, no {@code @Named}, no {@code @NeoExtension}.</p>
 */
final class PaymentTermRenameSync {

  /** Context attribute: the name an accepted update gives the term. */
  static final String ATTR_RENAMED_TO = PaymentTermHandler.class.getName() + ".renamedTo";

  private static final String DATA_ENVELOPE = "data";
  private static final String RESPONSE_ENVELOPE = "response";
  private static final String KEY_ID = "id";
  private static final String KEY_IDENTIFIER = "_identifier";

  private PaymentTermRenameSync() {
  }

  /**
   * Records on the context the name an accepted update gives the term, for
   * {@link PaymentTermHandler#afterHandle} to bring the translations in step once the write has
   * succeeded. Nothing is recorded when the name is not sent, is blank or does not change.
   *
   * @param context the update request; {@code null} records nothing
   * @param stored  the record being updated
   * @param name    the name the update sends, or {@code null} when it sends none
   */
  static void rememberRename(NeoContext context, PaymentTerm stored, String name) {
    if (context == null || StringUtils.isBlank(name) || name.equals(stored.getName())) {
      return;
    }
    context.setAttribute(ATTR_RENAMED_TO, name);
  }

  /**
   * Sets {@code _identifier} to {@code name} on the echoed record {@code recordId}, in the
   * {@code {"response": {"data": [...]}}} shape core's update returns or in a flat
   * {@code {"data": [...]}}. The identifier of a payment term is its name alone.
   */
  static void applyIdentifier(JSONObject body, String recordId, String name)
      throws JSONException {
    if (body == null) {
      return;
    }
    JSONObject inner = body.optJSONObject(RESPONSE_ENVELOPE);
    JSONArray rows = (inner != null ? inner : body).optJSONArray(DATA_ENVELOPE);
    if (rows == null) {
      return;
    }
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row != null && recordId.equals(row.optString(KEY_ID, null))) {
        row.put(KEY_IDENTIFIER, name);
      }
    }
  }

  /**
   * Copies {@code name} into every translation of the term ({@code C_PaymentTerm_Trl}), all
   * languages. Called from {@link PaymentTermHandler#afterHandle}, i.e. after the rename has been
   * committed by the write service (single REST / MCP write): it runs in a second transaction, committed at the end
   * of the request, so it is not atomic with the rename. A bulk HQL update: it bypasses DAL entity
   * access, which the role writing the base record does not need on the translation entity.
   *
   * @return the number of translation rows updated
   */
  static int syncTranslatedName(String termId, String name) {
    Query<?> update = OBDal.getInstance().getSession().createQuery(
        "update " + PaymentTermTrl.ENTITY_NAME + " t set t." + PaymentTermTrl.PROPERTY_NAME
            + " = :name where t." + PaymentTermTrl.PROPERTY_PAYMENTTERMS + ".id = :termId");
    update.setParameter("name", name);
    update.setParameter("termId", termId);
    return update.executeUpdate();
  }
}
