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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.criterion.SimpleExpression;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReturnShipmentUtils#resolvePriceFromSourceInvoiceLine} and
 * {@link ReturnShipmentUtils#findReturnDocTypeForOrg} (ETP-4737).
 *
 * <p>Covers:
 * <ul>
 *   <li>null shipmentLineId → returns fallback without touching OBDal.</li>
 *   <li>Session returns a row with price &gt; 0 → returns that price.</li>
 *   <li>Session returns a row with null price → returns fallback.</li>
 *   <li>Session returns an empty list → returns fallback (logs warn).</li>
 *   <li>Session.createQuery throws → returns fallback (logs warn, no rethrow).</li>
 *   <li>{@code findReturnDocTypeForOrg} — requireRectificative gating on column presence, org
 *       match, org-'0' fallback, and first-candidate fallback.</li>
 * </ul>
 */
public class ReturnShipmentUtilsTest {

  private static final BigDecimal FALLBACK = new BigDecimal("9.99");

  // ── Case 1: null shipmentLineId → fallback, OBDal never called ──────────────

  @Test
  public void resolvePriceFromSourceInvoiceLine_nullId_returnsFallbackWithoutCallingOBDal() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          null, "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
      // OBDal.getInstance() must not be touched when shipmentLineId is null
      verify(dal, never()).getSession();
    }
  }

  // ── Case 2: session returns a row with price > 0 → returns that price ───────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithPositivePrice_returnsThatPrice()
      throws Exception {
    BigDecimal expectedPrice = new BigDecimal("42.50");

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(expectedPrice));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-001", "unitPrice", FALLBACK);

      assertEquals(expectedPrice, result);
    }
  }

  // ── Case 3: session returns a row with null price → fallback ────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithNullPrice_returnsFallback()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      // The list has one element but it is null
      when(query.list()).thenReturn(Collections.singletonList(null));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-002", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Case 4: session returns empty list → fallback (log warn) ────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_emptyList_returnsFallback() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.<BigDecimal>emptyList());

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-003", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Case 5: session throws → fallback (log warn, no rethrow) ────────────────

  @Test
  public void resolvePriceFromSourceInvoiceLine_sessionThrows_returnsFallbackWithoutRethrow() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(BigDecimal.class)))
          .thenThrow(new RuntimeException("HibernateException: session closed"));

      // Must not throw — exception is swallowed and fallback is returned
      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-004", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Edge: row with price = 0 → treated as "no price" → fallback ─────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithZeroPrice_returnsFallback()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(BigDecimal.ZERO));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-005", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── findReturnDocTypeForOrg (ETP-4737) ───────────────────────────────────────

  private static OBCriteria<DocumentType> mockCriteria(OBDal dal, List<DocumentType> result) {
    @SuppressWarnings("unchecked")
    OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(result);
    return criteria;
  }

  private static DocumentType docTypeWithOrg(String orgId) {
    DocumentType dt = mock(DocumentType.class);
    Organization org = mock(Organization.class);
    when(dt.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn(orgId);
    return dt;
  }

  /**
   * {@code requireRectificative=false} must never add the
   * {@code EM_Etsg_Isrectificative} restriction, regardless of column presence.
   */
  @Test
  public void findReturnDocTypeForOrg_requireRectificativeFalse_doesNotAddRectificativeRestriction() {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBCriteria<DocumentType> criteria = mockCriteria(dal, Collections.emptyList());

      ReturnShipmentUtils.findReturnDocTypeForOrg("org-1", "APC", false, false, false);

      verify(criteria, never()).add(argThat(ReturnShipmentUtilsTest::isRectificativeRestriction));
    } finally {
      RectificativeSupport.setColumnPresentForTests(null);
    }
  }

  /**
   * {@code requireRectificative=true} + column present adds the restriction with value TRUE —
   * this is how the new unified "Factura Rectificativa" doc type is distinguished from a legacy
   * doc type sharing the same {@code docCategory}.
   */
  @Test
  public void findReturnDocTypeForOrg_requireRectificativeTrueColumnPresent_addsRestriction() {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBCriteria<DocumentType> criteria = mockCriteria(dal, Collections.emptyList());

      ReturnShipmentUtils.findReturnDocTypeForOrg("org-1", "ARC", true, false, true);

      verify(criteria, atLeastOnce()).add(argThat(ReturnShipmentUtilsTest::isRectificativeRestriction));
    } finally {
      RectificativeSupport.setColumnPresentForTests(null);
    }
  }

  /**
   * {@code requireRectificative=true} but the column is absent (SIF General not installed)
   * degrades gracefully — the restriction is silently skipped instead of blowing up the lookup.
   */
  @Test
  public void findReturnDocTypeForOrg_requireRectificativeTrueColumnAbsent_skipsRestriction() {
    RectificativeSupport.setColumnPresentForTests(false);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBCriteria<DocumentType> criteria = mockCriteria(dal, Collections.emptyList());

      ReturnShipmentUtils.findReturnDocTypeForOrg("org-1", "APC", false, false, true);

      verify(criteria, never()).add(argThat(ReturnShipmentUtilsTest::isRectificativeRestriction));
    } finally {
      RectificativeSupport.setColumnPresentForTests(null);
    }
  }

  private static boolean isRectificativeRestriction(Object criterion) {
    if (!(criterion instanceof SimpleExpression)) {
      return false;
    }
    SimpleExpression expr = (SimpleExpression) criterion;
    return DocumentType.PROPERTY_ETSGISRECTIFICATIVE.equals(expr.getPropertyName())
        && Boolean.TRUE.equals(expr.getValue());
  }

  /** A candidate matching the receipt/shipment's own org is returned directly. */
  @Test
  public void findReturnDocTypeForOrg_matchesOwnOrg_returnsIt() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType match = docTypeWithOrg("org-1");
      mockCriteria(dal, Collections.singletonList(match));

      DocumentType result = ReturnShipmentUtils.findReturnDocTypeForOrg(
          "org-1", "APC", false, false, false);

      assertSame(match, result);
    }
  }

  /** No candidate matches the org, but one has org {@code '0'} — org-'0' fallback wins. */
  @Test
  public void findReturnDocTypeForOrg_noOwnOrgMatch_fallsBackToOrgZero() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType other = docTypeWithOrg("org-99");
      DocumentType zero = docTypeWithOrg("0");
      mockCriteria(dal, java.util.Arrays.asList(other, zero));

      DocumentType result = ReturnShipmentUtils.findReturnDocTypeForOrg(
          "org-1", "APC", false, false, false);

      assertSame(zero, result);
    }
  }

  /** No org or org-'0' match — falls back to the first candidate. */
  @Test
  public void findReturnDocTypeForOrg_noMatchAtAll_fallsBackToFirstCandidate() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType first = docTypeWithOrg("org-99");
      mockCriteria(dal, Collections.singletonList(first));

      DocumentType result = ReturnShipmentUtils.findReturnDocTypeForOrg(
          "org-1", "APC", false, false, false);

      assertSame(first, result);
    }
  }

  /** No candidates at all — returns {@code null}. */
  @Test
  public void findReturnDocTypeForOrg_noCandidates_returnsNull() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      mockCriteria(dal, Collections.emptyList());

      DocumentType result = ReturnShipmentUtils.findReturnDocTypeForOrg(
          "org-1", "APC", false, false, true);

      assertNull(result);
    }
  }
}
