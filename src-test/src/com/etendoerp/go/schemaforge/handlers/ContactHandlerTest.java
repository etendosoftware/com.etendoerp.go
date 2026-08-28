/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;

/**
 * Unit tests for {@link ContactHandler} (ETP-4156).
 *
 * <p>These tests pin the behaviour migrated out of the app-shell's generic
 * {@code useEntity} hook (`applyContactsRequiredFields` / `applyContactNameDefaults`),
 * which hardcoded the entity names {@code contact} / {@code adUser} / {@code user}
 * in a metadata-driven runtime.
 *
 * <p>Tests that reach the database use {@code mockStatic(OBDal.class)} with a mock JDBC
 * {@link Connection}, mirroring {@code BusinessPartnerHandlerTest}, so no live Etendo
 * environment is required.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactHandlerTest {

  private ContactHandler handler;
  private NeoContext ctx;

  @BeforeEach
  void setUp() {
    handler = new ContactHandler();
    ctx = mock(NeoContext.class);
  }

  /**
   * Runs {@code handler.handle(ctx)} with {@code OBDal} stubbed to return a JDBC
   * connection whose single query resolves to the given persisted AD_User row.
   *
   * @param persistedName the value {@code ad_user.name} holds for the record
   */
  private void handleWithPersistedName(String persistedName) throws Exception {
    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);

    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString(1)).thenReturn(persistedName);
    when(rsMock.getString(2)).thenReturn("PersistedFirst");
    when(rsMock.getString(3)).thenReturn("PersistedLast");
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      handler.handle(ctx);
    }
  }

  // ── method guards ───────────────────────────────────────────────────────────

  /**
   * Non-write methods must return {@code null} without touching the request body.
   */
  @Test
  void testHandleGetMethodReturnsNull() {
    JSONObject body = new JSONObject();
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRequestBody()).thenReturn(body);

    assertNull(handler.handle(ctx));
    assertFalse(body.has("name"));
  }

  /**
   * A missing request body must not blow up.
   */
  @Test
  void testHandleNullBodyReturnsNull() {
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  /**
   * The handler is a pre-hook only: it always falls through to the default CRUD path.
   */
  @Test
  void testHandleAlwaysFallsThroughToDefaultCrud() {
    JSONObject body = new JSONObject();
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    assertNull(handler.handle(ctx));
  }

  /**
   * With neither name part in the body there is nothing to derive.
   */
  @Test
  void testHandlePostWithoutNamePartsLeavesBodyUntouched() {
    JSONObject body = new JSONObject();
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertFalse(body.has("name"));
    assertFalse(body.has("username"));
  }

  // ── handle() — POST: name derivation ────────────────────────────────────────

  /**
   * {@code AD_User.Name} is mandatory but not editable in the contacts window, so it
   * must be derived from the two visible parts.
   */
  @Test
  void testHandlePostDerivesNameFromFirstAndLastName() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");
    body.put("lastName", "Roe");

    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("Jane Roe", body.getString("name"));
  }

  /**
   * A single part is enough — no dangling separator.
   */
  @Test
  void testHandlePostDerivesNameFromFirstNameOnly() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");

    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("Jane", body.getString("name"));
  }

  /**
   * An explicit name always wins over the derived one.
   */
  @Test
  void testHandlePostDoesNotOverrideExistingName() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");
    body.put("lastName", "Roe");
    body.put("name", "Explicit Name");

    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("Explicit Name", body.getString("name"));
  }

  /**
   * Regression: {@code AD_User.Name} is {@code NVARCHAR(60)}. A first+last name longer
   * than that must be truncated here rather than failing on save.
   */
  @Test
  void testHandlePostTruncatesDerivedNameToColumnLength() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "A".repeat(40));
    body.put("lastName", "B".repeat(40));

    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals(60, body.getString("name").length());
    assertEquals(("A".repeat(40) + " " + "B".repeat(40)).substring(0, 60), body.getString("name"));
  }

  // ── handle() — POST: username parity with Classic ───────────────────────────

  /**
   * Contacts created through Classic leave {@code AD_User.Username} null. NEO must preserve
   * that behaviour instead of deriving a username from the contact name.
   */
  @Test
  void testHandlePostLeavesUsernameNullWhenNotProvided() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");
    body.put("lastName", "Roe");

    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertFalse(body.has("username"));
  }

  /**
   * An explicit username (the {@code user} window exposes it as editable) is respected.
   */
  @Test
  void testHandlePostDoesNotOverrideExistingUsername() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");
    body.put("lastName", "Roe");
    body.put("username", "jroe");

    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("jroe", body.getString("username"));
  }

  /** An explicit name does not cause a username to be invented. */
  @Test
  void testHandlePostDoesNotInventUsernameFromExplicitName() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Explicit Name");

    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertFalse(body.has("username"));
  }

  /** The contact handler protects username from the Classic name callout cascade. */
  @Test
  void testProtectsUsernameFromCreateCalloutCascade() {
    assertTrue(handler.protectedCreateCalloutFields(ctx).contains("username"));
  }

  // ── handle() — PATCH / PUT ──────────────────────────────────────────────────

  /**
   * On update, the name is rebuilt only when the persisted one is blank, merging the
   * body values with the persisted parts for whichever half the body omits.
   */
  @Test
  void testHandlePatchDerivesNameWhenPersistedNameIsBlank() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");

    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn("USER_001");

    handleWithPersistedName("");

    assertEquals("Jane PersistedLast", body.getString("name"));
  }

  /**
   * A persisted name is never overwritten by an update.
   */
  @Test
  void testHandlePatchSkipsNameDerivationWhenPersistedNameSet() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");

    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn("USER_001");

    handleWithPersistedName("Already Named");

    assertFalse(body.has("name"));
  }

  /**
   * Without a record id there is nothing to compare against.
   */
  @Test
  void testHandlePatchSkipsNameDerivationWhenRecordIdBlank() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");

    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn("");

    handler.handle(ctx);

    assertFalse(body.has("name"));
  }

  /**
   * Behaviour change vs. the removed app-shell hook (ETP-4156): renaming a contact must
   * NOT rewrite its username. {@code AD_User.Username} is unique and is the login
   * identifier; the old front-end code silently reassigned it on every rename.
   */
  @Test
  void testHandlePatchNeverTouchesUsername() throws Exception {
    JSONObject body = new JSONObject();
    body.put("firstName", "Jane");

    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn("USER_001");

    handleWithPersistedName("");

    assertEquals("Jane PersistedLast", body.getString("name"));
    assertFalse(body.has("username"));
  }
}
