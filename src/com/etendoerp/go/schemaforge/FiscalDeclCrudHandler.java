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

import java.io.BufferedReader;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

class FiscalDeclCrudHandler {

  static final String DEFAULT_STATUS = "draft";

  private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";
  private static final String PERIOD_KEY        = "period";
  private static final String STATUS_KEY        = "status";
  private static final String FILE_NAME_KEY     = "fileName";
  private static final String FILE_EXTERNAL_KEY = "fileExternal";

  private final NeoServlet servlet;

  FiscalDeclCrudHandler(NeoServlet servlet) {
    this.servlet = servlet;
  }

  void handleDeclarations(String method, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    String orgId    = OBContext.getOBContext().getCurrentOrganization().getId();
    response.setContentType(JSON_CONTENT_TYPE);
    if ("GET".equals(method)) {
      handleDeclGet(clientId, orgId, response);
    } else if ("POST".equals(method)) {
      handleDeclPost(request, response);
    } else if ("PUT".equals(method)) {
      handleDeclPut(clientId, request, response);
    } else if ("DELETE".equals(method)) {
      handleDeclDelete(clientId, request, response);
    } else {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Unsupported method for /fiscal303/declarations: " + method);
    }
  }

  private void handleDeclGet(String clientId, String orgId, HttpServletResponse response)
      throws Exception {
    OBCriteria<FiscalDecl> crit = OBDal.getInstance().createCriteria(FiscalDecl.class);
    crit.add(Restrictions.eq("client.id", clientId));
    crit.add(Restrictions.eq("organization.id", orgId));
    crit.addOrderBy(FiscalDecl.PROPERTY_FISCALYEAR, false);
    crit.addOrderBy(FiscalDecl.PROPERTY_PERIOD, false);
    crit.addOrderBy(FiscalDecl.PROPERTY_FISCALMODEL, true);
    JSONArray arr = new JSONArray();
    for (FiscalDecl decl : crit.list()) arr.put(declToJson(decl));
    JSONObject out = new JSONObject();
    out.put("data", arr);
    response.getWriter().write(out.toString());
  }

  private void handleDeclPost(HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    JSONObject body = readJsonBody(request);
    String model    = body.getString("model");
    long   year     = body.getLong("year");
    String period   = body.getString(PERIOD_KEY);
    String declType = "com".equals(body.optString("type")) ? "C" : "O";
    String status   = body.has(STATUS_KEY) ? body.getString(STATUS_KEY) : DEFAULT_STATUS;

    FiscalDecl decl = OBProvider.getInstance().get(FiscalDecl.class);
    decl.setClient(OBContext.getOBContext().getCurrentClient());
    decl.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    decl.setCreatedBy(OBContext.getOBContext().getUser());
    decl.setUpdatedBy(OBContext.getOBContext().getUser());
    decl.setFiscalModel(model);
    decl.setFiscalYear(year);
    decl.setPeriod(period);
    decl.setDeclarationType(declType);
    decl.setDeclarationStatus(status);
    OBDal.getInstance().save(decl);
    JSONObject created = declToJson(decl);
    OBDal.getInstance().commitAndClose();

    response.setStatus(HttpServletResponse.SC_CREATED);
    response.getWriter().write(created.toString());
  }

  private void handleDeclPut(String clientId, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String id    = request.getParameter("id");
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    if (id == null || id.isEmpty()) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing param: id");
      return;
    }
    JSONObject body      = readJsonBody(request);
    boolean hasStatus    = body.has(STATUS_KEY);
    String status        = hasStatus ? body.getString(STATUS_KEY) : null;
    boolean hasFileExt   = body.has(FILE_EXTERNAL_KEY);
    boolean fileExternal = body.optBoolean(FILE_EXTERNAL_KEY, false);
    boolean hasFileName  = body.has(FILE_NAME_KEY);
    String  fileName     = hasFileName && !body.isNull(FILE_NAME_KEY)
        ? body.getString(FILE_NAME_KEY) : null;

    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, id);
    if (decl == null || !clientId.equals(decl.getClient().getId())
        || !orgId.equals(decl.getOrganization().getId())) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + id);
      return;
    }
    if (hasStatus)   decl.setDeclarationStatus(status);
    if (hasFileExt)  decl.setFileExternal(fileExternal);
    if (hasFileName) decl.setDeclarationFileName(fileName);
    decl.setUpdatedBy(OBContext.getOBContext().getUser());
    OBDal.getInstance().commitAndClose();
    response.getWriter().write("{\"ok\":true}");
  }

  private void handleDeclDelete(String clientId, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String id    = request.getParameter("id");
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    if (id == null || id.isEmpty()) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing param: id");
      return;
    }
    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, id);
    if (decl == null || !clientId.equals(decl.getClient().getId())
        || !orgId.equals(decl.getOrganization().getId())) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + id);
      return;
    }
    OBDal.getInstance().remove(decl);
    OBDal.getInstance().commitAndClose();
    response.getWriter().write("{\"ok\":true}");
  }

  JSONObject declToJson(FiscalDecl decl) throws Exception {
    JSONObject o = new JSONObject();
    o.put("id",           decl.getId() != null ? decl.getId() : "");
    o.put("model",        decl.getFiscalModel() != null ? decl.getFiscalModel() : "");
    o.put("year",         decl.getFiscalYear() != null ? decl.getFiscalYear().intValue() : 0);
    o.put(PERIOD_KEY,     decl.getPeriod() != null ? decl.getPeriod() : "");
    String dt = decl.getDeclarationType();
    String dtNormalized = dt != null ? dt.trim() : "";
    o.put("type",         "C".equals(dtNormalized) ? "com" : "ord");
    o.put(STATUS_KEY,     decl.getDeclarationStatus() != null
        ? decl.getDeclarationStatus() : DEFAULT_STATUS);
    o.put(FILE_NAME_KEY,  decl.getDeclarationFileName() != null
        ? decl.getDeclarationFileName() : JSONObject.NULL);
    o.put(FILE_EXTERNAL_KEY, Boolean.TRUE.equals(decl.isFileExternal()));
    o.put("updatedAt",    decl.getUpdated() != null ? decl.getUpdated().getTime() : 0L);
    return o;
  }

  private JSONObject readJsonBody(HttpServletRequest request) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    return new JSONObject(sb.toString());
  }
}
