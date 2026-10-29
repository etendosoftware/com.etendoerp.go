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

package com.etendoerp.go.mcp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.PeriodControl;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * ETP-5852 (M-193): names the date and the period behind a "period not open" refusal of
 * {@code etendo_action}.
 *
 * <p>Completing a document dated in a period that is not open answered only "El Periodo no existe
 * o no está abierto", so the agent could not tell the user which period, nor open it without a
 * search. When the refusal carries {@code PeriodNotAvailable} or {@code PeriodNotValid} and the
 * record has an {@code accountingDate}, the body gains
 * {@code period: {accountingDate, id, name, status, documentCategory}} — {@code status} being the
 * period's state for the document's category ({@code open}, {@code closed}, {@code neverOpened},
 * {@code permanentlyClosed}) — or {@code period: {accountingDate, exists:false}} when the
 * organization's calendar has no period for that date. {@code id} is the record of
 * {@code open-close-period-control/periodControl}, the one the keyed hint tells the agent to open.</p>
 *
 * <p>The period is looked up as core's period check does: in the calendar of the organization
 * that controls periods for the record's organization. Enrichment only: a failure to read it
 * leaves the refusal as it was.</p>
 */
final class McpPeriodRefusal {

  private static final Logger log = LogManager.getLogger(McpPeriodRefusal.class);

  static final String KEY_PERIOD = "period";
  private static final String PROPERTY_ACCOUNTING_DATE = "accountingDate";
  private static final String PROPERTY_ORGANIZATION = "organization";
  private static final String PROPERTY_DOCUMENT_TYPE = "documentType";
  private static final List<String> PERIOD_KEYS =
      List.of(McpMessageKeyHints.PERIOD_NOT_AVAILABLE_KEY, "PeriodNotValid");
  private static final Map<String, String> STATUS_NAMES = Map.of("O", "open", "C", "closed",
      "N", "neverOpened", "P", "permanentlyClosed");

  private McpPeriodRefusal() {
  }

  /**
   * Adds {@code period} to an action refusal keyed on a period that is not open.
   *
   * @param body     the refusal body, mutated in place; {@code null} is ignored
   * @param sfEntity the entity the action ran on
   * @param recordId the record the action ran on
   */
  static void addTo(JSONObject body, SFEntity sfEntity, String recordId) {
    if (body == null || body.has(KEY_PERIOD) || !isPeriodRefusal(body)) {
      return;
    }
    try {
      JSONObject period = periodOf(sfEntity, recordId);
      if (period != null) {
        body.put(KEY_PERIOD, period);
      }
    } catch (Exception e) {
      log.warn("Could not name the period of {} {}: {}", sfEntity.getName(), recordId,
          e.getMessage());
    }
  }

  /** Whether the body, flat or with its nested {@code error}, is keyed on a period refusal. */
  static boolean isPeriodRefusal(JSONObject body) {
    JSONObject nested = body.optJSONObject(McpConstants.KEY_ERROR);
    return hasPeriodKey(body.optJSONArray("messageKeys"))
        || (nested != null && hasPeriodKey(nested.optJSONArray("messageKeys")));
  }

  private static boolean hasPeriodKey(JSONArray keys) {
    if (keys == null) {
      return false;
    }
    for (int i = 0; i < keys.length(); i++) {
      if (PERIOD_KEYS.contains(keys.optString(i))) {
        return true;
      }
    }
    return false;
  }

  private static JSONObject periodOf(SFEntity sfEntity, String recordId) throws JSONException {
    Entity entity = ModelProvider.getInstance()
        .getEntityByTableName(sfEntity.getADTab().getTable().getDBTableName());
    if (entity == null || !entity.hasProperty(PROPERTY_ACCOUNTING_DATE)
        || !entity.hasProperty(PROPERTY_ORGANIZATION)) {
      return null;
    }
    BaseOBObject document = OBDal.getInstance().get(entity.getName(), recordId);
    if (document == null) {
      return null;
    }
    Date date = (Date) document.get(PROPERTY_ACCOUNTING_DATE);
    Organization org = (Organization) document.get(PROPERTY_ORGANIZATION);
    if (date == null || org == null) {
      return null;
    }
    JSONObject out = new JSONObject();
    out.put(PROPERTY_ACCOUNTING_DATE, new SimpleDateFormat("yyyy-MM-dd").format(date));
    Organization controlling = OBContext.getOBContext()
        .getOrganizationStructureProvider(org.getClient().getId())
        .getPeriodControlAllowedOrganization(org);
    Period period = controlling == null ? null : periodFor(controlling.getCalendar(), date);
    if (period == null) {
      out.put("exists", false);
      return out;
    }
    out.put("id", period.getId());
    out.put("name", period.getIdentifier());
    String category = entity.hasProperty(PROPERTY_DOCUMENT_TYPE)
        && document.get(PROPERTY_DOCUMENT_TYPE) != null
            ? ((DocumentType) document.get(PROPERTY_DOCUMENT_TYPE)).getDocumentCategory()
            : null;
    if (category != null) {
      out.put("documentCategory", category);
      String status = statusFor(period, controlling, category);
      if (status != null) {
        out.put("status", STATUS_NAMES.getOrDefault(status, status));
      }
    }
    return out;
  }

  private static Period periodFor(Calendar calendar, Date date) {
    if (calendar == null) {
      return null;
    }
    OBCriteria<Period> crit = OBDal.getInstance().createCriteria(Period.class);
    crit.createAlias(Period.PROPERTY_YEAR, "y");
    crit.add(Restrictions.eq("y.calendar", calendar));
    crit.add(Restrictions.le(Period.PROPERTY_STARTINGDATE, date));
    crit.add(Restrictions.ge(Period.PROPERTY_ENDINGDATE, date));
    crit.add(Restrictions.eq(Period.PROPERTY_PERIODTYPE, "S"));
    crit.setMaxResults(1);
    return (Period) crit.uniqueResult();
  }

  private static String statusFor(Period period, Organization controlling, String category) {
    OBCriteria<PeriodControl> crit = OBDal.getInstance().createCriteria(PeriodControl.class);
    crit.add(Restrictions.eq(PeriodControl.PROPERTY_PERIOD, period));
    crit.add(Restrictions.eq(PeriodControl.PROPERTY_ORGANIZATION, controlling));
    crit.add(Restrictions.eq(PeriodControl.PROPERTY_DOCUMENTCATEGORY, category));
    crit.setMaxResults(1);
    PeriodControl control = (PeriodControl) crit.uniqueResult();
    return control != null ? control.getPeriodStatus() : null;
  }
}
