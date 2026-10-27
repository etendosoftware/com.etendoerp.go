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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Specifies the contact-type rules: a contact ({@code C_BPartner}) and a contact category
 * ({@code C_BP_Group}) must keep at least one of customer / vendor, and a new contact inherits each
 * type it was not sent explicitly from its category, and an update of the contact header that moves
 * it to a different category without sending either type re-inherits both from the new one.
 *
 * <p>The rules live in {@link ContactTypeRules}; the category and Customer/Vendor tab
 * customizations only delegate to it, so they are exercised through their {@code handle()} here
 * instead of re-testing the same rule in three classes. The contact header's wiring
 * ({@code BusinessPartnerHandler}) is pinned in {@code BusinessPartnerHandlerTest}.</p>
 *
 * <p>Every SQL lookup is answered by a mock statement routed on a distinctive fragment of its text;
 * a lookup nobody routed answers {@code null}, so an unexpected query fails loudly.</p>
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ContactTypeRules
 * @covers com.etendoerp.go.schemaforge.handlers.BusinessPartnerCategoryHandler
 * @covers com.etendoerp.go.schemaforge.handlers.ContactTypeFlagGuardHandler
 * @covers com.etendoerp.go.schemaforge.handlers.ContactCustomerTabHandler
 * @covers com.etendoerp.go.schemaforge.handlers.ContactVendorTabHandler
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTypeRulesTest {

  private static final String CATEGORY_TYPES_SQL = "em_etgo_isvendor FROM c_bp_group";
  private static final String CONTACT_TYPES_SQL = "iscustomer, isvendor FROM c_bpartner";
  private static final String DEFAULT_CATEGORY_SQL = "isdefault = 'Y'";
  private static final String CONTACT_CATEGORY_SQL = "c_bp_group_id FROM c_bpartner";

  /** Marks a value the request does not carry (or an attribute that is not set). */
  private static final String ABSENT = "-";

  private static final String CATEGORY_ID = "CAT-1";
  private static final String RECORD_ID = "REC-1";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBMessageUtils> messagesStatic;
  /** The {@code contact-category-type} flag: on by default here, the rules under test. */
  private MockedStatic<GoFeatureFlags> flagsStatic;
  private Connection conn;

  @BeforeEach
  void setUp() {
    conn = mock(Connection.class);
    OBDal dal = mock(OBDal.class);
    when(dal.getConnection()).thenReturn(conn);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    messagesStatic = mockStatic(OBMessageUtils.class);
    messagesStatic.when(() -> OBMessageUtils.messageBD(anyString()))
        .thenAnswer(inv -> inv.getArgument(0));
    flagsStatic = mockStatic(GoFeatureFlags.class);
    givenContactTypeFlag(true);
  }

  @AfterEach
  void tearDown() {
    flagsStatic.close();
    messagesStatic.close();
    obDalStatic.close();
  }

  // ── Contact create: inheritance from the category ──────────────────────────────────────────

  /**
   * The explicit/inherited matrix on create. "Explicit" is presence of the key in the body, unless
   * the channel hands over the keys the caller sent ({@code ATTR_CLIENT_PROVIDED_FIELDS}, MCP
   * {@code etendo_create}): then a key filled by the defaults pass is NOT explicit.
   *
   * <p>Columns: sent customer, sent vendor, client-provided keys ({@code -} attribute unset,
   * {@code {}} empty set, else {@code |}-separated), category customer/vendor flags, expected body
   * customer/vendor ({@code -} = key left out, so the AD default applies).</p>
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "nothing sent + both-type category,      -,     -,     -,        Y, Y, true,  true",
      "vendor category overrides AD default,    -,     -,     -,        N, Y, false, true",
      "explicit customer wins,                  true,  -,     -,        N, Y, true,  true",
      "explicit vendor wins,                    -,     false, -,        Y, Y, true,  false",
      "both explicit ignore the category,       false, true,  -,        Y, N, false, true",
      "neither-type category passes nothing,    -,     -,     -,        N, N, -,     -",
      "MCP defaults are not explicit,           true,  false, {},       N, Y, false, true",
      "MCP only the agent-sent key is explicit, true,  false, customer, N, Y, true,  true",
  })
  void createTakesEachTypeNotSentExplicitlyFromTheCategory(String scenario, String sentCustomer,
      String sentVendor, String clientProvided, String categoryCustomer, String categoryVendor,
      String expectedCustomer, String expectedVendor) throws Exception {
    JSONObject body = new JSONObject().put("businessPartnerCategory", CATEGORY_ID);
    put(body, "customer", sentCustomer);
    put(body, "vendor", sentVendor);
    NeoContext ctx = crudContext("POST", body, null);
    if (!ABSENT.equals(clientProvided)) {
      ctx.setAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS, keySet(clientProvided));
    }
    givenRow(CATEGORY_TYPES_SQL, categoryCustomer, categoryVendor);

    assertNull(ContactTypeRules.inheritAndValidateOnCreate(ctx, body));

    assertBodyValue(expectedCustomer, body, "customer");
    assertBodyValue(expectedVendor, body, "vendor");
  }

  /**
   * A create that names no category inherits from the one the create-defaults pass will assign:
   * the default category of the contact's organization and the session client.
   */
  @Test
  void createWithoutACategoryInheritsFromTheDefaultCategory() throws Exception {
    JSONObject body = new JSONObject().put("organization", "ORG-1");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLI-1");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
    NeoContext ctx = crudContext("POST", body, obContext);
    PreparedStatement defaultCategory = givenRow(DEFAULT_CATEGORY_SQL, "CAT-DEFAULT");
    PreparedStatement categoryTypes = givenRow(CATEGORY_TYPES_SQL, "N", "Y");

    assertNull(ContactTypeRules.inheritAndValidateOnCreate(ctx, body));

    verify(defaultCategory).setString(1, "ORG-1");
    verify(defaultCategory).setString(2, "CLI-1");
    verify(categoryTypes).setString(1, "CAT-DEFAULT");
    assertEquals(false, body.get("customer"));
    assertEquals(true, body.get("vendor"));
  }

  /**
   * A create that ends with neither type is refused — whether the caller chose it explicitly or
   * the category defines nothing and the AD defaults are not enough.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "explicit no-type beats a both-type category, false, false, Y, Y",
      "customer off + category defines nothing,     false, -,     N, N",
  })
  void createIsRejectedWhenTheContactEndsWithNoType(String scenario, String sentCustomer,
      String sentVendor, String categoryCustomer, String categoryVendor) throws Exception {
    JSONObject body = new JSONObject().put("businessPartnerCategory", CATEGORY_ID);
    put(body, "customer", sentCustomer);
    put(body, "vendor", sentVendor);
    givenRow(CATEGORY_TYPES_SQL, categoryCustomer, categoryVendor);

    NeoResponse response =
        ContactTypeRules.inheritAndValidateOnCreate(crudContext("POST", body, null), body);

    assertRejected(response, ContactTypeRules.MSG_CONTACT_TYPE_REQUIRED);
  }

  // ── Contact update: request flags merged with the stored ones ──────────────────────────────

  /**
   * An update is judged on the flags it leaves on the record: the request's own, else the stored
   * ones. {@code Y}/{@code N} strings are read like booleans.
   */
  @ParameterizedTest(name = "stored {0}/{1} + sent {2}/{3} -> rejected={4}")
  @CsvSource({
      "Y, N, false, -,    true",
      "N, Y, false, -,    false",
      "N, N, -,     -,    true",
      "N, N, -,     true, false",
      "Y, Y, N,     N,    true",
  })
  void updateMergesTheRequestFlagsWithTheStoredOnes(String storedCustomer, String storedVendor,
      String sentCustomer, String sentVendor, boolean rejected) throws Exception {
    JSONObject body = new JSONObject();
    put(body, "customer", sentCustomer);
    put(body, "vendor", sentVendor);
    givenRow(CONTACT_TYPES_SQL, storedCustomer, storedVendor);

    NeoResponse response =
        ContactTypeRules.validateContactUpdate(crudContext("PATCH", body, null), body, false);

    if (rejected) {
      assertRejected(response, ContactTypeRules.MSG_CONTACT_TYPE_REQUIRED);
    } else {
      assertNull(response);
    }
  }

  // ── Customer / Vendor tabs ─────────────────────────────────────────────────────────────────

  /**
   * The header update re-inherits BOTH types from the new category only when the request changes
   * the category and sends neither flag: either flag carries the caller's own decision, and a
   * category with neither flag defines nothing. The stored category is read only when the body
   * names one, and the category flags only once the category actually changed.
   *
   * <p>Columns: sent category, sent customer, sent vendor ({@code -} = key left out), stored
   * category, new category customer/vendor flags, expected body customer/vendor ({@code -} = key
   * still absent), whether the stored category is read, whether the category flags are read.
   * The stored contact is customer-only, so none of these rows is rejected.</p>
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "changed + no flag takes both from the category, CAT-NEW, -,     -,     CAT-OLD, N, Y, false, true,  true,  true",
      "changed + explicit customer inherits nothing,   CAT-NEW, true,  -,     CAT-OLD, N, Y, true,  -,     false, false",
      "changed + explicit vendor inherits nothing,     CAT-NEW, -,     false, CAT-OLD, Y, Y, -,     false, false, false",
      "changed + both flags explicit inherits nothing, CAT-NEW, false, true,  CAT-OLD, Y, N, false, true,  false, false",
      "changed to a category with neither flag,        CAT-NEW, -,     -,     CAT-OLD, N, N, -,     -,     true,  true",
      "same category as stored is not a change,        CAT-OLD, -,     -,     CAT-OLD, N, Y, -,     -,     true,  false",
      "no category in the body reads no category,      -,       -,     -,     CAT-OLD, N, Y, -,     -,     false, false",
  })
  void headerUpdateReinheritsBothTypesOnlyOnACategoryChangeWithoutFlags(String scenario,
      String sentCategory, String sentCustomer, String sentVendor, String storedCategory,
      String categoryCustomer, String categoryVendor, String expectedCustomer,
      String expectedVendor, boolean readsStoredCategory, boolean readsCategoryFlags)
      throws Exception {
    JSONObject body = new JSONObject().put("etgoFirstname", "Pedro");
    put(body, "businessPartnerCategory", sentCategory);
    put(body, "customer", sentCustomer);
    put(body, "vendor", sentVendor);
    givenRow(CONTACT_TYPES_SQL, "Y", "N");
    givenRow(CONTACT_CATEGORY_SQL, storedCategory);
    PreparedStatement categoryTypes = givenRow(CATEGORY_TYPES_SQL, categoryCustomer, categoryVendor);

    assertNull(ContactTypeRules.inheritOnCategoryChangeAndValidateUpdate(
        crudContext("PATCH", body, null), body));

    assertBodyValue(expectedCustomer, body, "customer");
    assertBodyValue(expectedVendor, body, "vendor");
    verify(conn, times(readsStoredCategory ? 1 : 0)).prepareStatement(
        argThat(sql -> sql != null && sql.contains(CONTACT_CATEGORY_SQL)));
    verify(conn, times(readsCategoryFlags ? 1 : 0)).prepareStatement(
        argThat(sql -> sql != null && sql.contains(CATEGORY_TYPES_SQL)));
    if (readsCategoryFlags) {
      verify(categoryTypes).setString(1, sentCategory);
    }
  }

  /**
   * Re-inheritance does not bypass the header rule: the write is still refused when the contact
   * ends with no type — a new category with neither flag cannot give one to a contact stored
   * without any, and an explicit flag that switches off the only type is kept, not overwritten by
   * the new category.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "neither-flag category cannot rescue a typeless contact, -,     N, N, N, N",
      "explicit customer off is not overwritten by a category, false, Y, N, Y, Y",
  })
  void headerUpdateChangingTheCategoryIsStillRejectedWhenNoTypeRemains(String scenario,
      String sentCustomer, String storedCustomer, String storedVendor, String categoryCustomer,
      String categoryVendor) throws Exception {
    JSONObject body = new JSONObject().put("businessPartnerCategory", "CAT-NEW");
    put(body, "customer", sentCustomer);
    givenRow(CONTACT_TYPES_SQL, storedCustomer, storedVendor);
    givenRow(CONTACT_CATEGORY_SQL, "CAT-OLD");
    givenRow(CATEGORY_TYPES_SQL, categoryCustomer, categoryVendor);

    NeoResponse response = ContactTypeRules.inheritOnCategoryChangeAndValidateUpdate(
        crudContext("PATCH", body, null), body);

    assertRejected(response, ContactTypeRules.MSG_CONTACT_TYPE_REQUIRED);
  }

  static Stream<Arguments> tabsClearingTheOnlyType() {
    return Stream.of(
        Arguments.of(new ContactCustomerTabHandler(), "customer", "Y", "N"),
        Arguments.of(new ContactVendorTabHandler(), "vendor", "N", "Y"));
  }

  /** A tab write that switches off the contact's only type is refused. */
  @ParameterizedTest(name = "{1} tab")
  @MethodSource("tabsClearingTheOnlyType")
  void tabWriteThatLeavesTheContactWithNoTypeIsRejected(NeoHandler tab, String flag,
      String storedCustomer, String storedVendor) throws Exception {
    JSONObject body = new JSONObject().put(flag, false);
    givenRow(CONTACT_TYPES_SQL, storedCustomer, storedVendor);

    assertRejected(tab.handle(crudContext("PATCH", body, null)),
        ContactTypeRules.MSG_CONTACT_TYPE_REQUIRED);
  }

  static Stream<NeoHandler> tabs() {
    return Stream.of(new ContactCustomerTabHandler(), new ContactVendorTabHandler());
  }

  /**
   * The tabs also save unrelated fields (payment terms, price list…): a write that does not send
   * either flag is not judged — not even read against the stored types, so a legacy contact with no
   * type stays editable there.
   */
  @ParameterizedTest
  @MethodSource("tabs")
  void tabWriteThatDoesNotTouchTheFlagsIsNotJudged(NeoHandler tab) throws Exception {
    JSONObject body = new JSONObject().put("paymentTerms", "PT-1");

    assertNull(tab.handle(crudContext("PATCH", body, null)));
    verify(conn, never()).prepareStatement(anyString());
  }

  // ── Category ───────────────────────────────────────────────────────────────────────────────

  /**
   * A category is refused when a create or an update leaves it with neither flag. Both columns
   * default to {@code N}, so a create must switch one on; an update keeps the stored flags it does
   * not send.
   */
  @ParameterizedTest(name = "{0} stored {1}/{2} + sent {3}/{4} -> rejected={5}")
  @CsvSource({
      "POST,  -, -, -,     -,     true",
      "POST,  -, -, false, false, true",
      "POST,  -, -, true,  -,     false",
      "PATCH, N, Y, -,     false, true",
      "PATCH, Y, Y, false, -,     false",
      "PATCH, N, Y, -,     -,     false",
  })
  void categoryWriteIsRejectedOnlyWhenItEndsWithNoType(String method, String storedCustomer,
      String storedVendor, String sentCustomer, String sentVendor, boolean rejected)
      throws Exception {
    JSONObject body = new JSONObject().put("name", "Clientes");
    put(body, "etgoIscustomer", sentCustomer);
    put(body, "etgoIsvendor", sentVendor);
    if (!ABSENT.equals(storedCustomer)) {
      givenRow(CATEGORY_TYPES_SQL, storedCustomer, storedVendor);
    }

    NeoResponse response =
        new BusinessPartnerCategoryHandler().handle(crudContext(method, body, null));

    if (rejected) {
      assertRejected(response, ContactTypeRules.MSG_CATEGORY_TYPE_REQUIRED);
    } else {
      assertNull(response);
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────────────────────

  // ── contact-category-type flag off: the customizations return to the default CRUD ─────────

  @Test
  void isEnabledReadsTheContactCategoryTypeFlag() {
    assertTrue(ContactTypeRules.isEnabled());
    givenContactTypeFlag(false);
    assertFalse(ContactTypeRules.isEnabled());
  }

  /** Flag off, a tab write that clears the only type is not judged (no stored-types lookup). */
  @ParameterizedTest(name = "{1} tab")
  @MethodSource("tabsClearingTheOnlyType")
  void tabWriteIsNotJudgedWithTheFlagOff(NeoHandler tab, String flag, String storedCustomer,
      String storedVendor) throws Exception {
    givenContactTypeFlag(false);
    JSONObject body = new JSONObject().put(flag, false);
    givenRow(CONTACT_TYPES_SQL, storedCustomer, storedVendor);

    assertNull(tab.handle(crudContext("PATCH", body, null)));
    verify(conn, never()).prepareStatement(
        argThat(sql -> sql != null && sql.contains(CONTACT_TYPES_SQL)));
  }

  /** Flag off, a category with neither type is saved by the default CRUD, create or update. */
  @ParameterizedTest
  @CsvSource({"POST", "PATCH"})
  void categoryWriteIsNotJudgedWithTheFlagOff(String method) throws Exception {
    givenContactTypeFlag(false);
    JSONObject body = new JSONObject().put("name", "Clientes")
        .put("etgoIscustomer", false).put("etgoIsvendor", false);

    assertNull(new BusinessPartnerCategoryHandler().handle(crudContext(method, body, null)));
    verify(conn, never()).prepareStatement(anyString());
  }

  private void givenContactTypeFlag(boolean enabled) {
    flagsStatic.when(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_CONTACT_CATEGORY_TYPE), any())).thenReturn(enabled);
  }

  private static NeoContext crudContext(String method, JSONObject body, OBContext obContext) {
    return NeoContext.builder()
        .specName("contacts")
        .entityName("businessPartner")
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .recordId("POST".equals(method) ? null : RECORD_ID)
        .requestBody(body)
        .obContext(obContext)
        .build();
  }

  /** {@code -} leaves the key out; {@code true}/{@code false} as JSON booleans; else a string. */
  private static void put(JSONObject body, String key, String value) throws Exception {
    if (ABSENT.equals(value)) {
      return;
    }
    if ("true".equals(value) || "false".equals(value)) {
      body.put(key, Boolean.parseBoolean(value));
    } else {
      body.put(key, value);
    }
  }

  private static Set<String> keySet(String encoded) {
    if ("{}".equals(encoded)) {
      return new HashSet<>();
    }
    return new HashSet<>(Arrays.asList(encoded.split("\\|")));
  }

  private static void assertBodyValue(String expected, JSONObject body, String key)
      throws Exception {
    if (ABSENT.equals(expected)) {
      assertFalse(body.has(key), key + " must be left to the AD default");
    } else {
      assertEquals(Boolean.parseBoolean(expected), body.get(key), key);
    }
  }

  private static void assertRejected(NeoResponse response, String messageKey) throws Exception {
    assertNotNull(response, "the write must be refused");
    assertEquals(400, response.getHttpStatus());
    assertEquals(messageKey,
        response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0));
  }

  /**
   * Answers the statement whose SQL contains {@code sqlFragment} with one row of {@code columns}.
   *
   * @return the statement, to verify what it was bound to
   */
  private PreparedStatement givenRow(String sqlFragment, String... columns) throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.next()).thenReturn(true);
    for (int i = 0; i < columns.length; i++) {
      when(row.getString(i + 1)).thenReturn(columns[i]);
    }
    PreparedStatement statement = mock(PreparedStatement.class);
    when(statement.executeQuery()).thenReturn(row);
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains(sqlFragment))))
        .thenReturn(statement);
    return statement;
  }
}
