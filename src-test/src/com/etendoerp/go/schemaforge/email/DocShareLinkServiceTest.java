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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService.Claims;

/**
 * Unit tests for {@link DocShareLinkService} (ETP-5035): code uniqueness, expiry and revocation
 * filtering, open counting and the purge of expired rows.
 *
 * @covers com.etendoerp.go.schemaforge.email.DocShareLinkService
 */
public class DocShareLinkServiceTest {

  private static final String CODE = "aB3dE5gH7j";

  @Test
  public void generatedCodesAreUniqueUrlSafeAndFixedLength() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 20000; i++) {
      String code = DocShareLinkService.generateCode();
      assertEquals(DocShareLinkService.CODE_LENGTH, code.length());
      assertTrue(code, DocShareLinkService.isShareCode(code));
      assertTrue("collision on " + code, seen.add(code));
    }
  }

  @Test
  public void signedTokensAndMalformedValuesAreNotShareCodes() {
    assertFalse(DocShareLinkService.isShareCode(null));
    assertFalse(DocShareLinkService.isShareCode("short"));
    assertFalse(DocShareLinkService.isShareCode("aB3dE5gH7."));
    assertFalse(DocShareLinkService.isShareCode("aB3dE5gH7jk"));
    assertFalse(DocShareLinkService.isShareCode("aB3dE5g-7j"));
    assertFalse(DocShareLinkService.isShareCode("eyJ2IjoidjEifQ.c2ln"));
  }

  @Test
  public void createRetriesACodeThatAlreadyExistsAndStoresTheRow() {
    Session session = mock(Session.class);
    NativeQuery<?> taken = query(Collections.singletonList(1));
    NativeQuery<?> free = query(Collections.emptyList());
    NativeQuery<?> insert = query(Collections.emptyList());
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.startsWith("SELECT 1"))))
        .thenReturn((NativeQuery) taken, (NativeQuery) free);
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.startsWith("INSERT"))))
        .thenReturn((NativeQuery) insert);
    NativeQuery<?> emptyQuery = query(Collections.emptyList());
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.startsWith("DELETE"))))
        .thenReturn((NativeQuery) emptyQuery);

    String code = withSession(session, () -> DocShareLinkService.create("c", "s", "r", "t", "k",
        System.currentTimeMillis() + 1000L));

    assertTrue(DocShareLinkService.isShareCode(code));
    Mockito.verify(insert).setParameter("code", code);
    Mockito.verify(insert).setParameter("record", "r");
    Mockito.verify(insert).setParameter("client", "t");
    Mockito.verify(insert).executeUpdate();
  }

  @Test
  public void resolveOnlyReadsLiveRowsAndMapsThemToClaims() {
    Session session = mock(Session.class);
    Timestamp expires = new Timestamp(System.currentTimeMillis() + 60000L);
    NativeQuery<?> select = query(Collections.singletonList(
        new Object[] { "sales-order-send", "sales-order", "rec", "client", "key", expires }));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    when(session.createNativeQuery(sql.capture())).thenReturn((NativeQuery) select);

    Claims claims = withSession(session, () -> DocShareLinkService.resolve(CODE)
        .orElseThrow(() -> new AssertionError("live link resolves")));

    assertEquals("sales-order", claims.getSpecName());
    assertEquals("rec", claims.getRecordId());
    assertEquals("client", claims.getClientId());
    assertEquals(null, claims.getAttachmentId());
    assertEquals(expires.getTime() / 1000L, claims.getExpiresAtSeconds());
    // Expiry and revocation are filtered by the query itself, so neither can be served.
    assertTrue(sql.getValue().contains("revoked = 'N'"));
    assertTrue(sql.getValue().contains("expires_at >= :now"));
  }

  @Test
  public void resolveIsEmptyForUnknownExpiredOrRevokedCodesAndNeverQueriesBadCodes() {
    Session session = mock(Session.class);
    NativeQuery<?> emptyQuery = query(Collections.emptyList());
    when(session.createNativeQuery(Mockito.anyString()))
        .thenReturn((NativeQuery) emptyQuery);

    assertFalse(withSession(session, () -> DocShareLinkService.resolve(CODE)).isPresent());
    assertFalse(withSession(session, () -> DocShareLinkService.resolve("not.a.code")).isPresent());
    Mockito.verify(session, Mockito.times(1)).createNativeQuery(Mockito.anyString());
  }

  @Test
  public void revokeKillsALiveLinkAndRecordOpenCountsIt() {
    Session session = mock(Session.class);
    NativeQuery<?> update = query(Collections.emptyList());
    when(update.executeUpdate()).thenReturn(1, 0);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    when(session.createNativeQuery(sql.capture())).thenReturn((NativeQuery) update);

    assertTrue(withSession(session, () -> DocShareLinkService.revoke(CODE)));
    assertTrue(sql.getValue().contains("SET revoked = 'Y'"));
    assertFalse(withSession(session, () -> DocShareLinkService.revoke(CODE)));

    withSession(session, () -> {
      DocShareLinkService.recordOpen(CODE);
      return null;
    });
    assertTrue(sql.getValue().contains("open_count = open_count + 1"));
    assertTrue(sql.getValue().contains("COALESCE(first_opened_at"));
  }

  @Test
  public void failedInsertRollsBackToTheSavepointSoTheCallerTransactionSurvives() throws Exception {
    Session session = mock(Session.class);
    Connection connection = mock(Connection.class);
    Savepoint savepoint = mock(Savepoint.class);
    when(connection.setSavepoint()).thenReturn(savepoint);
    Mockito.doAnswer(invocation -> {
      invocation.<Work>getArgument(0).execute(connection);
      return null;
    }).when(session).doWork(Mockito.any(Work.class));
    NativeQuery<?> free = query(Collections.emptyList());
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.startsWith("SELECT 1"))))
        .thenReturn((NativeQuery) free);
    NativeQuery<?> insert = query(Collections.emptyList());
    when(insert.executeUpdate()).thenThrow(new IllegalStateException("unique violation"));
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.startsWith("INSERT"))))
        .thenReturn((NativeQuery) insert);

    try {
      withSession(session,
          () -> DocShareLinkService.create("c", "s", "r", "t", "k", System.currentTimeMillis()));
      fail("the insert failure must propagate");
    } catch (IllegalStateException expected) {
      assertEquals("unique violation", expected.getMessage());
    }

    Mockito.verify(connection).rollback(savepoint);
    Mockito.verify(connection, Mockito.never()).releaseSavepoint(Mockito.any());
  }

  @Test
  public void purgeDeletesRowsExpiredBeyondTheRetentionWindow() {
    Session session = mock(Session.class);
    NativeQuery<?> delete = query(Collections.emptyList());
    when(delete.executeUpdate()).thenReturn(3);
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.startsWith("DELETE"))))
        .thenReturn((NativeQuery) delete);
    long now = System.currentTimeMillis();

    assertEquals(3, DocShareLinkService.purgeExpired(session, now));

    ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
    Mockito.verify(delete).setParameter(Mockito.eq("cutoff"), cutoff.capture());
    long expected = now - DocShareLinkService.PURGE_RETENTION_DAYS * 24L * 3600L * 1000L;
    assertEquals(expected, cutoff.getValue().getTime());
  }

  private static NativeQuery<?> query(List<?> rows) {
    NativeQuery<?> query = mock(NativeQuery.class);
    when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn((NativeQuery) query);
    when(query.list()).thenReturn((List) rows);
    return query;
  }

  private static <T> T withSession(Session session, Supplier<T> action) {
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      context.when(OBContext::getOBContext).thenReturn(null);
      return action.get();
    }
  }
}
