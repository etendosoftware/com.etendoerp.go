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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.Properties;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.util.NeoImageHelper;
import com.etendoerp.go.schemaforge.util.NeoImageUploadTickets;

/**
 * Unit tests for the MCP image-upload tools (ETP-5184).
 *
 * <p>Includes the documentation assertions the plan calls for: both tool descriptions must name the
 * cheap path and the 256 KB cap, so the guidance an agent reads cannot drift away from the
 * validation the server actually enforces.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpImageToolsTest {

  private static final String CLIENT = "CLIENT1";
  private static final String ORG = "ORG1";
  private static final String USER = "USER1";
  private static final String IMAGE_ID = "95E2A8B50A254B2AAE6774B8C2F28120";

  private static byte[] realPng() throws Exception {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(2, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  /** An OBContext reporting a fixed client/org/user, as {@code McpServlet} would have set up. */
  private static OBContext sessionContext() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn(ORG);
    User user = mock(User.class);
    when(user.getId()).thenReturn(USER);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    when(context.getCurrentOrganization()).thenReturn(organization);
    when(context.getUser()).thenReturn(user);
    return context;
  }

  /** Runs {@code body} with a live ticket store and a fixed session context. */
  private static <T> T withSession(NeoImageUploadTickets store, ThrowingSupplier<T> body)
      throws Exception {
    try (MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
        MockedStatic<NeoImageUploadTickets> ticketsMock = mockStatic(NeoImageUploadTickets.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
      contextMock.when(OBContext::getOBContext).thenReturn(sessionContext());
      ticketsMock.when(NeoImageUploadTickets::getInstance).thenReturn(store);
      return body.get();
    }
  }

  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  // ── Documentation assertions ──────────────────────────────────────────

  @Nested
  @DisplayName("tool descriptions")
  class ToolDescriptions {

    @Test
    @DisplayName("neo_request_image_upload advertises itself as the cheap path")
    void requestToolNamesTheCheapPath() {
      String description = ToolRegistry.REQUEST_IMAGE_UPLOAD_DESCRIPTION;
      assertTrue(description.contains("curl"), description);
      assertTrue(description.contains(McpConstants.TOOL_NEO_UPLOAD_IMAGE),
          "it must tell the agent what it is cheaper than: " + description);
      assertTrue(description.contains("never pass through the conversation"), description);
      assertTrue(description.contains("neo_update"),
          "the agent still has to write the id somewhere: " + description);
    }

    @Test
    @DisplayName("neo_upload_image states the cap and routes over-cap callers to the cheap path")
    void uploadToolStatesTheCapAndTheAlternative() {
      String description = ToolRegistry.UPLOAD_IMAGE_DESCRIPTION;
      assertTrue(description.contains("256 KB"),
          "the cap in the description must match the enforced one: " + description);
      assertTrue(description.contains(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD), description);
      assertTrue(description.contains("image/png") && description.contains("image/jpeg"),
          "the allowlist belongs in the description: " + description);
    }

    @Test
    @DisplayName("the documented cap is the enforced cap")
    void documentedCapMatchesTheConstant() {
      assertEquals(256 * 1024, McpConstants.IMAGE_BASE64_MAX_BYTES);
      assertTrue(ToolRegistry.UPLOAD_IMAGE_DESCRIPTION.contains("256 KB"));
    }

    @Test
    @DisplayName("neo_get_image_upload explains it is only for a lost PUT response")
    void statusToolExplainsItself() {
      String description = ToolRegistry.GET_IMAGE_UPLOAD_DESCRIPTION;
      assertTrue(description.contains(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD), description);
      assertTrue(description.contains("imageId"), description);
    }
  }

  // ── neo_request_image_upload ──────────────────────────────────────────

  @Nested
  @DisplayName("neo_request_image_upload")
  class RequestUpload {

    @Test
    @DisplayName("returns a URL, an expiry, a cap and a curl example that matches the URL")
    void curlExampleMatchesUploadUrl() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject result = withSession(store, () -> McpImageTools.requestUpload(new JSONObject()));

      String uploadUrl = result.getString("uploadUrl");
      assertTrue(uploadUrl.contains(McpImageTools.UPLOAD_PATH), uploadUrl);
      assertTrue(uploadUrl.endsWith(result.getString("token")),
          "the URL must end in the token it was issued for: " + uploadUrl);
      assertTrue(result.getString("curlExample").contains(uploadUrl),
          "the curl example must target the same URL: " + result.getString("curlExample"));
      assertTrue(result.getString("curlExample").contains("--upload-file"),
          "the example must be runnable as-is: " + result.getString("curlExample"));
      assertEquals(NeoImageHelper.MAX_IMAGE_SIZE_BYTES, result.getInt("maxBytes"));
      assertNotNull(result.getString("expiresAt"));
      assertFalse(result.has(McpConstants.KEY_ERROR));
    }

    /** Runs {@code body} with a live store, a session, and exactly these Openbravo properties. */
    private <T> T withProperties(NeoImageUploadTickets store, Properties props,
        ThrowingSupplier<T> body) throws Exception {
      OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
      when(provider.getOpenbravoProperties()).thenReturn(props);
      try (MockedStatic<OBPropertiesProvider> propsMock = mockStatic(OBPropertiesProvider.class)) {
        propsMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
        return withSession(store, body);
      }
    }

    @Test
    @DisplayName("the upload URL is built on the Go app's public base, not on context.url")
    void uploadUrlUsesTheAppBase() throws Exception {
      Properties props = new Properties();
      props.setProperty("etendo.go.app.baseUrl", "http://localhost:3100");
      props.setProperty("context.url", "http://localhost:8080/etendo");
      props.setProperty("context.name", "etendo");
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());

      JSONObject result = withProperties(store, props,
          () -> McpImageTools.requestUpload(new JSONObject()));

      // The uploader talks to the app, not to Tomcat: context.url can be an internal address the
      // client cannot reach at all (proxy, tunnel, other host).
      assertEquals("http://localhost:3100" + McpImageTools.UPLOAD_PATH + result.getString("token"),
          result.getString("uploadUrl"));
    }

    @Test
    @DisplayName("without an app base it falls back to context.url WITH its context path")
    void fallbackKeepsTheContextPath() throws Exception {
      Properties props = new Properties();
      props.setProperty("context.url", "http://localhost:8080/etendo");
      props.setProperty("context.name", "etendo");
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());

      JSONObject result = withProperties(store, props,
          () -> McpImageTools.requestUpload(new JSONObject()));

      // Regression: this used to strip "/etendo", so the PUT reached Tomcat's default servlet and
      // came back 405 without ever entering NeoServlet. Asserting only that the URL *contains*
      // UPLOAD_PATH did not catch it — the stripped URL contained it too.
      assertEquals("http://localhost:8080/etendo" + McpImageTools.UPLOAD_PATH
          + result.getString("token"), result.getString("uploadUrl"));
    }

    @Test
    @DisplayName("with no base configured the relative fallback still carries the context path")
    void relativeFallbackCarriesTheContext() throws Exception {
      Properties props = new Properties();
      props.setProperty("context.name", "etendo");
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());

      JSONObject result = withProperties(store, props,
          () -> McpImageTools.requestUpload(new JSONObject()));

      assertEquals("/etendo" + McpImageTools.UPLOAD_PATH + result.getString("token"),
          result.getString("uploadUrl"));
    }

    @Test
    @DisplayName("a trailing slash on the base does not produce a double slash")
    void trailingSlashIsNormalised() throws Exception {
      Properties props = new Properties();
      props.setProperty("etendo.go.app.baseUrl", "http://localhost:3100/");
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());

      JSONObject result = withProperties(store, props,
          () -> McpImageTools.requestUpload(new JSONObject()));

      assertEquals("http://localhost:3100" + McpImageTools.UPLOAD_PATH + result.getString("token"),
          result.getString("uploadUrl"));
    }

    @Test
    @DisplayName("the advertised expiry is the ticket's own")
    void expiryComesFromTheTicket() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject result = withSession(store, () -> McpImageTools.requestUpload(new JSONObject()));
      assertEquals(store.peek(result.getString("token")).orElseThrow().getExpiresAt().toString(),
          result.getString("expiresAt"));
    }

    @Test
    @DisplayName("the ticket is bound to the calling session")
    void ticketIsBoundToTheSession() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject result = withSession(store, () -> McpImageTools.requestUpload(new JSONObject()));
      assertTrue(store.peek(result.getString("token")).orElseThrow()
          .belongsTo(CLIENT, ORG, USER));
    }

    @Test
    @DisplayName("an unsupported mime_type is refused up front")
    void unsupportedMimeTypeIsRefused() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject args = new JSONObject();
      args.put(McpImageTools.PARAM_MIME_TYPE, "image/gif");
      JSONObject result = withSession(store, () -> McpImageTools.requestUpload(args));
      assertEquals(McpConstants.ERROR_VALIDATION, result.getString(McpConstants.KEY_ERROR));
      assertEquals(0, store.size(), "a refused request must not consume a ticket");
    }

    @Test
    @DisplayName("the per-session cap is reported as a self-correctable error, not a crash")
    void perSessionCapIsReportedCleanly() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject result = withSession(store, () -> {
        for (int i = 0; i < NeoImageUploadTickets.MAX_PENDING_PER_SESSION; i++) {
          McpImageTools.requestUpload(new JSONObject());
        }
        return McpImageTools.requestUpload(new JSONObject());
      });
      assertEquals(McpConstants.ERROR_VALIDATION, result.getString(McpConstants.KEY_ERROR));
      assertNotNull(result.getString(McpConstants.KEY_HINT));
    }
  }

  // ── neo_upload_image ──────────────────────────────────────────────────

  @Nested
  @DisplayName("neo_upload_image")
  class UploadImage {

    private JSONObject upload(JSONObject args) throws Exception {
      try (MockedStatic<NeoImageHelper> helperMock = mockStatic(NeoImageHelper.class,
          org.mockito.Mockito.CALLS_REAL_METHODS)) {
        Image image = mock(Image.class);
        when(image.getId()).thenReturn(IMAGE_ID);
        when(image.getName()).thenReturn("image");
        when(image.getMimetype()).thenReturn(NeoImageHelper.MIME_PNG);
        helperMock.when(() -> NeoImageHelper.createImage(anyString(), anyString(), any()))
            .thenReturn(image);
        return McpImageTools.uploadImage(args);
      }
    }

    @Test
    @DisplayName("a small PNG round-trips to an imageId, with the shape every upload path returns")
    void smallPngReturnsAnImageId() throws Exception {
      JSONObject args = new JSONObject();
      args.put(McpImageTools.PARAM_DATA_BASE64,
          Base64.getEncoder().encodeToString(realPng()));
      JSONObject result = upload(args);

      assertEquals(IMAGE_ID, result.getString("imageId"));
      assertEquals(NeoImageHelper.MIME_PNG, result.getString("mimeType"));
      assertTrue(result.getInt("bytes") > 0);
      assertEquals(2, result.getInt("width"));
      assertEquals(3, result.getInt("height"));
      assertTrue(result.getString(McpConstants.KEY_HINT).contains("neo_update"),
          "the response must say the image is not attached to anything yet");
      assertFalse(result.has(McpConstants.KEY_ERROR));
    }

    @Test
    @DisplayName("a data: URI prefix is stripped and the stored bytes are still correct")
    void dataUriPrefixIsStripped() throws Exception {
      byte[] png = realPng();
      JSONObject args = new JSONObject();
      args.put(McpImageTools.PARAM_DATA_BASE64,
          "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
      JSONObject result = upload(args);
      assertEquals(png.length, result.getInt("bytes"));
    }

    @Test
    @DisplayName("over the 256 KB cap the error names neo_request_image_upload")
    void overCapNamesTheCheapPath() throws Exception {
      String oversized = "A".repeat(
          (McpConstants.IMAGE_BASE64_MAX_BYTES + 4096) / 3 * 4);
      JSONObject args = new JSONObject();
      args.put(McpImageTools.PARAM_DATA_BASE64, oversized);
      JSONObject result = upload(args);

      assertEquals(McpConstants.ERROR_VALIDATION, result.getString(McpConstants.KEY_ERROR));
      assertTrue(result.getString(McpConstants.KEY_DETAIL)
              .contains(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD),
          "assert the message, not just the status: " + result.getString(McpConstants.KEY_DETAIL));
      assertEquals(McpImageTools.OVER_CAP_ADVICE, result.getString(McpConstants.KEY_HINT));
    }

    @Test
    @DisplayName("a blank payload and a malformed one give distinct, actionable errors")
    void blankAndMalformedAreDistinct() throws Exception {
      JSONObject blank = upload(new JSONObject());
      JSONObject malformed = new JSONObject();
      malformed.put(McpImageTools.PARAM_DATA_BASE64, "!!! not base64 !!!");
      JSONObject malformedResult = upload(malformed);

      assertEquals(McpConstants.ERROR_VALIDATION, blank.getString(McpConstants.KEY_ERROR));
      assertEquals(McpConstants.ERROR_VALIDATION, malformedResult.getString(McpConstants.KEY_ERROR));
      assertFalse(blank.getString(McpConstants.KEY_DETAIL)
          .equals(malformedResult.getString(McpConstants.KEY_DETAIL)),
          "the two failures must not collapse into one message");
      assertTrue(malformedResult.getString(McpConstants.KEY_HINT).contains("base64"),
          malformedResult.getString(McpConstants.KEY_HINT));
    }

    @Test
    @DisplayName("an HTML payload labelled image/png is refused by the magic-byte sniff")
    void lyingMimeTypeCannotStoreArbitraryBytes() throws Exception {
      JSONObject args = new JSONObject();
      args.put(McpImageTools.PARAM_DATA_BASE64, Base64.getEncoder()
          .encodeToString("<html>not an image</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      args.put(McpImageTools.PARAM_MIME_TYPE, NeoImageHelper.MIME_PNG);
      JSONObject result = upload(args);

      assertEquals(McpConstants.ERROR_VALIDATION, result.getString(McpConstants.KEY_ERROR));
      assertTrue(result.getString(McpConstants.KEY_HINT).contains("PNG or JPEG"),
          result.getString(McpConstants.KEY_HINT));
    }

    @Test
    @DisplayName("a real PNG declared as image/jpeg is refused")
    void mimeMismatchIsRefused() throws Exception {
      JSONObject args = new JSONObject();
      args.put(McpImageTools.PARAM_DATA_BASE64, Base64.getEncoder().encodeToString(realPng()));
      args.put(McpImageTools.PARAM_MIME_TYPE, NeoImageHelper.MIME_JPEG);
      JSONObject result = upload(args);
      assertEquals(McpConstants.ERROR_VALIDATION, result.getString(McpConstants.KEY_ERROR));
      assertTrue(result.getString(McpConstants.KEY_HINT).contains(McpImageTools.PARAM_MIME_TYPE),
          result.getString(McpConstants.KEY_HINT));
    }
  }

  // ── neo_get_image_upload ──────────────────────────────────────────────

  @Nested
  @DisplayName("neo_get_image_upload")
  class GetUpload {

    @Test
    @DisplayName("a pending ticket reports pending and repeats the upload URL")
    void pendingTicketReportsPending() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject result = withSession(store, () -> {
        String token = McpImageTools.requestUpload(new JSONObject()).getString("token");
        JSONObject args = new JSONObject();
        args.put(McpImageTools.PARAM_TOKEN, token);
        return McpImageTools.getUpload(args);
      });
      assertEquals("pending", result.getString(McpConstants.KEY_STATUS));
      assertTrue(result.getString("uploadUrl").contains(McpImageTools.UPLOAD_PATH));
    }

    @Test
    @DisplayName("a completed ticket hands back the imageId the PUT produced")
    void completedTicketReturnsTheImageId() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject result = withSession(store, () -> {
        String token = McpImageTools.requestUpload(new JSONObject()).getString("token");
        store.claim(token);
        store.complete(token, IMAGE_ID);
        JSONObject args = new JSONObject();
        args.put(McpImageTools.PARAM_TOKEN, token);
        return McpImageTools.getUpload(args);
      });
      assertEquals("completed", result.getString(McpConstants.KEY_STATUS));
      assertEquals(IMAGE_ID, result.getString("imageId"));
      assertTrue(result.getString(McpConstants.KEY_HINT).contains("neo_update"));
    }

    @Test
    @DisplayName("an unknown or forged token is a 404 that says how to get a working one")
    void forgedTokenIsRefused() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject args = new JSONObject();
      args.put(McpImageTools.PARAM_TOKEN, "a-token-nobody-ever-issued");
      JSONObject result = withSession(store, () -> McpImageTools.getUpload(args));

      assertEquals(McpConstants.ERROR_NOT_FOUND, result.getString(McpConstants.KEY_ERROR));
      assertEquals(McpConstants.STATUS_NOT_FOUND, result.getInt(McpConstants.KEY_STATUS));
      assertTrue(result.getString(McpConstants.KEY_HINT)
          .contains(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD));
    }

    @Test
    @DisplayName("a ticket issued to another session is answered exactly like an unknown one")
    void crossSessionTokenIsRefused() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      // Issued to a different user, directly on the store.
      String foreignToken = store.issue(CLIENT, ORG, "SOMEONE-ELSE", "photo.png", null);
      JSONObject args = new JSONObject();
      args.put(McpImageTools.PARAM_TOKEN, foreignToken);
      JSONObject result = withSession(store, () -> McpImageTools.getUpload(args));

      JSONObject unknown = withSession(store, () -> {
        JSONObject other = new JSONObject();
        other.put(McpImageTools.PARAM_TOKEN, "a-token-nobody-ever-issued");
        return McpImageTools.getUpload(other);
      });
      assertEquals(unknown.getString(McpConstants.KEY_DETAIL),
          result.getString(McpConstants.KEY_DETAIL),
          "a cross-session token must not be distinguishable from an unknown one");
      assertFalse(result.has("imageId"));
    }

    @Test
    @DisplayName("a missing token argument is refused with a named argument")
    void missingTokenIsRefused() throws Exception {
      NeoImageUploadTickets store = new NeoImageUploadTickets(Clock.systemUTC());
      JSONObject result = withSession(store, () -> McpImageTools.getUpload(new JSONObject()));
      assertEquals(McpConstants.ERROR_VALIDATION, result.getString(McpConstants.KEY_ERROR));
      assertTrue(result.getString(McpConstants.KEY_DETAIL)
          .contains(McpImageTools.PARAM_TOKEN));
    }
  }

  @Nested
  @DisplayName("tool registration")
  class ToolRegistration {

    @Test
    @DisplayName("all three tools bypass spec resolution")
    void toolsAreSpecLess() {
      assertTrue(ToolRegistry.isCrudTool(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD));
      assertTrue(ToolRegistry.isCrudTool(McpConstants.TOOL_NEO_UPLOAD_IMAGE));
      assertTrue(ToolRegistry.isCrudTool(McpConstants.TOOL_NEO_GET_IMAGE_UPLOAD));
    }

    @Test
    @DisplayName("resolveSpecName yields no spec for them, so no window access is demanded")
    void noSpecIsDerivedFromTheToolName() {
      assertEquals(null, ToolRegistry.resolveSpecName(
          McpConstants.TOOL_NEO_UPLOAD_IMAGE, new JSONObject()));
      assertEquals(null, ToolRegistry.resolveSpecName(
          McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD, null));
    }
  }
}
