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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.OrganizationInformation;

/**
 * The file-name, contact and org-data resolution cluster used to build the {@code inputParams}
 * for the AEAT349 electronic-file generation call — on behalf of {@link Fiscal349BoxesHandler},
 * which holds one instance of this class ({@code generateSupport}) and delegates to it from
 * {@code handleGenerate(...)}.
 *
 * <p>Extracted verbatim from {@link Fiscal349BoxesHandler} (ETP-5456) purely to keep that class's
 * method count under the SonarQube {@code java:S1448} threshold, mirroring how the "sources" and
 * "submission" concerns were already split out of {@code Fiscal303BoxesHandler} into
 * {@link Fiscal303SourcesSupport}/{@link Fiscal303SubmissionSupport}, and the "VIES validation"
 * concern out of this same class into {@link Fiscal349ViesSupport}. This class is exactly the
 * "generate inputParams" concern: filename resolution, contact/phone fallback resolution and
 * optional-text-parameter handling — none of these methods read or write any
 * {@link Fiscal349BoxesHandler} instance state, so unlike {@link Fiscal303SourcesSupport}/
 * {@link Fiscal303SubmissionSupport} this class does not need an {@code owner} back-reference at
 * all.</p>
 */
class Fiscal349GenerateSupport {

  // FileName: request param wins when provided, else fall back to the locally-computed default.
  String resolveFileName(HttpServletRequest request, String period, int year) {
    String requestedFileName = request.getParameter("fileName");
    if (requestedFileName != null && !requestedFileName.isEmpty()) {
      return requestedFileName;
    }
    return "349_" + period + "_" + year;
  }

  // Extracted from handleGenerate to keep its cognitive complexity within budget.
  Map<String, String> buildGenerateInputParams(HttpServletRequest request, String orgId,
      String filename) {
    Map<String, String> inputParams = new HashMap<>();
    inputParams.put("FileName", filename);

    // Substitutive/Navarra/Guipuzcoa are checkbox parameters: AEAT3492010Report's generateLine1
    // calls inputParams.get("Substitutive").equals("Y") — NPE if the key is absent — so all three
    // must ALWAYS be present in the map, "Y" or "N", mirroring classic's CHECK-type convention
    // (OBTL_TaxReportLauncher#generateFile always writes CHECK params regardless of value).
    inputParams.put("Substitutive", "Y".equals(request.getParameter("substitutive")) ? "Y" : "N");
    inputParams.put("Navarra",      "Y".equals(request.getParameter("navarra"))      ? "Y" : "N");
    inputParams.put("Guipuzcoa",    "Y".equals(request.getParameter("guipuzcoa"))    ? "Y" : "N");

    applyContactParams(request, orgId, inputParams);
    applyOptionalTextParams(request, inputParams);
    return inputParams;
  }

  // Phone and Contact: AEAT3492010Report checks constantParameters first (TaxReport config),
  // then falls back to inputParams. Query params override; fall back to AD_OrgInformation /
  // current user so generation works even without TaxReport pre-configuration.
  void applyContactParams(HttpServletRequest request, String orgId,
      Map<String, String> inputParams) {
    String phone   = request.getParameter("phone");
    String contact = request.getParameter("contact");
    if (phone == null || phone.isEmpty()) {
      phone = resolveOrgPhone(orgId);
    }
    if (contact == null || contact.isEmpty()) {
      contact = resolveCurrentUserContactName();
    }
    if (phone   != null && !phone.isEmpty())   inputParams.put("Phone",   phone);
    if (contact != null && !contact.isEmpty()) inputParams.put("Contact", contact);
  }

  /**
   * The current session's AD_User display name — the {@code contact} fallback both
   * {@link #applyContactParams} (generation time) and {@link Fiscal349BoxesHandler#computeOperators}'s
   * {@code contactFallback} (read-only, for the frontend's pre-generation validation) resolve to.
   * Extracted so both call sites share the exact same one-liner instead of each repeating it.
   */
  static String resolveCurrentUserContactName() {
    return OBContext.getOBContext().getUser().getName();
  }

  // FormerStatement/RepresentativeTaxId are TEXT parameters — classic omits empty TEXT
  // parameters from inputParams entirely (OBTL_TaxReportLauncher#generateFile), so mirror
  // that here rather than sending an empty string.
  void applyOptionalTextParams(HttpServletRequest request, Map<String, String> inputParams) {
    String formerStatement     = request.getParameter("formerStatement");
    String representativeTaxId = request.getParameter("representativeTaxId");
    if (formerStatement != null && !formerStatement.isEmpty()) {
      inputParams.put("FormerStatement", formerStatement);
    }
    if (representativeTaxId != null && !representativeTaxId.isEmpty()) {
      inputParams.put("RepresentativeTaxId", representativeTaxId);
    }
  }

  // ── resolution helpers ────────────────────────────────────────────

  String resolveOrgNif(String orgId) {
    OBCriteria<OrganizationInformation> crit =
        OBDal.getInstance().createCriteria(OrganizationInformation.class);
    crit.add(Restrictions.in(OrganizationInformation.PROPERTY_ORGANIZATION + ".id",
        Arrays.asList(orgId, "0")));
    crit.addOrder(Order.desc(OrganizationInformation.PROPERTY_ORGANIZATION + ".id"));
    crit.setMaxResults(1);
    List<OrganizationInformation> list = crit.list();
    if (list.isEmpty()) return "";
    String taxId = list.get(0).getTaxID();
    return taxId != null ? taxId : "";
  }

  String resolveOrgPhone(String orgId) {
    OBCriteria<OrganizationInformation> crit =
        OBDal.getInstance().createCriteria(OrganizationInformation.class);
    crit.add(Restrictions.eq(OrganizationInformation.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.setMaxResults(1);
    List<OrganizationInformation> list = crit.list();
    if (list.isEmpty()) return null;
    // OrganizationInformation has no phone directly; try the org's user contact phone
    org.openbravo.model.ad.access.User contact = list.get(0).getUserContact();
    if (contact == null) return null;
    String phone = contact.getPhone();
    return phone != null && !phone.isEmpty() ? phone : contact.getAlternativePhone();
  }
}
