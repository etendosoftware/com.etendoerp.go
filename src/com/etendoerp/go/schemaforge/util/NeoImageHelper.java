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

package com.etendoerp.go.schemaforge.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Static helpers for the image endpoints, and the one place an {@code AD_Image} row is created.
 *
 * <p>Three callers share {@link #createImage}: the servlet {@code POST /sws/neo/image} the React
 * {@code ImageField.jsx} uses, the MCP {@code neo_upload_image} base64 fallback, and the one-shot
 * ticket endpoint {@code PUT /sws/neo/image/upload/{token}} (ETP-5184). Byte validation
 * ({@link #validateImageBytes}) is deliberately NOT part of {@code createImage}: the servlet
 * endpoint predates it and accepts any MIME up to 10 MB, and tightening that would change the
 * behaviour of a shipped UI. The MCP paths call the validator explicitly, with their own — much
 * lower — cap.
 */
public final class NeoImageHelper {

  private static final Logger log = LogManager.getLogger(NeoImageHelper.class);
  /**
   * Body cap of the servlet POST endpoint and of the ticketed PUT — 10 MB, the value
   * {@code ImageField.jsx} has always been sized against. The MCP base64 fallback uses its own,
   * far lower cap ({@code McpConstants.IMAGE_BASE64_MAX_BYTES}); see that constant for why.
   */
  public static final int MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;

  /** MIME types the MCP upload paths accept. Both are sniffable from magic bytes. */
  public static final String MIME_PNG = "image/png";
  public static final String MIME_JPEG = "image/jpeg";
  /** The allowlist, in the order it is reported to a caller that got it wrong. */
  public static final List<String> ALLOWED_MIME_TYPES = List.of(MIME_PNG, MIME_JPEG);

  private static final byte[] MAGIC_PNG = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
  private static final byte[] MAGIC_JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

  /**
   * The single answer to every unusable upload token. One sentence for unknown, expired, forged and
   * already-used alike — see {@link #handleUploadTicketRequest} for why they must not be
   * distinguishable.
   */
  /**
   * Path of the ticketed upload endpoint, relative to the NEO servlet mount — the single source of
   * truth for it. {@code NeoServlet} routes on this prefix and {@code McpImageTools} advertises the
   * URL built from it; a literal in each would let the advertised URL drift from the served route,
   * and the only symptom would be an agent PUTting to a 404.
   */
  public static final String UPLOAD_TICKET_PATH = "/image/upload/";

  /**
   * The one {@link ImageValidationException#getReason() reason} callers branch on: it is the only
   * validation failure that maps to a different HTTP status (413 rather than 400), so the string is
   * both thrown and compared and must not drift between the two.
   */
  public static final String REASON_TOO_LARGE = "too_large";

  static final String INVALID_UPLOAD_LINK_MESSAGE =
      "This upload link is not valid, has already been used, or has expired. Request a new one with "
      + "neo_request_image_upload.";

  private NeoImageHelper() {
  }

  /**
   * A caller-fixable problem with the bytes offered for upload.
   *
   * <p>Carries a machine-readable {@link #getReason() reason} alongside the message, so an MCP tool
   * can build a self-correctable envelope without parsing prose, and an HTTP endpoint can answer 400
   * or 413 from the same throw.
   */
  public static class ImageValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Machine-readable reason: {@code empty}, {@code too_large}, {@code unsupported_mime},
     *  {@code mime_mismatch} or {@code malformed_base64}. */
    private final String reason;

    /**
     * @param reason  machine-readable reason: {@code empty}, {@code too_large},
     *                {@code unsupported_mime}, {@code mime_mismatch} or {@code malformed_base64}
     * @param message the sentence handed to the caller. Every reason here is caller-correctable, so
     *                this is expected to say what to change and retry with
     */
    public ImageValidationException(String reason, String message) {
      super(message);
      this.reason = reason;
    }

    public String getReason() {
      return reason;
    }
  }

  /**
   * Detects what an image payload really is, from its leading magic bytes.
   *
   * @param data the raw bytes; {@code null}, or shorter than the magic sequence, yields
   *     {@code null}
   * @return the MIME type the bytes actually are ({@link #MIME_PNG} / {@link #MIME_JPEG}), or
   *     {@code null} when they are neither. Magic bytes only — the caller's declared MIME is never
   *     trusted, because a lying {@code mime_type} is exactly how an arbitrary blob would get stored
   *     in an image column.
   */
  public static String sniffMimeType(byte[] data) {
    if (data == null) {
      return null;
    }
    if (startsWith(data, MAGIC_PNG)) {
      return MIME_PNG;
    }
    return startsWith(data, MAGIC_JPEG) ? MIME_JPEG : null;
  }

  private static boolean startsWith(byte[] data, byte[] magic) {
    return data.length >= magic.length
        && Arrays.equals(Arrays.copyOf(data, magic.length), magic);
  }

  /**
   * Validates bytes destined for an {@code AD_Image} row on one of the MCP paths.
   *
   * <p>Order matters and is asserted by tests: empty → over the cap → sniffable as an allowed image
   * → consistent with what the caller declared. The last check is the security-relevant one: without
   * it a caller could store an HTML or PDF payload in an image column simply by declaring
   * {@code mime_type: "image/png"}.
   *
   * @param data              the decoded bytes
   * @param declaredMimeType  what the caller said the bytes are; {@code null}/blank means "sniff it"
   * @param maxBytes          hard cap for this path
   * @param overCapAdvice     appended to the too-large message — each path routes the caller
   *                          somewhere different, and the advice is what makes the error
   *                          self-correctable
   * @return the sniffed, authoritative MIME type
   * @throws ImageValidationException on any of the above
   */
  public static String validateImageBytes(byte[] data, String declaredMimeType, int maxBytes,
      String overCapAdvice) {
    if (data == null || data.length == 0) {
      throw new ImageValidationException("empty", "No image bytes were received.");
    }
    if (data.length > maxBytes) {
      throw new ImageValidationException(REASON_TOO_LARGE, "The image is " + data.length
          + " bytes, over the " + maxBytes + "-byte limit of this path. "
          + StringUtils.defaultString(overCapAdvice));
    }
    String sniffed = sniffMimeType(data);
    if (sniffed == null) {
      throw new ImageValidationException("unsupported_mime",
          "These bytes are not a PNG or a JPEG. Only " + String.join(" and ", ALLOWED_MIME_TYPES)
              + " are accepted; convert the image and retry.");
    }
    String declared = StringUtils.trimToNull(declaredMimeType);
    if (declared != null && !sniffed.equalsIgnoreCase(declared)) {
      throw new ImageValidationException("mime_mismatch", "The bytes are " + sniffed
          + " but were declared as " + declared
          + ". Send the correct mime_type, or omit it and it will be detected.");
    }
    return sniffed;
  }

  /**
   * Decodes a base64 payload, tolerating a {@code data:image/png;base64,} prefix.
   *
   * <p>{@code maxBytes} is pre-checked against the ENCODED length (base64 inflates by 4/3) so a
   * caller cannot make the server materialise a huge array just to have it rejected.
   *
   * @param dataBase64    the base64 text, with or without a {@code data:...;base64,} prefix;
   *                      whitespace is stripped before decoding
   * @param maxBytes      hard cap on the DECODED size, pre-checked against the encoded length
   * @param overCapAdvice appended to the too-large message, to route the caller somewhere that
   *                      accepts the file (the ticketed PUT, for the MCP base64 path)
   * @return the decoded bytes. Their type is NOT checked here — call
   *     {@link #validateImageBytes} for that
   * @throws ImageValidationException with reason {@code empty} when nothing was sent,
   *     {@code too_large} when the payload is over the cap, or {@code malformed_base64} when it
   *     does not decode
   */
  public static byte[] decodeBase64Image(String dataBase64, int maxBytes, String overCapAdvice) {
    String payload = StringUtils.trimToNull(dataBase64);
    if (payload == null) {
      throw new ImageValidationException("empty",
          "No image data was provided. Send the image as base64 in 'data_base64'.");
    }
    int comma = payload.indexOf(',');
    if (StringUtils.startsWithIgnoreCase(payload, "data:") && comma >= 0) {
      payload = payload.substring(comma + 1);
    }
    payload = payload.replaceAll("\\s", "");
    // 4 base64 chars carry 3 bytes; reject before allocating when the encoding alone is over cap.
    if (payload.length() / 4L * 3L > maxBytes) {
      throw new ImageValidationException(REASON_TOO_LARGE, "The base64 payload decodes to more than the "
          + maxBytes + "-byte limit of this path. " + StringUtils.defaultString(overCapAdvice));
    }
    try {
      return Base64.getDecoder().decode(payload);
    } catch (IllegalArgumentException e) {
      throw new ImageValidationException("malformed_base64", "'data_base64' is not valid base64: "
          + e.getMessage() + ". Send the raw base64 of the file, optionally with a "
          + "'data:image/png;base64,' prefix, and nothing else.");
    }
  }

  /**
   * Creates the {@code AD_Image} row and flushes it, in the current client/organization.
   *
   * <p>Extracted verbatim from {@code handlePostImage} (ETP-5184) so the servlet endpoint, the MCP
   * base64 tool and the ticket endpoint cannot drift apart. No validation here — see the class
   * javadoc for why.
   *
   * @param name     name for the row; blank falls back to {@code "image"}
   * @param mimeType MIME type to record; blank falls back to {@link #MIME_PNG}
   * @param data     the image bytes, stored as-is
   * @return the persisted image, with its generated id available
   */
  public static Image createImage(String name, String mimeType, byte[] data) {
    return createImage(name, mimeType, data,
        OBContext.getOBContext().getCurrentClient(),
        OBContext.getOBContext().getCurrentOrganization());
  }

  /**
   * Creates the {@code AD_Image} row in an explicitly given client/organization.
   *
   * <p>The ticket endpoint needs this overload: it runs unauthenticated (the token is the
   * credential), so there is no session context to read the scope from — the scope comes from the
   * ticket that was issued to an authenticated MCP session.
   *
   * @param name         name for the row; blank falls back to {@code "image"}
   * @param mimeType     MIME type to record; blank falls back to {@link #MIME_PNG}
   * @param data         the image bytes, stored as-is
   * @param client       client the row is created in
   * @param organization organization the row is created in
   * @return the persisted and flushed image, with its generated id available
   */
  public static Image createImage(String name, String mimeType, byte[] data, Client client,
      Organization organization) {
    Image image = OBProvider.getInstance().get(Image.class);
    image.setClient(client);
    image.setOrganization(organization);
    image.setName(StringUtils.defaultIfBlank(name, "image"));
    image.setMimetype(StringUtils.defaultIfBlank(mimeType, MIME_PNG));
    image.setBindaryData(data);
    OBDal.getInstance().save(image);
    OBDal.getInstance().flush();
    return image;
  }

  /**
   * The {@code {imageId, name, mimeType, bytes, width, height}} body every upload path returns —
   * one shape, so an agent parses one response whichever path it took.
   *
   * <p>{@code width}/{@code height} are omitted rather than guessed when {@code ImageIO} cannot read
   * the stream: they are informational, and a wrong dimension is worse than an absent one.
   *
   * @param image the persisted row, read for its id, name and MIME type
   * @param data  the bytes that were stored, used for {@code bytes} and for the dimension probe;
   *              {@code null} reports {@code bytes: 0} and omits the dimensions
   * @return the response body described above
   * @throws JSONException if a value cannot be written into the body
   */
  public static JSONObject describeImage(Image image, byte[] data) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("imageId", image.getId());
    result.put("name", image.getName());
    result.put("mimeType", image.getMimetype());
    result.put("bytes", data == null ? 0 : data.length);
    int[] dimensions = readDimensions(data);
    if (dimensions != null) {
      result.put("width", dimensions[0]);
      result.put("height", dimensions[1]);
    }
    return result;
  }

  /**
   * Probes an image's pixel dimensions with {@link ImageIO}, without throwing: a payload no reader
   * understands is reported as absent rather than as an error, because the dimensions are
   * informational.
   *
   * @param data the image bytes; {@code null} or empty yields {@code null}
   * @return {@code {width, height}}, or {@code null} when the bytes cannot be decoded.
   */
  public static int[] readDimensions(byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    try (InputStream in = new ByteArrayInputStream(data)) {
      BufferedImage decoded = ImageIO.read(in);
      return decoded == null ? null : new int[] { decoded.getWidth(), decoded.getHeight() };
    } catch (IOException | RuntimeException e) {
      log.debug("Could not read image dimensions: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Dispatches an image request to either the GET (retrieve image bytes) or POST (upload image)
   * handler based on the HTTP method, writing the result directly to the servlet response.
   *
   * @param imageId  the ID of the {@link Image} record to retrieve; used only for GET requests
   * @param method   the HTTP method string ({@code "GET"} or {@code "POST"})
   * @param request  the HTTP servlet request, used to read the POST body
   * @param response the HTTP servlet response to which the image data or JSON result is written
   * @throws IOException if an I/O error occurs while reading the request or writing the response
   */
  public static void handleImageRequest(String imageId, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      if ("GET".equals(method)) {
        handleGetImage(imageId, response);
      } else if ("POST".equals(method)) {
        handlePostImage(request, response);
      } else {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Image endpoint only supports GET and POST");
      }
    } catch (Exception e) {
      log.error("Error handling image request", e);
      OBDal.getInstance().rollbackAndClose();
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Image request failed");
    }
  }

  private static void handleGetImage(String imageId, HttpServletResponse response) throws Exception {
    if (StringUtils.isBlank(imageId)) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Image ID required");
      return;
    }
    Image image = OBDal.getInstance().get(Image.class, imageId);
    if (image == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, "Image not found: " + imageId);
      return;
    }
    byte[] data = image.getBindaryData();
    if (data == null || data.length == 0) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, "Image has no data");
      return;
    }
    String mimeType = image.getMimetype();
    if (StringUtils.isBlank(mimeType)) {
      mimeType = "image/png";
    }
    response.setContentType(mimeType);
    response.setContentLength(data.length);
    try (OutputStream out = response.getOutputStream()) {
      out.write(data);
    }
  }

  private static void handlePostImage(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    byte[] rawBytes = request.getInputStream().readNBytes(MAX_IMAGE_SIZE_BYTES + 1);
    if (rawBytes.length > MAX_IMAGE_SIZE_BYTES) {
      sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Image exceeds 10 MB limit");
      return;
    }
    String bodyStr = new String(rawBytes, StandardCharsets.UTF_8);
    JSONObject body = new JSONObject(bodyStr);
    String name = body.optString("name", "image");
    String mimeType = body.optString("mimeType", "image/png");
    String dataBase64 = body.optString("data", "");
    if (StringUtils.isBlank(dataBase64)) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Image data required");
      return;
    }
    if (dataBase64.contains(",")) {
      dataBase64 = dataBase64.substring(dataBase64.indexOf(',') + 1);
    }
    byte[] imageBytes = Base64.getDecoder().decode(dataBase64);
    // ETP-5184: the row creation moved to createImage so three callers share it. This endpoint
    // still validates nothing beyond the 10 MB body cap above — ImageField.jsx relies on that.
    Image image = createImage(name, mimeType, imageBytes);
    JSONObject result = new JSONObject();
    result.put("imageId", image.getId());
    result.put("name", image.getName());
    writeJson(response, result);
  }

  /**
   * Serves {@code PUT /sws/neo/image/upload/{token}} — the out-of-band leg of the MCP upload ticket
   * (ETP-5184).
   *
   * <p><b>This endpoint is not authenticated, and the token is the credential.</b> That is the whole
   * point: whoever holds the file — a shell running {@code curl --upload-file}, or a person dropping
   * it in a browser — must be able to send the bytes without an MCP session, so the image never
   * passes through the model's context. What keeps it safe is the ticket, not a header: it is 192
   * bits of {@link java.security.SecureRandom}, single-use, valid for
   * {@link NeoImageUploadTickets#TTL}, and carries the client/organization/user of the MCP session it
   * was issued to — so the row can only land in that session's scope.
   *
   * <p>An unknown, expired, forged or already-used token all get the same 403 with the same
   * sentence. Distinguishing them would tell a caller whether a token it guessed ever existed, and
   * it would also make a Tomcat restart (which drops the in-memory store) look different from an
   * expiry, when the agent's remedy — request another ticket — is identical.
   *
   * @param token    the path segment after {@code /image/upload/}
   * @param method   the HTTP method; anything but {@code "PUT"} is answered 405 without touching
   *                 the ticket
   * @param request  the request whose body is the raw file bytes — not base64, not multipart
   * @param response the response the created image's description, or the error, is written to
   * @throws IOException if writing the response fails. Failures while reading the body or creating
   *     the row do not propagate: they release the ticket and are answered as an error status
   */
  public static void handleUploadTicketRequest(String token, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"PUT".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Upload the image with PUT to this URL, sending the raw file bytes as the body.");
      return;
    }
    NeoImageUploadTickets tickets = NeoImageUploadTickets.getInstance();
    NeoImageUploadTickets.PendingUpload ticket = tickets.claim(token).orElse(null);
    if (ticket == null) {
      sendError(response, HttpServletResponse.SC_FORBIDDEN, INVALID_UPLOAD_LINK_MESSAGE);
      return;
    }
    try {
      storeTicketedUpload(token, ticket, tickets, request, response);
    } catch (ImageValidationException e) {
      // A rejected payload must not burn the ticket: the remedy is to PUT the right file to the
      // same URL, and forcing a new ticket for a fixable mistake would cost the agent a round trip.
      tickets.release(token);
      sendError(response, REASON_TOO_LARGE.equals(e.getReason())
          ? HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
          : HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Ticketed image upload failed", e);
      tickets.release(token);
      OBDal.getInstance().rollbackAndClose();
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Image upload failed");
    }
  }

  /** The successful body of a ticketed PUT: validate, create, consume, answer. */
  private static void storeTicketedUpload(String token, NeoImageUploadTickets.PendingUpload ticket,
      NeoImageUploadTickets tickets, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    byte[] data = request.getInputStream().readNBytes(MAX_IMAGE_SIZE_BYTES + 1);
    if (data.length > MAX_IMAGE_SIZE_BYTES) {
      throw new ImageValidationException(REASON_TOO_LARGE,
          "The uploaded file exceeds the " + MAX_IMAGE_SIZE_BYTES + "-byte limit.");
    }
    String mimeType = validateImageBytes(data, ticket.getMimeType(), MAX_IMAGE_SIZE_BYTES,
        "Send a smaller image.");
    Client client = OBDal.getInstance().get(Client.class, ticket.getClientId());
    Organization organization = OBDal.getInstance()
        .get(Organization.class, ticket.getOrganizationId());
    if (client == null || organization == null) {
      // The ticket outlived its own scope (client/org deactivated or deleted). Indistinguishable
      // from an expiry to the caller, on purpose.
      tickets.complete(token, null);
      sendError(response, HttpServletResponse.SC_FORBIDDEN, INVALID_UPLOAD_LINK_MESSAGE);
      return;
    }
    Image image = createImage(ticket.getName(), mimeType, data, client, organization);
    tickets.complete(token, (String) image.getId());
    writeJson(response, describeImage(image, data));
  }

  /** Writes a JSON body with a 200, shared by the POST and ticketed-PUT paths. */
  private static void writeJson(HttpServletResponse response, JSONObject body) throws IOException {
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setStatus(HttpServletResponse.SC_OK);
    try (OutputStream out = response.getOutputStream()) {
      out.write(body.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    NeoResponse errorResponse = NeoResponse.error(status, message);
    response.setStatus(errorResponse.getHttpStatus());
    if (errorResponse.getBody() != null) {
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(errorResponse.getBody().toString());
    }
  }
}
