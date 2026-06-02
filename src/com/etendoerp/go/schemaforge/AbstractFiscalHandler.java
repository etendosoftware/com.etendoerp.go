/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;

abstract class AbstractFiscalHandler {

  private static final Logger log = Logger.getLogger(AbstractFiscalHandler.class);

  protected static final String DECLARATIONS = "declarations";
  protected static final String MODIFIED     = "modified";
  protected static final String PERIOD_KEY   = "period";
  protected static final String SINCE_KEY    = "since";
  protected static final String JSON_CT      = "application/json;charset=UTF-8";

  protected final NeoServlet servlet;
  private   final FiscalDeclCrudHandler declHandler;

  AbstractFiscalHandler(NeoServlet servlet) {
    this.servlet     = servlet;
    this.declHandler = new FiscalDeclCrudHandler(servlet);
  }

  void handle(String entityName, String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (DECLARATIONS.equals(entityName)) {
      try {
        declHandler.handleDeclarations(method, request, response);
      } catch (Exception e) {
        log.error("Error in /" + getModelKey() + "/declarations", e);
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
      }
      return;
    }
    if (!isKnownEntity(entityName)) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Unknown " + getModelKey() + " entity: " + entityName);
      return;
    }
    boolean methodOk = "GET".equals(method)
        || ("POST".equals(method) && allowsPost(entityName));
    if (!methodOk) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Method not allowed for /" + getModelKey() + "/" + entityName);
      return;
    }
    String yearStr = request.getParameter("year");
    String period  = request.getParameter(PERIOD_KEY);
    if (yearStr == null || period == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required params: year, period");
      return;
    }
    if (MODIFIED.equals(entityName) && request.getParameter(SINCE_KEY) == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required param: since");
      return;
    }
    int year;
    try {
      year = Integer.parseInt(yearStr);
    } catch (NumberFormatException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid year: " + yearStr);
      return;
    }
    try {
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
      dispatch(entityName, orgId, year, period, request, response);
    } catch (FiscalHandlerException e) {
      log.error("Error in /" + getModelKey() + "/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error in /" + getModelKey() + "/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred.");
    }
  }

  protected abstract boolean isKnownEntity(String entityName);

  protected boolean allowsPost(String entityName) { return false; }

  protected abstract void dispatch(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException;

  protected abstract String getModelKey();

  // ── shared helpers ────────────────────────────────────────────────

  protected void handleModified(String orgId, int year, String period, Date since,
      HttpServletResponse response) throws Exception {
    List<Period> periods = resolvePeriods(orgId, year, period);
    if (periods.isEmpty()) { writeModifiedJson(response, false, 0); return; }
    Date fromDate = periods.get(0).getStartingDate();
    Date toDate   = periods.get(periods.size() - 1).getEndingDate();
    if (fromDate == null || toDate == null) { writeModifiedJson(response, false, 0); return; }

    Long count = (Long) OBDal.getInstance().getSession()
        .createQuery(
            "select count(i.id) from Invoice i "
            + "where i.organization.id = :orgId "
            + "  and i.invoiceDate between :fromDate and :toDate "
            + "  and i.updated > :since")
        .setParameter("orgId",    orgId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate",   toDate)
        .setParameter(SINCE_KEY,  since)
        .uniqueResult();

    boolean modified = count != null && count > 0;
    writeModifiedJson(response, modified, count == null ? 0 : count.intValue());
  }

  private void writeModifiedJson(HttpServletResponse response, boolean modified, int count)
      throws Exception {
    JSONObject out = new JSONObject();
    out.put(MODIFIED, modified);
    out.put("count", count);
    response.setContentType(JSON_CT);
    response.getWriter().write(out.toString());
  }

  protected void writeGeneratedFile(HashMap<String, Object> result, String filenameWithExt,
      HttpServletResponse response) throws Exception {
    Object fileContent = result.get("file");
    if (fileContent == null) {
      throw new OBException("generateElectronicFile returned no file content");
    }
    byte[] bytes = fileContent.toString().getBytes(StandardCharsets.ISO_8859_1);
    response.setContentType("text/plain");
    response.setCharacterEncoding("ISO-8859-1");
    response.setHeader("Content-Disposition",
        "attachment; filename=\"" + filenameWithExt + "\"");
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
    response.flushBuffer();
  }

  protected AcctSchema resolveAcctSchema(Organization org) {
    OBCriteria<AcctSchema> crit = OBDal.getInstance().createCriteria(AcctSchema.class);
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT + ".id", org.getClient().getId()));
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    List<AcctSchema> list = crit.list();
    if (list.isEmpty()) {
      throw new OBException("No AcctSchema found for client=" + org.getClient().getId());
    }
    return list.get(0);
  }

  @SuppressWarnings("unchecked")
  protected List<Period> resolvePeriods(String orgId, int year, String periodCode) {
    int monthFrom;
    int monthTo;
    if (periodCode.startsWith("T")) {
      int q = Integer.parseInt(periodCode.substring(1));
      monthFrom = (q - 1) * 3 + 1;
      monthTo   = q * 3;
    } else {
      monthFrom = Integer.parseInt(periodCode);
      monthTo   = monthFrom;
    }
    return OBDal.getInstance().getSession()
        .createQuery(
            "from FinancialMgmtPeriod p "
            + "where p.organization.id = :orgId "
            + "  and p.year.fiscalYear = :year "
            + "  and p.periodNo between :from and :to "
            + "order by p.periodNo",
            Period.class)
        .setParameter("orgId", orgId)
        .setParameter("year",  String.valueOf(year))
        .setParameter("from",  (long) monthFrom)
        .setParameter("to",    (long) monthTo)
        .list();
  }
}
