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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoWriteChannel;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Specifies the contact-type KPI events (ETP-5791) through the pre-hook / post-hook pair the
 * contact customizations call: {@code backend_contact_created} and
 * {@code backend_contact_type_changed}, their properties, and that no record identifier is sent.
 *
 * <p>The stored contact row is answered by a mock statement; the event is captured on a mock
 * {@link NeoTelemetryService} (sending runs on a background thread, hence the timeouts).</p>
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ContactTypeTelemetry
 */
class ContactTypeTelemetryTest {

  private static final String CONTACT_STATE_SQL = "c_bp_group_id, created FROM c_bpartner";
  private static final String CLIENT_ID = "CLIENT-A";
  private static final String RECORD_ID = "BP-0001";
  private static final long WAIT_MS = 3000L;

  private static final Set<String> CREATED_KEYS = new HashSet<>(Arrays.asList(
      "account_id", "channel", "flag_enabled", "is_customer", "is_vendor", "types_source"));
  private static final Set<String> CHANGED_KEYS = new HashSet<>(Arrays.asList(
      "account_id", "channel", "flag_enabled", "previous_is_customer", "previous_is_vendor",
      "is_customer", "is_vendor", "days_since_created", "change_trigger", "change_origin"));

  private MockedStatic<OBDal> obDalStatic;
  private Connection conn;
  private PreparedStatement stateStatement;
  private NeoTelemetryService telemetry;
  private OBContext obContext;

  @BeforeEach
  void setUp() {
    conn = mock(Connection.class);
    OBDal dal = mock(OBDal.class);
    when(dal.getConnection()).thenReturn(conn);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    stateStatement = null;
    telemetry = mock(NeoTelemetryService.class);
    ContactTypeTelemetry.setTelemetryService(telemetry);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
  }

  @AfterEach
  void tearDown() {
    ContactTypeTelemetry.setTelemetryService(null);
    obDalStatic.close();
  }

  // ── backend_contact_created ──────────────────────────────────────────────────────────────

  /**
   * {@code types_source} collapses the per-flag source (sent → explicit, written by the rules
   * from the category → inherited, neither → default) and reports {@code mixed} when they differ.
   * {@code flag_enabled} carries the flag the pre-hook was told.
   *
   * <p>Columns: sent customer/vendor ({@code -} = not sent), fields the rules inherited
   * ({@code |}-separated, {@code -} = none), flag, expected source.</p>
   */
  @ParameterizedTest(name = "{4}")
  @CsvSource({
      "true, false, -,               false, explicit",
      "-,    -,     customer|vendor, true,  inherited",
      "-,    -,     -,               false, default",
      "true, -,     vendor,          true,  mixed",
  })
  void createdEventReportsWhereTheTypesCameFrom(String sentCustomer, String sentVendor,
      String inherited, boolean flagEnabled, String expectedSource) throws Exception {
    JSONObject body = new JSONObject().put("name", "Acme");
    if (!"-".equals(sentCustomer)) {
      body.put("customer", Boolean.parseBoolean(sentCustomer));
    }
    if (!"-".equals(sentVendor)) {
      body.put("vendor", Boolean.parseBoolean(sentVendor));
    }
    NeoContext ctx = context("POST", null, body, 201);

    ContactTypeTelemetry.beforeCreate(ctx, body, flagEnabled);
    if (!"-".equals(inherited)) {
      // What ContactTypeRules leaves behind when it writes a type from the category.
      ctx.setAttribute(ContactTypeRules.ATTR_INHERITED_TYPE_FIELDS,
          new HashSet<>(Arrays.asList(inherited.split("\\|"))));
    }
    givenStoredStates(state("Y", "Y", "CAT-1", 0));
    ContactTypeTelemetry.afterWrite(ctx, RECORD_ID);

    Map<String, Object> props = emitted(NeoTelemetryEvents.BACKEND_CONTACT_CREATED);
    assertEquals(expectedSource, props.get("types_source"));
    assertEquals(flagEnabled, props.get("flag_enabled"));
    assertEquals(true, props.get("is_customer"));
    assertEquals(true, props.get("is_vendor"));
    assertEquals(CLIENT_ID, props.get("account_id"));
    assertEquals(NeoWriteChannel.REST, props.get("channel"));
    assertEquals(CREATED_KEYS, props.keySet(), "no record identifier may be sent");
  }

  /** The channel is the one the write ran on: an MCP tool call is labelled {@code mcp}. */
  @Test
  void createdEventCarriesTheChannelOfTheWrite() throws Exception {
    JSONObject body = new JSONObject().put("customer", true);
    NeoContext ctx = context("POST", null, body, 201);
    String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
    try {
      ContactTypeTelemetry.beforeCreate(ctx, body, true);
    } finally {
      NeoWriteChannel.restore(previous);
    }
    givenStoredStates(state("Y", "N", "CAT-1", 0));
    ContactTypeTelemetry.afterWrite(ctx, RECORD_ID);

    assertEquals(NeoWriteChannel.MCP,
        emitted(NeoTelemetryEvents.BACKEND_CONTACT_CREATED).get("channel"));
  }

  // ── backend_contact_type_changed ─────────────────────────────────────────────────────────

  /**
   * A type switched on by the caller without moving the category: {@code checkbox} +
   * {@code user}, previous and new types, and the contact's age in whole days.
   */
  @Test
  void typeChangedByTheCallerIsACheckboxChangeOfUserOrigin() throws Exception {
    JSONObject body = new JSONObject().put("vendor", true);
    NeoContext ctx = context("PATCH", RECORD_ID, body, 200);
    givenStoredStates(state("Y", "N", "CAT-OLD", 10), state("Y", "Y", "CAT-OLD", 10));

    ContactTypeTelemetry.beforeUpdate(ctx, body, false, true);
    ContactTypeTelemetry.afterWrite(ctx, RECORD_ID);

    Map<String, Object> props = emitted(NeoTelemetryEvents.BACKEND_CONTACT_TYPE_CHANGED);
    assertEquals(true, props.get("previous_is_customer"));
    assertEquals(false, props.get("previous_is_vendor"));
    assertEquals(true, props.get("is_customer"));
    assertEquals(true, props.get("is_vendor"));
    assertEquals(10L, props.get("days_since_created"));
    assertEquals(ContactTypeTelemetry.TRIGGER_CHECKBOX, props.get("change_trigger"));
    assertEquals(ContactTypeTelemetry.ORIGIN_USER, props.get("change_origin"));
    assertEquals(false, props.get("flag_enabled"));
    assertEquals(CHANGED_KEYS, props.keySet(), "no record identifier may be sent");
  }

  /**
   * A header write that moves the contact to another category and whose types the rules
   * re-inherited: {@code category_change} + {@code system}. Neither the contact, the categories
   * nor anything but the tenant travels in the payload.
   */
  @Test
  void typeReinheritedOnACategoryChangeIsOfSystemOrigin() throws Exception {
    JSONObject body = new JSONObject().put("businessPartnerCategory", "CAT-NEW")
        .put("customer", false).put("vendor", true);
    NeoContext ctx = context("PATCH", RECORD_ID, body, 200);
    ctx.setAttribute(ContactTypeRules.ATTR_INHERITED_TYPE_FIELDS,
        new HashSet<>(Arrays.asList("customer", "vendor")));
    givenStoredStates(state("Y", "N", "CAT-OLD", 3), state("N", "Y", "CAT-NEW", 3));

    ContactTypeTelemetry.beforeUpdate(ctx, body, true, true);
    ContactTypeTelemetry.afterWrite(ctx, RECORD_ID);

    Map<String, Object> props = emitted(NeoTelemetryEvents.BACKEND_CONTACT_TYPE_CHANGED);
    assertEquals(ContactTypeTelemetry.TRIGGER_CATEGORY_CHANGE, props.get("change_trigger"));
    assertEquals(ContactTypeTelemetry.ORIGIN_SYSTEM, props.get("change_origin"));
    assertEquals(true, props.get("flag_enabled"));
    assertEquals(CHANGED_KEYS, props.keySet());
    for (Object value : props.values()) {
      assertFalse(Arrays.asList(RECORD_ID, "CAT-NEW", "CAT-OLD").contains(value),
          "a record identifier leaked: " + props);
    }
  }

  /** A write that sends a type equal to the stored one changed nothing: no event. */
  @Test
  void noEventWhenTheTypesDidNotChange() throws Exception {
    JSONObject body = new JSONObject().put("customer", true);
    NeoContext ctx = context("PATCH", RECORD_ID, body, 200);
    givenStoredStates(state("Y", "N", "CAT-1", 5), state("Y", "N", "CAT-1", 5));

    ContactTypeTelemetry.beforeUpdate(ctx, body, true, false);
    ContactTypeTelemetry.afterWrite(ctx, RECORD_ID);

    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  /** An update that sends neither type cannot change one: no lookup, no event. */
  @Test
  void updateWithoutTypeFlagsIsNotTracked() throws Exception {
    JSONObject body = new JSONObject().put("paymentTerms", "PT-1");
    NeoContext ctx = context("PATCH", RECORD_ID, body, 200);

    ContactTypeTelemetry.beforeUpdate(ctx, body, true, false);
    ContactTypeTelemetry.afterWrite(ctx, RECORD_ID);

    verify(conn, never()).prepareStatement(anyString());
    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  /** A write the CRUD answered with an error is not durable: no event. */
  @Test
  void noEventForAFailedWrite() throws Exception {
    JSONObject body = new JSONObject().put("customer", true);
    NeoContext ctx = context("POST", null, body, 400);
    givenStoredStates(state("Y", "N", "CAT-1", 0));

    ContactTypeTelemetry.beforeCreate(ctx, body, true);
    ContactTypeTelemetry.afterWrite(ctx, RECORD_ID);

    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  // ── fixtures ─────────────────────────────────────────────────────────────────────────────

  private NeoContext context(String method, String recordId, JSONObject body, int status) {
    return NeoContext.builder()
        .specName("contacts")
        .entityName("businessPartner")
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .requestBody(body)
        .obContext(obContext)
        .previousResult(new NeoResponse(status, new JSONObject()))
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> emitted(String event) {
    ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
    verify(telemetry, timeout(WAIT_MS)).emit(eq(event), props.capture(), eq(CLIENT_ID));
    return props.getValue();
  }

  /** One stored contact row: customer, vendor, category id, age in days. */
  private static ResultSet state(String customer, String vendor, String categoryId, int ageDays)
      throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.next()).thenReturn(true);
    when(row.getString(1)).thenReturn(customer);
    when(row.getString(2)).thenReturn(vendor);
    when(row.getString(3)).thenReturn(categoryId);
    when(row.getTimestamp(4)).thenReturn(
        Timestamp.from(Instant.now().minus(Duration.ofDays(ageDays))));
    return row;
  }

  /** Answers the contact-state lookup with {@code rows}, one per call (before, after). */
  private void givenStoredStates(ResultSet first, ResultSet... rest) throws SQLException {
    if (stateStatement == null) {
      stateStatement = mock(PreparedStatement.class);
      when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains(CONTACT_STATE_SQL))))
          .thenReturn(stateStatement);
    }
    when(stateStatement.executeQuery()).thenReturn(first, rest);
  }
}
