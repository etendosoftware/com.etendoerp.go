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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.oauth2.OAuth2Servlet.PublicApiKeyContext;
import com.etendoerp.go.schemaforge.data.com.etendoerp.go.schemaforge.data.OAuth2Client;
import com.etendoerp.go.schemaforge.data.com.etendoerp.go.schemaforge.data.OAuth2Token;

/**
 * ETP-5781 — real-database specs for the token side of {@link PublicApiKeyStore}.
 *
 * <p>Needs a database on purpose: the defect is a DAL refusal that only a real flush produces.
 * {@code OAuth2Servlet} inserts a key's tokens by SQL with {@code AD_CLIENT_ID = '0'} and the
 * tenant organization, and {@code ETGO_OAUTH2_TOKEN} is a System-level table, so
 * {@code SecurityChecker} refuses to flush any DAL change to such a row (even in admin mode:
 * "accesslevel check must also be done for administrators"). Rotate and revoke-tokens updated the
 * tokens through OBDal and answered 500 for every key that had ever issued a token; delete removed
 * them through OBDal and would have failed the same way.
 *
 * <p>The fixtures reproduce the production shape exactly: the key is created through the store
 * (client and organization {@code 0}), the tokens are inserted with the same columns as
 * {@code OAuth2Servlet.SQL_INSERT_TOKEN} and a real tenant organization. Everything runs in the
 * test's transaction and {@link #rollBack()} discards it: {@code OBBaseTest} would otherwise commit
 * a successful test.
 *
 * @covers com.etendoerp.go.oauth2.PublicApiKeyStore
 */
public class PublicApiKeyStoreIntegrationTest extends OBBaseTest {

  private static final String OWNER_USER_ID = "100";
  private static final String SYSTEM_ROLE_ID = "0";
  private static final String INSERT_TOKEN =
      "INSERT INTO etgo_oauth2_token "
      + "(etgo_oauth2_token_id, ad_client_id, ad_org_id, isactive, "
      + "created, createdby, updated, updatedby, "
      + "etgo_oauth2_client_id, access_token_hash, refresh_token_hash, scopes, expires_at, "
      + "validity_seconds, is_revoked) "
      + "VALUES (get_uuid(), '0', ?, 'Y', now(), ?, now(), ?, ?, ?, ?, 'etendo:read', "
      + "now() + interval '1 hour', 3600, 'N')";

  private final PublicApiKeyStore store = new PublicApiKeyStore();
  private String tenantOrgId;
  private String keyId;

  @Before
  public void createUsedKey() throws Exception {
    setSystemAdministratorContext();
    String tenantClientId;
    try (PreparedStatement ps = connection().prepareStatement(
        "SELECT ad_org_id, ad_client_id FROM ad_org WHERE ad_org_id <> '0' AND isactive = 'Y' "
            + "ORDER BY ad_org_id LIMIT 1");
        ResultSet rs = ps.executeQuery()) {
      assertTrue("the test database needs at least one tenant organization", rs.next());
      tenantOrgId = rs.getString(1);
      tenantClientId = rs.getString(2);
    }
    PublicApiKeyContext context =
        new PublicApiKeyContext(OWNER_USER_ID, SYSTEM_ROLE_ID, tenantClientId, tenantOrgId);
    String marker = UUID.randomUUID().toString().replace("-", "");
    OAuth2Client key = store.create(context, "etp5781-it-" + marker,
        "etgo-it-" + marker.substring(0, 16), OAuth2Utils.hashSecret("secret"),
        "neo:public-api-key etendo:read");
    keyId = key.getId();
    insertToken("access-a-" + marker, "refresh-a-" + marker);
    insertToken("access-b-" + marker, "refresh-b-" + marker);
  }

  @After
  public void rollBack() {
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void revokeTokensOfAUsedKeyRevokesEveryValidToken() throws Exception {
    int revoked = store.revokeTokens(keyId, OWNER_USER_ID);

    assertEquals(2, revoked);
    assertEquals(0, countTokens("is_revoked = 'N'"));
    assertEquals(2, countTokens("is_revoked = 'Y' AND updatedby = '" + OWNER_USER_ID + "'"));
  }

  @Test
  public void revokeTokensTwiceRevokesNothingTheSecondTime() throws Exception {
    store.revokeTokens(keyId, OWNER_USER_ID);

    assertEquals(0, store.revokeTokens(keyId, OWNER_USER_ID));
  }

  @Test
  public void deleteOfAUsedKeyRemovesTheKeyAndItsTokens() throws Exception {
    OAuth2Client key = OBDal.getInstance().get(OAuth2Client.class, keyId);
    assertNotNull(key);

    store.delete(key);

    assertEquals(0, countTokens("1 = 1"));
    OBDal.getInstance().getSession().clear();
    assertNull(OBDal.getInstance().get(OAuth2Client.class, keyId));
  }

  /**
   * Pins the root cause, so the SQL path is not "simplified" back to DAL: changing one of these
   * tokens through OBDal is refused. If this ever starts passing, the DAL accepts such rows and
   * the SQL in {@link PublicApiKeyStore} is no longer required.
   */
  @Test
  public void dalRefusesToChangeATokenThatCarriesTheTenantOrganization() {
    OBQuery<OAuth2Token> query = OBDal.getInstance().createQuery(OAuth2Token.class,
        "as token where token.oAuth2Client.id = :keyId");
    query.setNamedParameter("keyId", keyId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    OAuth2Token token = query.list().get(0);
    assertEquals(tenantOrgId, token.getOrganization().getId());

    try {
      token.setRevoked(true);
      OBDal.getInstance().save(token);
      OBDal.getInstance().flush();
      fail("the DAL accepted a change to a System-level row with a tenant organization");
    } catch (RuntimeException expected) {
      String messages = rootMessage(expected);
      assertTrue(messages, messages.contains("may only have instances with organization"));
    }
  }

  private void insertToken(String accessHash, String refreshHash) throws Exception {
    try (PreparedStatement ps = connection().prepareStatement(INSERT_TOKEN)) {
      ps.setString(1, tenantOrgId);
      ps.setString(2, OWNER_USER_ID);
      ps.setString(3, OWNER_USER_ID);
      ps.setString(4, keyId);
      ps.setString(5, accessHash);
      ps.setString(6, refreshHash);
      ps.executeUpdate();
    }
  }

  private int countTokens(String condition) throws Exception {
    try (PreparedStatement ps = connection().prepareStatement(
        "SELECT count(*) FROM etgo_oauth2_token WHERE etgo_oauth2_client_id = ? AND " + condition)) {
      ps.setString(1, keyId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static Connection connection() {
    return OBDal.getInstance().getConnection();
  }

  private static String rootMessage(Throwable error) {
    Throwable cause = error;
    StringBuilder messages = new StringBuilder();
    while (cause != null) {
      messages.append(cause.getMessage()).append(" | ");
      cause = cause.getCause() == cause ? null : cause.getCause();
    }
    return messages.toString();
  }
}
