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

package com.etendoerp.go.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.com.etendoerp.go.schemaforge.data.OAuth2Client;

/**
 * ETP-5781 — token revocation and deletion of a self-service API key must not go through the DAL.
 *
 * <p>Tokens are inserted by SQL with the tenant organization on the System-level
 * {@code ETGO_OAUTH2_TOKEN}, and the DAL refuses to flush any change to such a row ({@code Entity
 * etgo_oauth2_token may only have instances with organization *}), so rotate and revoke-tokens
 * answered 500 for every key that had issued a token. These cases pin that both paths are SQL on the
 * DAL connection and never touch a token entity. The database itself is exercised by
 * {@code PublicApiKeyStoreIntegrationTest}.
 *
 * @covers com.etendoerp.go.oauth2.PublicApiKeyStore
 */
class PublicApiKeyStoreTest {

  private static final String KEY_ID = "key-1";
  private static final String USER_ID = "user-1";

  private final PublicApiKeyStore store = new PublicApiKeyStore();
  private OBDal dal;
  private Connection connection;
  private PreparedStatement statement;
  private MockedStatic<OBDal> dalStatic;

  @BeforeEach
  void setUp() throws Exception {
    dal = mock(OBDal.class);
    connection = mock(Connection.class);
    statement = mock(PreparedStatement.class);
    when(dal.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(any())).thenReturn(statement);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
  }

  @Test
  void revokeTokensUpdatesOnlyTheKeysStillValidTokensBySql() throws Exception {
    when(statement.executeUpdate()).thenReturn(3);

    int revoked = store.revokeTokens(KEY_ID, USER_ID);

    assertEquals(3, revoked, "the affected-row count is the number of tokens revoked");
    verify(connection).prepareStatement(PublicApiKeyStore.SQL_REVOKE_ACTIVE_TOKENS);
    verify(statement).setString(1, USER_ID);
    verify(statement).setString(2, KEY_ID);
    verify(dal, never()).save(any());
    verify(dal, never()).flush();
  }

  @Test
  void revokeTokensOfAKeyWithoutValidTokensRevokesNone() throws Exception {
    when(statement.executeUpdate()).thenReturn(0);

    assertEquals(0, store.revokeTokens(KEY_ID, USER_ID));
  }

  @Test
  void revokeTokensSqlOnlyTouchesTokensThatAreNotRevokedYet() {
    String sql = PublicApiKeyStore.SQL_REVOKE_ACTIVE_TOKENS.toLowerCase();

    assertTrue(sql.startsWith("update etgo_oauth2_token set is_revoked = 'y'"));
    assertTrue(sql.contains("and is_revoked = 'n'"),
        "an already revoked token must not be counted again");
  }

  @Test
  void deleteTokensSqlOnlyTouchesTheTokensOfTheDeletedKey() {
    String sql = PublicApiKeyStore.SQL_DELETE_TOKENS.toLowerCase();

    assertTrue(sql.startsWith("delete from etgo_oauth2_token"));
    assertTrue(sql.endsWith("where etgo_oauth2_client_id = ?"),
        "an unfiltered delete would remove every key's tokens");
  }

  @Test
  void deleteRemovesTheTokensBySqlBeforeTheKey() throws Exception {
    OAuth2Client key = mock(OAuth2Client.class);
    when(key.getId()).thenReturn(KEY_ID);

    store.delete(key);

    InOrder order = inOrder(connection, statement, dal);
    order.verify(connection).prepareStatement(PublicApiKeyStore.SQL_DELETE_TOKENS);
    order.verify(statement).setString(1, KEY_ID);
    order.verify(statement).executeUpdate();
    order.verify(dal).remove(key);
    order.verify(dal).flush();
  }
}
