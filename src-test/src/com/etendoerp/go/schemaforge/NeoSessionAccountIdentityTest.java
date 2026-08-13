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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.schemaforge.data.Account;

class NeoSessionAccountIdentityTest {

  private static final String ACCOUNT_ID = "A1B2C3D4E5F6";
  private static final String ACCOUNT_EMAIL = "user@example.com";
  private static final String USERNAME = "user@example.com+acmeltd";

  private JSONObject sessionBodyFor(User user, Optional<Account> resolved) throws Exception {
    JSONObject body = new JSONObject();
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);

    try (var ctxMock = mockStatic(OBContext.class);
         var resolverMock = mockStatic(GoAccountResolver.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(context);
      resolverMock.when(() -> GoAccountResolver.findAccountByUsername(user == null
          ? null : user.getUsername())).thenReturn(resolved);
      NeoSessionService.putAccountIdentity(body);
    }
    return body;
  }

  private static User userNamed(String username) {
    User user = mock(User.class);
    when(user.getUsername()).thenReturn(username);
    return user;
  }

  private static Account account() {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn(ACCOUNT_ID);
    when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);
    return account;
  }

  @Test
  void addsBothFieldsWhenTheUserHasAPlatformAccount() throws Exception {
    JSONObject body = sessionBodyFor(userNamed(USERNAME), Optional.of(account()));

    assertEquals(ACCOUNT_ID, body.getString("accountId"));
    assertEquals(ACCOUNT_EMAIL, body.getString("accountEmail"));
  }

  /**
   * Absence must be omission, not null and not an empty string: a targeting rule cannot tell an
   * empty-string sentinel from a real value, so it would silently match the wrong users.
   */
  @Test
  void omitsBothFieldsWhenTheUserHasNoPlatformAccount() throws Exception {
    JSONObject body = sessionBodyFor(userNamed("warehouse-operator"), Optional.empty());

    assertFalse(body.has("accountId"));
    assertFalse(body.has("accountEmail"));
  }

  @Test
  void omitsBothFieldsWhenThereIsNoSessionUser() throws Exception {
    JSONObject body = sessionBodyFor(null, Optional.empty());

    assertFalse(body.has("accountId"));
    assertFalse(body.has("accountEmail"));
  }

  @Test
  void leavesExistingSessionFieldsUntouched() throws Exception {
    JSONObject body = new JSONObject();
    body.put("currencyCode", "EUR");
    // userNamed() and account() stub their own mocks, so they must be built BEFORE being handed to
    // another when(...) — nesting one stubbing inside another trips Mockito's unfinished-stubbing
    // detector.
    User user = userNamed(USERNAME);
    Account resolvedAccount = account();
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);

    try (var ctxMock = mockStatic(OBContext.class);
         var resolverMock = mockStatic(GoAccountResolver.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(context);
      resolverMock.when(() -> GoAccountResolver.findAccountByUsername(USERNAME))
          .thenReturn(Optional.of(resolvedAccount));
      NeoSessionService.putAccountIdentity(body);
    }

    assertEquals("EUR", body.getString("currencyCode"));
    assertEquals(3, body.length());
  }

  /**
   * The identity is an enrichment of the session payload. A failure resolving it must leave the
   * rest of the session intact rather than failing the endpoint.
   */
  @Test
  void aResolutionFailureLeavesTheSessionUsableWithoutTheFields() throws Exception {
    JSONObject body = new JSONObject();
    body.put("currencyCode", "EUR");
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenThrow(new IllegalStateException("no context"));

    try (var ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(context);
      assertDoesNotThrow(() -> NeoSessionService.putAccountIdentity(body));
    }

    assertFalse(body.has("accountId"));
    assertEquals("EUR", body.getString("currencyCode"));
  }

  /**
   * Naming guard. In the Mixpanel observability layer {@code account_id} already means the AD_Client
   * (tenant) id; emitting that name here would silently merge two different identities in analytics
   * and in flag-targeting rules.
   */
  @Test
  void neverEmitsTheTenantScopedAccountIdName() throws Exception {
    JSONObject body = sessionBodyFor(userNamed(USERNAME), Optional.of(account()));

    assertFalse(body.has("account_id"));
    assertFalse(body.has("account_email"));
    assertTrue(body.has("accountId"));
    assertTrue(body.has("accountEmail"));
  }
}
