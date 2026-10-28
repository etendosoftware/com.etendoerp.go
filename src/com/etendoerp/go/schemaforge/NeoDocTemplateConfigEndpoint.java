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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.BoundedJsonParser;
import com.etendoerp.go.common.MalformedJsonException;

/**
 * {@code GET|PUT /sws/neo/doctemplateconfig} (ETP-5988): the printable template choice of an
 * organization per document type, which the UI (T5) edits and the renderer (T6) reads.
 *
 * <ul>
 *   <li>{@code GET ?docType=<type>[&orgId=<org>]} answers one configuration, or the defaults with
 *       {@code configured:false} when none was saved, so a consumer never branches on a 404;</li>
 *   <li>{@code GET [?orgId=<org>]} answers {@code {orgId, configs:[...]}}, the saved ones only;</li>
 *   <li>{@code PUT {orgId?, docType, variant?, logo?, footerText?}} creates or updates the one of
 *       the organization and type. A key left out keeps what was saved (a new row starts from the
 *       defaults); {@code null} or an empty string clears the logo or the footer.</li>
 * </ul>
 *
 * <p><b>Isolation.</b> {@code orgId} defaults to the caller's organization. Reading needs the
 * organization to be one the caller's role reads; saving needs a client administrator and an
 * organization the role writes. Anything else is a {@code 403}, which does not say whether the
 * organization exists. The client is never taken from the request. Organization {@code 0} (*) holds
 * no templates and is a {@code 400}.</p>
 */
class NeoDocTemplateConfigEndpoint {

  private static final Logger log = LogManager.getLogger(NeoDocTemplateConfigEndpoint.class);

  /** Body cap: the largest logo plus the room the rest of the fields need. */
  static final int MAX_BODY_BYTES = DocTemplateConfigSupport.MAX_LOGO_DATA_LENGTH + 64 * 1024;

  private static final int MAX_JSON_DEPTH = 8;
  private static final String ROOT_ORG_ID = "0";
  private static final String K_ORG_ID = "orgId";
  private static final String K_DOC_TYPE = "docType";
  private static final String K_VARIANT = "variant";
  private static final String K_LOGO = "logo";
  private static final String K_FOOTER = "footerText";
  private static final String MSG_FORBIDDEN = "Not allowed to access the template configuration "
      + "of this organization";

  private final DocTemplateConfigStore store;
  private final DocTemplateConfigAccess access;

  NeoDocTemplateConfigEndpoint() {
    this(new DocTemplateConfigStore(), new DocTemplateConfigAccess());
  }

  NeoDocTemplateConfigEndpoint(DocTemplateConfigStore store, DocTemplateConfigAccess access) {
    this.store = store;
    this.access = access;
  }

  /**
   * @param request a GET or a PUT
   * @return the answer; 400 invalid input, 403 not allowed, 405 other methods, 413 body too large
   */
  NeoResponse handle(HttpServletRequest request) throws IOException {
    try {
      if ("GET".equals(request.getMethod())) {
        return read(request);
      }
      if ("PUT".equals(request.getMethod())) {
        return save(request);
      }
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Doctemplateconfig endpoint supports GET and PUT");
    } catch (JSONException e) {
      log.error("The document template configuration cannot be serialized", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "The template configuration cannot be read");
    }
  }

  private NeoResponse read(HttpServletRequest request) throws JSONException {
    String orgId = orgOf(request.getParameter(K_ORG_ID));
    NeoResponse refusal = orgRefusal(orgId, access.canRead(orgId));
    if (refusal != null) {
      return refusal;
    }
    String docType = request.getParameter(K_DOC_TYPE);
    if (StringUtils.isBlank(docType)) {
      return NeoResponse.ok(listJson(orgId, store.list(orgId)));
    }
    String error = DocTemplateConfigSupport.docTypeError(docType);
    if (error != null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, error);
    }
    DocTemplateConfig saved = store.find(orgId, docType);
    return NeoResponse.ok((saved != null ? saved : DocTemplateConfig.defaults(orgId, docType))
        .toJson());
  }

  private NeoResponse save(HttpServletRequest request) throws IOException, JSONException {
    if (!access.isAdmin()) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN, MSG_FORBIDDEN);
    }
    Optional<byte[]> raw = readCapped(request);
    if (raw.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
          "Body exceeds " + MAX_BODY_BYTES + " bytes");
    }
    JSONObject body = parse(new String(raw.get(), StandardCharsets.UTF_8));
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Body must be a JSON object");
    }
    String orgId = orgOf(text(body, K_ORG_ID));
    NeoResponse refusal = orgRefusal(orgId, access.canWrite(orgId));
    if (refusal != null) {
      return refusal;
    }
    String docType = text(body, K_DOC_TYPE);
    String error = DocTemplateConfigSupport.docTypeError(docType);
    if (error != null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, error);
    }
    String typeError = nonStringError(body);
    if (typeError != null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, typeError);
    }
    DocTemplateConfig current = store.find(orgId, docType);
    String variant = pick(body, K_VARIANT, current == null ? DocTemplateConfigSupport.DEFAULT_VARIANT
        : current.getVariant(), false);
    String logo = pick(body, K_LOGO, current == null ? null : current.getLogo(), true);
    String footer = pick(body, K_FOOTER, current == null ? null : current.getFooterText(), true);
    error = firstNonNull(DocTemplateConfigSupport.variantError(variant),
        DocTemplateConfigSupport.logoError(logo), DocTemplateConfigSupport.footerError(footer));
    if (error != null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, error);
    }
    return NeoResponse.ok(store.save(orgId, docType, variant, logo, footer).toJson());
  }

  /** The value the body sets for the key, the saved one when the key is left out. */
  private static String pick(JSONObject body, String key, String saved, boolean clearable) {
    if (!body.has(key)) {
      return saved;
    }
    String value = text(body, key);
    return clearable ? DocTemplateConfigSupport.normalize(value) : value;
  }

  /** A field that is present must be a string or null: a number must not clear a logo by accident. */
  private static String nonStringError(JSONObject body) {
    for (String key : new String[] { K_DOC_TYPE, K_VARIANT, K_LOGO, K_FOOTER }) {
      Object value = body.opt(key);
      if (body.has(key) && value != null && !JSONObject.NULL.equals(value)
          && !(value instanceof String)) {
        return key + " must be a string";
      }
    }
    return null;
  }

  /** The organization asked for, or the caller's own when the request names none. */
  private String orgOf(String requested) {
    return StringUtils.isBlank(requested) ? access.currentOrgId() : requested.trim();
  }

  /** @return the refusal to answer with, or null when the organization may be used */
  private static NeoResponse orgRefusal(String orgId, boolean allowed) {
    if (ROOT_ORG_ID.equals(orgId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Organization * has no template configuration");
    }
    return allowed ? null : NeoResponse.error(HttpServletResponse.SC_FORBIDDEN, MSG_FORBIDDEN);
  }

  private static JSONObject listJson(String orgId, List<DocTemplateConfig> configs)
      throws JSONException {
    JSONArray array = new JSONArray();
    for (DocTemplateConfig config : configs) {
      array.put(config.toJson());
    }
    return new JSONObject().put(K_ORG_ID, orgId).put("configs", array);
  }

  private static String firstNonNull(String... errors) {
    for (String error : errors) {
      if (error != null) {
        return error;
      }
    }
    return null;
  }

  /** A JSON string value; null when absent, JSON null or not a string. */
  private static String text(JSONObject json, String key) {
    Object value = json.opt(key);
    return value instanceof String ? (String) value : null;
  }

  private static JSONObject parse(String body) {
    if (StringUtils.isBlank(body)) {
      return null;
    }
    try {
      return BoundedJsonParser.parseObject(body, MAX_JSON_DEPTH);
    } catch (MalformedJsonException e) {
      return null;
    }
  }

  private static Optional<byte[]> readCapped(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      return Optional.empty();
    }
    try (InputStream in = request.getInputStream()) {
      byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
      return bytes.length > MAX_BODY_BYTES ? Optional.empty() : Optional.of(bytes);
    }
  }
}
