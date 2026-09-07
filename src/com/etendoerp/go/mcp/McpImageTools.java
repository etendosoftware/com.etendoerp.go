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

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.utility.Image;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.util.NeoImageHelper;
import com.etendoerp.go.schemaforge.util.NeoImageUploadTickets;

/**
 * The MCP image-upload tools (ETP-5184): {@code neo_request_image_upload} (primary),
 * {@code neo_upload_image} (base64 fallback) and {@code neo_get_image_upload} (status lookup).
 *
 * <p><b>Why two write tools and not one.</b> A tool argument is model <i>output</i>, generated token
 * by token; no MCP client elides argument content, and there is no way to mark an argument as
 * not-for-the-LLM. So base64 in an argument always costs output tokens — roughly 1.4 characters per
 * token, i.e. ~100k tokens for a 100 KB image. The only way not to pay is to not put the bytes in
 * the argument at all: {@code neo_request_image_upload} hands back a single-use URL, and whoever
 * holds the file PUTs it there over plain HTTP. The base64 tool stays because it is one code path
 * away (both end at {@link NeoImageHelper#createImage}) and it is the only option for a caller that
 * holds the bytes in memory with no shell — hence its deliberately low 256 KB cap, so nobody
 * discovers the token cost by paying it.
 *
 * <p>Neither tool wires the image to a record. That stays an explicit {@code neo_update} of the
 * image field, which is what keeps both tools generic across every {@code image}-typed field
 * (product, organization logo, anything enabled later) and keeps the audit trail obvious.
 */
final class McpImageTools {

  private static final Logger log = LogManager.getLogger(McpImageTools.class);

  private McpImageTools() {
    // utility class — no instances
  }

  static final String PARAM_NAME = "name";
  static final String PARAM_MIME_TYPE = "mime_type";
  static final String PARAM_DATA_BASE64 = "data_base64";
  static final String PARAM_TOKEN = "token";

  /** Relative path of the ticketed upload endpoint; joined to {@code context.url} when known. */
  static final String UPLOAD_PATH = "/sws/neo/image/upload/";
  private static final String OPENBRAVO_CONTEXT_URL = "context.url";
  private static final String OPENBRAVO_CONTEXT_NAME = "context.name";
  private static final String KEY_UPLOAD_URL = "uploadUrl";
  private static final String DEFAULT_IMAGE_NAME = "image";

  /**
   * The advice appended to every "too large for base64" rejection. Naming the cheap tool is what
   * makes the error self-correctable: the agent can act on it with no human in the loop.
   */
  static final String OVER_CAP_ADVICE =
      "Use " + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD + " instead: it returns a single-use "
      + "upload URL and a ready-to-run curl command, so the bytes never pass through the "
      + "conversation and there is no size problem. Only fall back to "
      + McpConstants.TOOL_NEO_UPLOAD_IMAGE + " for an image under 256 KB.";

  // ── neo_request_image_upload ──────────────────────────────────────────

  /**
   * Issues an upload ticket for the current MCP session and returns the URL to PUT the file to.
   *
   * <p>{@code curlExample} is part of the contract, not decoration: a ready-to-run command is what
   * makes the cheap path the obvious one for an agent that has a shell.
   */
  static JSONObject requestUpload(JSONObject args) throws JSONException {
    String name = StringUtils.defaultIfBlank(
        McpArgumentUtils.optionalString(args, PARAM_NAME), DEFAULT_IMAGE_NAME);
    String mimeType = StringUtils.trimToNull(
        McpArgumentUtils.optionalString(args, PARAM_MIME_TYPE));
    if (mimeType != null && !NeoImageHelper.ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase(Locale.ROOT))) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          "'" + PARAM_MIME_TYPE + "' must be one of "
              + String.join(", ", NeoImageHelper.ALLOWED_MIME_TYPES) + ", or omitted.",
          "Omit " + PARAM_MIME_TYPE + " and the type will be detected from the uploaded bytes.");
    }
    OBContext context = OBContext.getOBContext();
    String clientId = (String) context.getCurrentClient().getId();
    String orgId = (String) context.getCurrentOrganization().getId();
    String userId = (String) context.getUser().getId();
    NeoImageUploadTickets tickets = NeoImageUploadTickets.getInstance();
    String token;
    try {
      token = tickets.issue(clientId, orgId, userId, name, mimeType);
    } catch (NeoImageUploadTickets.TooManyPendingTicketsException e) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          e.getMessage(),
          "Upload the file for a ticket you already requested, or wait for one to expire.");
    }
    String uploadUrl = buildUploadUrl(token);
    JSONObject result = new JSONObject();
    result.put(PARAM_TOKEN, token);
    result.put(KEY_UPLOAD_URL, uploadUrl);
    // Read the expiry off the ticket itself rather than recomputing it here: one source of truth,
    // so a change to the TTL cannot make the advertised deadline disagree with the enforced one.
    result.put("expiresAt", tickets.peek(token)
        .map(ticket -> ticket.getExpiresAt().toString())
        .orElseGet(() -> Instant.now().plus(NeoImageUploadTickets.TTL).toString()));
    result.put("maxBytes", NeoImageHelper.MAX_IMAGE_SIZE_BYTES);
    result.put("acceptedMimeTypes", String.join(", ", NeoImageHelper.ALLOWED_MIME_TYPES));
    result.put("curlExample", "curl -X PUT --upload-file ./your-image.png \"" + uploadUrl + "\"");
    result.put(McpConstants.KEY_HINT, "PUT the raw image bytes to uploadUrl — no auth header is "
        + "needed, the URL itself is the credential, and it works exactly once. The response of "
        + "that PUT carries the imageId; if you miss it, call "
        + McpConstants.TOOL_NEO_GET_IMAGE_UPLOAD + " with this token. Then write the imageId to "
        + "the image field with neo_update. Do not read the file into this conversation.");
    return result;
  }

  /**
   * Absolute upload URL the CLIENT can reach, or a relative path when no base can be resolved.
   *
   * <p>The uploader is not this server: it is a shell, a browser or an agent that talks to the
   * Etendo Go app, so the URL has to be the app's public base — NOT {@code context.url}, which is
   * the internal backend address and may be unreachable (reverse proxy, tunnel, different host).
   * The app serves {@code /sws/*} on its own origin, so appending the servlet path to the app base
   * is what produces a working URL. Resolution order:
   *
   * <ol>
   *   <li>{@code etendo.go.app.baseUrl} — the app's public base, the same property the
   *       transactional emails use for their links.</li>
   *   <li>{@code context.url}, context path included, for an instance reached directly on Tomcat
   *       with no app in front.</li>
   *   <li>a context-relative path, so the answer is still usable by a client that resolves it
   *       against the origin it is already talking to.</li>
   * </ol>
   *
   * <p>Guessing a host would be worse than a relative path — an agent that PUTs to the wrong one
   * gets a network error instead of an upload.
   */
  private static String buildUploadUrl(String token) {
    String base = readAppBaseUrl();
    if (base == null) {
      base = readContextUrl();
    }
    return base == null
        ? contextPrefix() + UPLOAD_PATH + token
        : StringUtils.removeEnd(base, "/") + UPLOAD_PATH + token;
  }

  /**
   * The configured public base of the Etendo Go app, or {@code null}. Deliberately the
   * request-independent variant: a tool call has no {@link javax.servlet.http.HttpServletRequest}
   * to hand, and deriving a base from one would be wrong here anyway — behind the app's proxy the
   * request carries Tomcat's own context path, which the app does not serve {@code /sws} under.
   */
  private static String readAppBaseUrl() {
    try {
      return StringUtils.trimToNull(PublicUrlResolver.resolveConfiguredAppBaseUrl());
    } catch (Exception e) {
      log.debug("Could not resolve the app base URL: {}", e.getMessage());
      return null;
    }
  }

  private static String readContextUrl() {
    try {
      String raw = OBPropertiesProvider.getInstance().getOpenbravoProperties()
          .getProperty(OPENBRAVO_CONTEXT_URL);
      // Returned AS-IS, context path included. UPLOAD_PATH is relative to the context (the servlet
      // is mapped at /sws/neo/* INSIDE it), so context.url = http://host:8080/etendo yields
      // http://host:8080/etendo/sws/neo/image/upload/{token}. Stripping the context name here — as
      // this method used to — produced http://host:8080/sws/neo/... which Tomcat answers with a 405
      // from its default servlet, never reaching NeoServlet.
      return StringUtils.trimToNull(raw);
    } catch (Exception e) {
      log.debug("Could not read {}: {}", OPENBRAVO_CONTEXT_URL, e.getMessage());
      return null;
    }
  }

  /**
   * @return {@code /context.name} when that property is set, or {@code ""}. Used only to prefix the
   *     relative fallback below: without it a host-absolute path would miss the context and hit
   *     Tomcat's default servlet instead of NeoServlet.
   */
  private static String contextPrefix() {
    try {
      String name = StringUtils.trimToNull(OBPropertiesProvider.getInstance()
          .getOpenbravoProperties().getProperty(OPENBRAVO_CONTEXT_NAME));
      return name == null ? "" : "/" + StringUtils.strip(name, "/");
    } catch (Exception e) {
      return "";
    }
  }

  // ── neo_upload_image ──────────────────────────────────────────────────

  /**
   * Creates an {@code AD_Image} row from an inline base64 payload.
   *
   * <p>Validation order — present/non-blank, {@code data:} prefix stripped, decodable, under the
   * 256 KB decoded cap, sniffable as PNG/JPEG, consistent with any declared {@code mime_type} — is
   * fixed and asserted by tests. The last step is the security-relevant one: without the magic-byte
   * cross-check, a lying {@code mime_type} would let an arbitrary blob be stored in an image column.
   */
  static JSONObject uploadImage(JSONObject args) throws JSONException {
    String name = StringUtils.defaultIfBlank(
        McpArgumentUtils.optionalString(args, PARAM_NAME), DEFAULT_IMAGE_NAME);
    String declaredMime = StringUtils.trimToNull(
        McpArgumentUtils.optionalString(args, PARAM_MIME_TYPE));
    String dataBase64 = McpArgumentUtils.optionalString(args, PARAM_DATA_BASE64);
    try {
      byte[] data = NeoImageHelper.decodeBase64Image(dataBase64,
          McpConstants.IMAGE_BASE64_MAX_BYTES, OVER_CAP_ADVICE);
      String mimeType = NeoImageHelper.validateImageBytes(data, declaredMime,
          McpConstants.IMAGE_BASE64_MAX_BYTES, OVER_CAP_ADVICE);
      Image image = NeoImageHelper.createImage(name, mimeType, data);
      JSONObject result = NeoImageHelper.describeImage(image, data);
      result.put(McpConstants.KEY_HINT, "The image row exists but is not attached to anything yet. "
          + "Write this imageId to the image field of the record you want it on, with neo_update.");
      return result;
    } catch (NeoImageHelper.ImageValidationException e) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          e.getMessage(), hintFor(e.getReason()));
    }
  }

  /** The remedy that goes with each validation reason — one sentence the agent can act on. */
  private static String hintFor(String reason) {
    switch (reason) {
      case "too_large":
        return OVER_CAP_ADVICE;
      case "unsupported_mime":
        return "Convert the image to PNG or JPEG and resend it.";
      case "mime_mismatch":
        return "Omit " + PARAM_MIME_TYPE + " so the type is detected, or declare the real one.";
      case "malformed_base64":
        return "Re-encode the file as base64 and send just that string in " + PARAM_DATA_BASE64 + ".";
      default:
        return "Send the image as base64 in " + PARAM_DATA_BASE64 + ", or use "
            + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD + " to upload it out of band.";
    }
  }

  // ── neo_get_image_upload ──────────────────────────────────────────────

  /**
   * Reads the status of a ticket this session owns.
   *
   * <p>Exists for one recoverable situation: the {@code imageId} travels in the PUT's own response,
   * and an agent that shelled out may not have captured it.
   *
   * <p>A ticket belonging to another session answers exactly like an unknown one. Cross-session
   * visibility would leak both the existence of a token and the id of an image the caller has no
   * claim to, and an agent's remedy is the same either way: request its own ticket.
   */
  static JSONObject getUpload(JSONObject args) throws JSONException {
    String token = StringUtils.trimToNull(McpArgumentUtils.optionalString(args, PARAM_TOKEN));
    if (token == null) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          "'" + PARAM_TOKEN + "' is required.",
          "Pass the token " + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD + " returned.");
    }
    OBContext context = OBContext.getOBContext();
    Optional<NeoImageUploadTickets.PendingUpload> found =
        NeoImageUploadTickets.getInstance().peek(token)
            .filter(ticket -> ticket.belongsTo(
                (String) context.getCurrentClient().getId(),
                (String) context.getCurrentOrganization().getId(),
                (String) context.getUser().getId()));
    if (found.isEmpty()) {
      return errorEnvelope(McpConstants.STATUS_NOT_FOUND, McpConstants.ERROR_NOT_FOUND,
          "This upload token is not valid, or has expired.",
          "Request a new one with " + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD
              + " and upload the file again.");
    }
    NeoImageUploadTickets.PendingUpload ticket = found.get();
    boolean completed = ticket.getStatus() == NeoImageUploadTickets.Status.COMPLETED;
    JSONObject result = new JSONObject();
    result.put(McpConstants.KEY_STATUS, completed ? "completed" : "pending");
    result.put("expiresAt", ticket.getExpiresAt().toString());
    if (completed && ticket.getImageId() != null) {
      result.put("imageId", ticket.getImageId());
      result.put(McpConstants.KEY_HINT, "Write this imageId to the image field with neo_update.");
    } else {
      result.put(KEY_UPLOAD_URL, buildUploadUrl(token));
      result.put(McpConstants.KEY_HINT,
          "Nothing has been uploaded yet. PUT the raw image bytes to uploadUrl.");
    }
    return result;
  }

  /** The IMP-5 envelope shape every other MCP error uses, so an agent parses one set of keys. */
  private static JSONObject errorEnvelope(int status, String code, String detail, String hint)
      throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, status);
    error.put(McpConstants.KEY_ERROR, code);
    error.put(McpConstants.KEY_DETAIL, detail);
    error.put(McpConstants.KEY_HINT, hint);
    error.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    return error;
  }
}
