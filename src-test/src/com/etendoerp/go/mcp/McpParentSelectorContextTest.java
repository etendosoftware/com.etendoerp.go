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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.base.structure.OrganizationEnabled;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * ETP-5535 — {@link McpParentSelectorContext#selectorArgs}: the parent record of a child create,
 * handed to FK-by-name resolution as the {@code parentContext} an agent would pass to
 * {@code neo_selectors}. Measured gap: a quotation line's tax rule reads the header's order date,
 * which the line body does not carry, so {@code tax: "Entregas IVA 21%"} resolved to nothing.
 *
 * <p>Every guard must answer {@code null} — the pre-ETP-5535 context — and the abstain cases
 * also assert the parent was never looked up, because the whole body sits inside a
 * {@code catch (Exception)} that would answer {@code null} too.</p>
 */
@DisplayName("McpParentSelectorContext (ETP-5535)")
class McpParentSelectorContextTest {

  private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";
  private static final String PARENT_FIELD = "salesOrder";
  private static final String PARENT_ENTITY = "Order";
  private static final String PARENT_ID = "ORDER-1";
  private static final String CURRENT_CLIENT = "CLIENT-1";
  private static final Predicate<String> SKIP_REFS = v -> v.startsWith("$ref:");

  /** What {@code OBDal#get} answers for the parent id in {@link #abstainCases}. */
  private enum ParentLookup {
    PRESENT, MISSING, THROWS
  }

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<McpParentScope> parentScopeMock;
  private MockedStatic<OBContext> obContextMock;
  private OBDal dal;
  private OBContext obContext;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    parentScopeMock = mockStatic(McpParentScope.class);
    obContext = mock(OBContext.class);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
    parentScopeMock.close();
    obDalMock.close();
  }

  // ── fixture ───────────────────────────────────────────────────────────

  /**
   * A parent scope naming {@code parentField} ({@code null} = not a child). Only
   * {@code getParentField} is read by the class under test.
   */
  private static McpParentScope.Scope scope(String parentField) {
    McpParentScope.Scope scope = mock(McpParentScope.Scope.class);
    when(scope.getParentField()).thenReturn(parentField);
    return scope;
  }

  private void stubScope(String parentField) {
    McpParentScope.Scope resolved = scope(parentField);
    parentScopeMock.when(() -> McpParentScope.forEntity(any())).thenReturn(resolved);
  }

  private static Property property(String name) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(name);
    return property;
  }

  private static Date date(int year, int month, int day) {
    Calendar calendar = Calendar.getInstance();
    calendar.clear();
    calendar.set(year, month - 1, day);
    return calendar.getTime();
  }

  /** A line entity whose {@code salesOrder} link targets {@code parentEntity}. */
  private static Entity lineEntity(Entity parentEntity) {
    Property link = property(PARENT_FIELD);
    when(link.getTargetEntity()).thenReturn(parentEntity);
    Entity line = mock(Entity.class);
    when(line.getProperty(PARENT_FIELD, false)).thenReturn(link);
    return line;
  }

  private static Entity parentEntity(List<Property> properties) {
    Entity parent = mock(Entity.class);
    when(parent.getName()).thenReturn(PARENT_ENTITY);
    when(parent.getProperties()).thenReturn(properties);
    return parent;
  }

  private static Client client(String id) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(id);
    return client;
  }

  /** A parent carrying a tenant, as every client/org-scoped entity does. */
  private static BaseOBObject tenantParent(String clientId, String orgId) {
    Client client = clientId != null ? client(clientId) : null;
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn(orgId);
    BaseOBObject parent = mock(BaseOBObject.class,
        withSettings().extraInterfaces(ClientEnabled.class, OrganizationEnabled.class));
    when(((ClientEnabled) parent).getClient()).thenReturn(client);
    when(((OrganizationEnabled) parent).getOrganization()).thenReturn(organization);
    return parent;
  }

  // ── abstains ──────────────────────────────────────────────────────────

  static Stream<Arguments> abstainCases() {
    return Stream.of(
        Arguments.of("no parent field (header entity)", null, "{\"parentId\":\"ORDER-1\"}",
            ParentLookup.PRESENT, false),
        Arguments.of("no parent id in the body", PARENT_FIELD, "{}", ParentLookup.PRESENT, false),
        Arguments.of("parent id is a batch $ref", PARENT_FIELD, "{\"parentId\":\"$ref:op-1\"}",
            ParentLookup.PRESENT, false),
        Arguments.of("parent record missing", PARENT_FIELD, "{\"parentId\":\"ORDER-1\"}",
            ParentLookup.MISSING, true),
        Arguments.of("parent lookup throws", PARENT_FIELD, "{\"parentId\":\"ORDER-1\"}",
            ParentLookup.THROWS, true));
  }

  /**
   * Rows: case, the scope's parent field, body, what the parent lookup answers, whether the guard
   * under test is the record lookup itself (so the lookup is expected to run). A lookup that throws
   * must degrade to the pre-ETP-5535 context, never fail the write.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("abstainCases")
  void answersNullWithoutAReadableParent(String scenario, String parentField, String body,
      ParentLookup lookup, boolean lookupExpected) throws Exception {
    stubScope(parentField);
    Entity line = lineEntity(parentEntity(List.of(property("documentNo"))));
    BaseOBObject parent = mock(BaseOBObject.class);
    if (lookup == ParentLookup.THROWS) {
      when(dal.get(PARENT_ENTITY, PARENT_ID)).thenThrow(new IllegalStateException("boom"));
    } else {
      when(dal.get(PARENT_ENTITY, PARENT_ID))
          .thenReturn(lookup == ParentLookup.PRESENT ? parent : null);
    }

    JSONObject args = McpParentSelectorContext.selectorArgs(mock(SFEntity.class), line,
        new JSONObject(body), null, SKIP_REFS);

    assertNull(args, scenario);
    if (lookupExpected) {
      verify(dal).get(PARENT_ENTITY, PARENT_ID);
    } else {
      verify(dal, never()).get(anyString(), any());
    }
  }

  // ── tenant boundary ───────────────────────────────────────────────────

  /**
   * Rows: case, whether the parent implements {@link ClientEnabled} + {@link OrganizationEnabled},
   * its client id, its organization id, the role's readable organizations, whether a context is
   * built. The current client is {@value #CURRENT_CLIENT}.
   */
  static Stream<Arguments> tenantCases() {
    String[] readable = { "0", "ORG-1" };
    return Stream.of(
        Arguments.of("same client, readable org", true, CURRENT_CLIENT, "ORG-1", readable, true),
        Arguments.of("another client", true, "CLIENT-2", "ORG-1", readable, false),
        Arguments.of("parent without a client", true, null, "ORG-1", readable, false),
        Arguments.of("org not readable", true, CURRENT_CLIENT, "ORG-9", readable, false),
        Arguments.of("no readable orgs", true, CURRENT_CLIENT, "ORG-1", null, false),
        Arguments.of("not tenant-scoped", false, null, null, null, true));
  }

  /**
   * The MCP router runs in admin mode, so {@code OBDal#get} does not apply the caller's access: a
   * foreign header id must not leak its values into the selectors. A refused parent is not read
   * at all.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("tenantCases")
  void usesTheParentOnlyInsideTheCallersTenant(String scenario, boolean tenantScoped,
      String parentClientId, String parentOrgId, String[] readableOrgs, boolean expectBuilt)
      throws Exception {
    stubScope(PARENT_FIELD);
    Entity line = lineEntity(parentEntity(List.of(property("documentNo"))));
    BaseOBObject parent = tenantScoped ? tenantParent(parentClientId, parentOrgId)
        : mock(BaseOBObject.class);
    when(parent.get("documentNo")).thenReturn("QU-1");
    when(dal.get(PARENT_ENTITY, PARENT_ID)).thenReturn(parent);
    Client current = client(CURRENT_CLIENT);
    when(obContext.getCurrentClient()).thenReturn(current);
    when(obContext.getReadableOrganizations()).thenReturn(readableOrgs);

    JSONObject args = McpParentSelectorContext.selectorArgs(mock(SFEntity.class), line,
        new JSONObject("{\"parentId\":\"ORDER-1\"}"), null, SKIP_REFS);

    if (expectBuilt) {
      assertNotNull(args, scenario);
      assertEquals("QU-1",
          args.getJSONObject(McpConstants.PARAM_PARENT_CONTEXT).getString("documentNo"));
    } else {
      assertNull(args, scenario);
      verify(parent, never()).get(anyString());
    }
  }

  // ── builds the parentContext ─────────────────────────────────────────

  /**
   * The parent's values keyed by DAL property: an FK as its id, a Date as {@code yyyy-MM-dd}, a
   * Boolean as {@code Y}/{@code N}, any other scalar as its string. One-to-many and computed
   * properties are skipped without being read, and a null value is left out. Run with the id in
   * {@code parentId} (where it beats a different link value: only {@code ORDER-1} resolves) and
   * with the id only in the link field.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "{\"parentId\":\"ORDER-1\",\"salesOrder\":\"OTHER-ORDER\"}",
      "{\"salesOrder\":\"ORDER-1\"}" })
  void describesTheParentAsParentContext(String body) throws Exception {
    stubScope(PARENT_FIELD);

    Property businessPartner = property("businessPartner");
    Property orderDate = property("orderDate");
    Property salesTransaction = property("salesTransaction");
    Property processed = property("processed");
    Property documentNo = property("documentNo");
    Property description = property("description");
    Property lines = property("orderLineList");
    when(lines.isOneToMany()).thenReturn(true);
    Property computed = property("deliveryStatus");
    when(computed.isComputedColumn()).thenReturn(true);
    Entity line = lineEntity(parentEntity(List.of(businessPartner, orderDate, salesTransaction,
        processed, documentNo, description, lines, computed)));

    BaseOBObject partner = mock(BaseOBObject.class);
    when(partner.getId()).thenReturn("BP-1");
    BaseOBObject parent = mock(BaseOBObject.class);
    when(parent.get("businessPartner")).thenReturn(partner);
    when(parent.get("orderDate")).thenReturn(date(2026, 8, 10));
    when(parent.get("salesTransaction")).thenReturn(Boolean.TRUE);
    when(parent.get("processed")).thenReturn(Boolean.FALSE);
    when(parent.get("documentNo")).thenReturn("QU-1");
    when(parent.get("description")).thenReturn(null);
    when(dal.get(PARENT_ENTITY, PARENT_ID)).thenReturn(parent);

    JSONObject args = McpParentSelectorContext.selectorArgs(mock(SFEntity.class), line,
        new JSONObject(body), null, SKIP_REFS);

    assertNotNull(args, "a readable parent must produce a context");
    JSONObject context = args.getJSONObject(McpConstants.PARAM_PARENT_CONTEXT);
    assertEquals("BP-1", context.getString("businessPartner"));
    assertEquals("2026-08-10", context.getString("orderDate"));
    assertEquals("Y", context.getString("salesTransaction"));
    assertEquals("N", context.getString("processed"));
    assertEquals("QU-1", context.getString("documentNo"));
    assertFalse(context.has("description"));
    assertFalse(context.has("orderLineList"));
    assertFalse(context.has("deliveryStatus"));
    verify(parent, never()).get("orderLineList");
    verify(parent, never()).get("deliveryStatus");
    verify(dal, never()).get(PARENT_ENTITY, "OTHER-ORDER");
  }

  /**
   * The child's own value wins, resolved or not: a line sending {@code businessPartner} must not
   * have the header's partner filled in under the same key, or a {@code partnerAddress} given by
   * name would resolve against the header's partner. Run with a pending name and with an id — the
   * rule does not depend on the value. The other header values still reach the context.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "Some Name", "BP-LINE" })
  void leavesOutTheKeysTheChildBodyCarries(String childPartner) throws Exception {
    stubScope(PARENT_FIELD);
    Entity line = lineEntity(parentEntity(List.of(property("businessPartner"),
        property("orderDate"), property("documentNo"))));
    BaseOBObject headerPartner = mock(BaseOBObject.class);
    when(headerPartner.getId()).thenReturn("BP-HEADER");
    BaseOBObject parent = mock(BaseOBObject.class);
    when(parent.get("businessPartner")).thenReturn(headerPartner);
    when(parent.get("orderDate")).thenReturn(date(2026, 8, 10));
    when(parent.get("documentNo")).thenReturn("QU-1");
    when(dal.get(PARENT_ENTITY, PARENT_ID)).thenReturn(parent);
    JSONObject body = new JSONObject();
    body.put(PARENT_FIELD, PARENT_ID);
    body.put("businessPartner", childPartner);

    JSONObject args = McpParentSelectorContext.selectorArgs(mock(SFEntity.class), line, body,
        null, SKIP_REFS);

    assertNotNull(args, "the other header values must still produce a context");
    JSONObject context = args.getJSONObject(McpConstants.PARAM_PARENT_CONTEXT);
    assertFalse(context.has("businessPartner"),
        "the header's partner must not stand in for the line's own");
    assertEquals("2026-08-10", context.getString("orderDate"));
    assertEquals("QU-1", context.getString("documentNo"));
    verify(parent, never()).get("businessPartner");
  }

  /**
   * A batch {@code parentRef} op carries its parent only as {@code OperationContext#parentId()}:
   * the body has neither {@code parentId} nor the link field yet. The explicit id must be used, and
   * it wins over a different link value in the body.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "{}", "{\"salesOrder\":\"OTHER-ORDER\"}" })
  void usesTheExplicitParentIdOverTheBody(String body) throws Exception {
    stubScope(PARENT_FIELD);
    Entity line = lineEntity(parentEntity(List.of(property("documentNo"))));
    BaseOBObject parent = mock(BaseOBObject.class);
    when(parent.get("documentNo")).thenReturn("QU-1");
    when(dal.get(PARENT_ENTITY, PARENT_ID)).thenReturn(parent);

    JSONObject args = McpParentSelectorContext.selectorArgs(mock(SFEntity.class), line,
        new JSONObject(body), PARENT_ID, SKIP_REFS);

    assertNotNull(args, "the explicit parent id must produce a context");
    assertEquals("QU-1",
        args.getJSONObject(McpConstants.PARAM_PARENT_CONTEXT).getString("documentNo"));
    verify(dal, never()).get(PARENT_ENTITY, "OTHER-ORDER");
  }

  // ── call sites ────────────────────────────────────────────────────────

  static Stream<Arguments> fkPrePassCallSites() {
    return Stream.of(
        Arguments.of("handleCreate", "null"),
        Arguments.of("preprocessBatchOperation", "op.parentId()"));
  }

  /**
   * Both FK-by-name pre-passes must hand the parent context to the resolver, describing the very
   * body being resolved (the child-wins rule reads it). The batch row is the regression guard for
   * the QA finding: reading the parent from the body alone gave a {@code parentRef} op no parent
   * context, because BatchService injects the link only when the record is created. Neither method
   * can be reached from a unit test (OBContext, live DAL, AD_Tab), so the call site is read from
   * source — see {@link McpSourceScanner}.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("fkPrePassCallSites")
  void theFkPrePassReceivesTheParentContext(String method, String expectedParentId) {
    String body = McpSourceScanner.methodBody(McpSourceScanner.read(ROUTER), method);

    List<String> resolve = McpSourceScanner.callArguments(body, "McpFkResolver.resolveFkNames",
        0);
    assertTrue(resolve.size() >= 4, method + " must still run the FK-by-name pre-pass");
    List<String> selector = McpSourceScanner.callArguments(resolve.get(3),
        "McpParentSelectorContext.selectorArgs", 0);
    assertEquals(5, selector.size(),
        method + " must build the resolver's selector context from McpParentSelectorContext");
    assertEquals(resolve.get(0), selector.get(2),
        "the parent context must describe the body being resolved");
    assertEquals(expectedParentId, selector.get(3), method + " passes the wrong parent id");
  }
}
