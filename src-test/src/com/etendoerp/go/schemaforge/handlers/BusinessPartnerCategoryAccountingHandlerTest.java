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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;

/**
 * Unit tests for {@link BusinessPartnerCategoryAccountingHandler}.
 *
 * <p>Covers all guard clauses in {@code handle()}, the injection path when
 * {@code accountingSchema} is absent or explicitly null, the no-op path when
 * the DB returns no accounting schemas, and the exception fallback.
 *
 * <p>100 % line and branch coverage of the handler class is the target.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessPartnerCategoryAccountingHandlerTest {

  private static final BusinessPartnerCategoryAccountingHandler HANDLER = new BusinessPartnerCategoryAccountingHandler();

  private static final String CLIENT_ID = "test-client-001";
  private static final String SCHEMA_ID  = "acct-schema-001";

  @Mock private OBContext obContext;
  @Mock private Client    client;
  @Mock private OBDal     obDal;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal>    obDalMock;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    obDalMock     = mockStatic(OBDal.class);

    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn(CLIENT_ID);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
    obDalMock.close();
  }

  // ── handle() guard clauses ─────────────────────────────────────────────────

  /**
   * Endpoints other than CRUD must be ignored — the handler is a POST/create guard only.
   */
  @Test
  @DisplayName("Non-CRUD endpoint returns null immediately")
  void testHandleReturnsNullForNonCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .httpMethod("GET")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * CRUD GET (and any non-POST method) must pass through without side effects.
   */
  @Test
  @DisplayName("CRUD GET returns null without touching the body")
  void testHandleReturnsNullForGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * A null request body must not cause a NullPointerException — returns null immediately.
   */
  @Test
  @DisplayName("CRUD POST with null body returns null safely")
  void testHandleReturnsNullForNullBody() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * When the body already contains a non-null {@code accountingSchema}, the handler
   * must skip injection and leave the value unchanged.
   */
  @Test
  @DisplayName("CRUD POST with accountingSchema already set skips injection")
  void testHandleSkipsInjectionWhenFieldAlreadyPresent() throws Exception {
    JSONObject body = new JSONObject().put("accountingSchema", "existing-schema-id");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();

    assertNull(HANDLER.handle(ctx));
    assertEquals("existing-schema-id", body.getString("accountingSchema"));
  }

  // ── handle() injection paths ──────────────────────────────────────────────

  /**
   * When {@code accountingSchema} is absent from the body the handler must look up
   * the default schema for the current client and inject its ID.
   */
  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("CRUD POST with missing field injects the default accounting schema ID")
  void testHandleInjectsSchemaWhenFieldAbsent() throws Exception {
    JSONObject body = new JSONObject(); // key absent
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();

    AcctSchema schema = mock(AcctSchema.class);
    doReturn(SCHEMA_ID).when(schema).getId();

    OBCriteria<AcctSchema> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchema.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(schema));

    assertNull(HANDLER.handle(ctx));
    assertEquals(SCHEMA_ID, body.getString("accountingSchema"));
  }

  /**
   * An explicit {@code null} value for {@code accountingSchema} (i.e. the key exists
   * but is {@link JSONObject#NULL}) is treated the same as absent — the handler injects
   * the default schema ID.
   */
  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("CRUD POST with accountingSchema=null injects the default accounting schema ID")
  void testHandleInjectsSchemaWhenFieldIsExplicitNull() throws Exception {
    JSONObject body = new JSONObject().put("accountingSchema", JSONObject.NULL);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();

    AcctSchema schema = mock(AcctSchema.class);
    doReturn(SCHEMA_ID).when(schema).getId();

    OBCriteria<AcctSchema> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchema.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(schema));

    assertNull(HANDLER.handle(ctx));
    assertEquals(SCHEMA_ID, body.getString("accountingSchema"));
  }

  /**
   * When the DB returns no active accounting schema for the current client, the handler
   * must leave the body unchanged rather than injecting {@code null}.
   */
  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("CRUD POST when no AcctSchema found in DB does not inject")
  void testHandleDoesNotInjectWhenNoSchemaFound() throws Exception {
    JSONObject body = new JSONObject(); // key absent
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();

    OBCriteria<AcctSchema> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchema.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    assertNull(HANDLER.handle(ctx));
    assertTrue(body.isNull("accountingSchema"));
  }

  /**
   * A DB exception during schema resolution must be caught and swallowed — the handler
   * must still return null so the default CRUD path can proceed (or fail cleanly on its own).
   */
  @Test
  @DisplayName("CRUD POST when DB throws returns null without propagating the exception")
  void testHandleReturnsNullOnDbException() {
    JSONObject body = new JSONObject(); // key absent, triggers resolveDefaultSchemaId()
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();

    when(obDal.createCriteria(AcctSchema.class))
        .thenThrow(new RuntimeException("DB connection lost"));

    assertNull(HANDLER.handle(ctx));
  }
}
