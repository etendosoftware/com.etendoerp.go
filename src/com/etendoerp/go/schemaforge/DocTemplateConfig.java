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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * One organization's printable template choice for one document type, as the
 * {@code doctemplateconfig} endpoint serves it to the UI (T5) and the renderer (T6).
 *
 * <p>{@code configured} tells a saved row from the defaults served when the organization has
 * none, so a consumer never has to branch on a 404.</p>
 */
final class DocTemplateConfig {

  private final String orgId;
  private final String docType;
  private final String variant;
  private final String logo;
  private final String footerText;
  private final DocTemplateStyle style;
  private final boolean configured;

  DocTemplateConfig(String orgId, String docType, String variant, String logo, String footerText,
      DocTemplateStyle style, boolean configured) {
    this.orgId = orgId;
    this.docType = docType;
    this.variant = variant;
    this.logo = logo;
    this.footerText = footerText;
    this.style = style == null ? DocTemplateStyle.NONE : style;
    this.configured = configured;
  }

  /** The configuration served when the organization has saved none for the document type. */
  static DocTemplateConfig defaults(String orgId, String docType) {
    return new DocTemplateConfig(orgId, docType, DocTemplateConfigSupport.DEFAULT_VARIANT, null,
        null, DocTemplateStyle.NONE, false);
  }

  String getOrgId() {
    return orgId;
  }

  String getDocType() {
    return docType;
  }

  String getVariant() {
    return variant;
  }

  String getLogo() {
    return logo;
  }

  String getFooterText() {
    return footerText;
  }

  DocTemplateStyle getStyle() {
    return style;
  }

  boolean isConfigured() {
    return configured;
  }

  /** The wire shape: {@code {orgId, docType, variant, logo, footerText, color, font, fontSize,
   * rowStripes, configured}}. */
  JSONObject toJson() throws JSONException {
    JSONObject json = new JSONObject();
    json.put("orgId", orgId);
    json.put("docType", docType);
    json.put("variant", variant);
    json.put("logo", logo == null ? JSONObject.NULL : logo);
    json.put("footerText", footerText == null ? JSONObject.NULL : footerText);
    style.writeTo(json);
    json.put("configured", configured);
    return json;
  }
}
