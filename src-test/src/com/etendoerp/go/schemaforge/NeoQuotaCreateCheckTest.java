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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.exception.GenericJDBCException;
import org.hibernate.query.Query;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usage.quota.QuotaClientCache;
import com.etendoerp.go.usage.quota.QuotaDefinition;
import com.etendoerp.go.usage.quota.QuotaEvaluation;
import com.etendoerp.go.usage.quota.QuotaPeriod;
import com.etendoerp.go.usage.quota.QuotaVerdict;
import com.etendoerp.go.usage.quota.QuotaWriteGuard;

/**
 * ETP-5051 — specs for {@link NeoQuotaCreateCheck}, the plan-quota check of a NEO action that
 * creates records itself (a document generated from another one, a clone, a conversion).
 *
 * <ul>
 *   <li>Nothing is flushed or queried when no {@code block} quota of the client counts the created
 *       entity, nor its lines.</li>
 *   <li>A document's lines are found from the DAL model: its one-to-many child collections.</li>
 *   <li>A refusal is the CRUD gate's 403 {@code QUOTA_EXCEEDED} with the {@code quota} body; an
 *       unreadable quota entry, or a check failure that poisoned the transaction (a database error
 *       in the cause chain, or a transaction marked rollback-only), fails closed with 500
 *       {@code QUOTA_CHECK_FAILED}. A failure raised before any SQL ran leaves the rows standing,
 *       as on the CRUD gate.</li>
 *   <li>{@code responseFor} finds a refusal anywhere in a cause chain; the response rolls the
 *       transaction back, except inside a batch, which owns it.</li>
 * </ul>
 *
 * <p>The guard and the DAL are mocked: what the guard decides is {@code NeoQuotaWriteGateTest}'s and
 * the quota package's to pin; this class pins what the check does with the decision.
 *
 * @covers com.etendoerp.go.schemaforge.NeoQuotaCreateCheck
 */
class NeoQuotaCreateCheckTest {

  private static final String HEADER_ENTITY = "Invoice";
  private static final String LINE_ENTITY = "InvoiceLine";
  private static final String HEADER_ID = "INV-1";

  private QuotaWriteGuard guard;
  private QuotaClientCache.Entry entry;
  private OBDal dal;
  private Session session;
  private Transaction transaction;
  private MockedStatic<OBDal> obDal;

  @BeforeEach
  void setUp() {
    guard = mock(QuotaWriteGuard.class);
    entry = mock(QuotaClientCache.Entry.class);
    when(guard.entryIfChecked(anyString())).thenReturn(Optional.empty());
    when(guard.refusalFor(any(), anyString(), any())).thenReturn(Optional.empty());
    dal = mock(OBDal.class);
    session = mock(Session.class);
    transaction = mock(Transaction.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getSession()).thenReturn(session);
    when(session.getTransaction()).thenReturn(transaction);
    when(transaction.getStatus()).thenReturn(TransactionStatus.ACTIVE);
  }

  @AfterEach
  void tearDown() {
    obDal.close();
  }

  // ── fixtures ──────────────────────────────────────────────────────────────────────────────

  private static BaseOBObject row(String entityName, String id) {
    BaseOBObject row = mock(BaseOBObject.class);
    when(row.getEntityName()).thenReturn(entityName);
    when(row.getId()).thenReturn(id);
    return row;
  }

  /** A header whose entity has one child collection of {@link #LINE_ENTITY}, plus non-children. */
  private static BaseOBObject header() {
    BaseOBObject header = row(HEADER_ENTITY, HEADER_ID);
    List<Property> properties =
        Arrays.asList(lineCollection(), plainColumn(), nonChildCollection());
    Entity entity = mock(Entity.class);
    when(entity.getProperties()).thenReturn(properties);
    when(header.getEntity()).thenReturn(entity);
    return header;
  }

  private static Property lineCollection() {
    Property lines = mock(Property.class);
    Entity lineEntity = mock(Entity.class);
    when(lineEntity.getName()).thenReturn(LINE_ENTITY);
    Property parent = mock(Property.class);
    when(parent.getName()).thenReturn("invoice");
    when(lines.isOneToMany()).thenReturn(true);
    when(lines.isChild()).thenReturn(true);
    when(lines.getReferencedProperty()).thenReturn(parent);
    when(lines.getTargetEntity()).thenReturn(lineEntity);
    return lines;
  }

  private static Property plainColumn() {
    return mock(Property.class);
  }

  /** A one-to-many that is not a child collection (e.g. a link table): never charged. */
  private static Property nonChildCollection() {
    Property other = mock(Property.class);
    Entity target = mock(Entity.class);
    when(target.getName()).thenReturn("FIN_Payment_Schedule");
    when(other.isOneToMany()).thenReturn(true);
    when(other.isChild()).thenReturn(false);
    when(other.getTargetEntity()).thenReturn(target);
    when(other.getReferencedProperty()).thenReturn(mock(Property.class));
    return other;
  }

  @SuppressWarnings("unchecked")
  private Query<String> givenLineIds(String... ids) {
    Query<String> query = mock(Query.class, RETURNS_SELF);
    when(query.list()).thenReturn(new ArrayList<>(Arrays.asList(ids)));
    when(session.createQuery(anyString(), eq(String.class))).thenReturn(query);
    return query;
  }

  private static QuotaEvaluation denial() {
    QuotaDefinition quota = QuotaDefinition.builder().resourceId("RES-INV")
        .resourceKey("CREATED_SALES_INVOICES").resourceName("Sales invoices")
        .unitLabel("Inv").enforcementMode("block").includedQty(10L).build();
    Date start = new Date(1_790_000_000_000L);
    Date end = new Date(1_792_600_000_000L);
    return new QuotaEvaluation(quota, "month", QuotaPeriod.current(start, end, false,
        new Date(1_791_000_000_000L)), 10L, QuotaVerdict.DENY);
  }

  private static RuntimeException databaseFailure() {
    return new IllegalStateException("wrapped", new GenericJDBCException(
        "could not execute query", new java.sql.SQLException("current transaction is aborted")));
  }

  private static void assertRefusal(NeoResponse response) throws Exception {
    assertEquals(403, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("QUOTA_EXCEEDED", error.getString("code"));
    assertEquals("CREATED_SALES_INVOICES", error.getJSONObject("quota").getString("resourceKey"));
  }

  private static void assertCheckFailed(NeoResponse response) throws Exception {
    assertEquals(NeoQuotaWriteGate.CHECK_FAILED_STATUS, response.getHttpStatus());
    assertEquals(NeoQuotaWriteGate.CHECK_FAILED_CODE,
        response.getBody().getJSONObject("error").getString("code"));
  }

  // ── specs ─────────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("rows no block quota counts")
  class Unchecked {

    @Test
    void rowsOfAnUncountedEntityCostNoFlushAndNoCheck() {
      NeoQuotaCreateCheck.requireAllowed(List.of(row(HEADER_ENTITY, HEADER_ID)), guard);

      verify(guard).entryIfChecked(HEADER_ENTITY);
      verify(guard, never()).refusalFor(any(), anyString(), any());
      verify(dal, never()).flush();
    }

    @Test
    void aDocumentWhoseHeaderAndLinesAreUncountedCostsNoFlushAndNoQuery() {
      NeoQuotaCreateCheck.requireAllowedDocument(header(), guard);

      verify(guard).entryIfChecked(HEADER_ENTITY);
      verify(guard).entryIfChecked(LINE_ENTITY);
      verify(dal, never()).flush();
      verifyNoInteractions(session);
      verify(guard, never()).refusalFor(any(), anyString(), any());
    }

    @Test
    void nullRowsAndRowsWithoutAnIdAreIgnored() {
      NeoQuotaCreateCheck.requireAllowed(
          Arrays.asList(null, row(HEADER_ENTITY, null), row(HEADER_ENTITY, " ")), guard);
      NeoQuotaCreateCheck.requireAllowed((List<BaseOBObject>) null, guard);
      NeoQuotaCreateCheck.requireAllowedDocument(row(HEADER_ENTITY, null), guard);

      verifyNoInteractions(guard);
      verify(dal, never()).flush();
    }

    @Test
    void theIdVariantWithABlankEntityOrNoIdsChecksNothing() {
      NeoQuotaCreateCheck.requireAllowed(" ", List.of("ID-1"), guard);
      NeoQuotaCreateCheck.requireAllowed(LINE_ENTITY, Collections.emptyList(), guard);
      NeoQuotaCreateCheck.requireAllowed(LINE_ENTITY, null, guard);

      verifyNoInteractions(guard);
      verify(dal, never()).flush();
    }

    @Test
    void thePublicEntryPointsUseTheDefaultGuard() {
      try (MockedStatic<QuotaWriteGuard> defaults = mockStatic(QuotaWriteGuard.class)) {
        defaults.when(QuotaWriteGuard::getDefault).thenReturn(guard);

        NeoQuotaCreateCheck.requireAllowed(row(HEADER_ENTITY, HEADER_ID));
        NeoQuotaCreateCheck.requireAllowed((BaseOBObject[]) null);
        NeoQuotaCreateCheck.requireAllowed(LINE_ENTITY, List.of("L-1"));
        BaseOBObject header = header();
        NeoQuotaCreateCheck.requireAllowedDocument(header);
      }

      verify(guard, org.mockito.Mockito.times(2)).entryIfChecked(HEADER_ENTITY);
      verify(guard, org.mockito.Mockito.times(2)).entryIfChecked(LINE_ENTITY);
      verify(dal, never()).flush();
    }
  }

  @Nested
  @DisplayName("a document and its lines")
  class Document {

    @Test
    void theLinesAreFoundFromTheDalModelAfterTheFlushAndCheckedByIds() throws Exception {
      when(guard.entryIfChecked(LINE_ENTITY)).thenReturn(Optional.of(entry));
      Query<String> query = givenLineIds("L-1", "L-2");

      NeoQuotaCreateCheck.requireAllowedDocument(header(), guard);

      org.mockito.InOrder order = org.mockito.Mockito.inOrder(dal, session, guard);
      order.verify(dal).flush();
      order.verify(session).createQuery(
          "select e.id from InvoiceLine e where e.invoice.id = :headerId", String.class);
      order.verify(guard).refusalFor(entry, LINE_ENTITY, List.of("L-1", "L-2"));
      verify(query).setParameter("headerId", HEADER_ID);
      // The header is uncounted: only the lines are checked.
      verify(guard, never()).refusalFor(any(), eq(HEADER_ENTITY), any());
      verify(guard, never()).entryIfChecked("FIN_Payment_Schedule");
    }

    @Test
    void aCountedHeaderIsCheckedWithoutReadingItsUncountedLines() {
      when(guard.entryIfChecked(HEADER_ENTITY)).thenReturn(Optional.of(entry));

      NeoQuotaCreateCheck.requireAllowedDocument(header(), guard);

      verify(dal).flush();
      verify(guard).refusalFor(entry, HEADER_ENTITY, List.of(HEADER_ID));
      verify(session, never()).createQuery(anyString(), eq(String.class));
    }

    @Test
    void aRefusedLineRefusesTheDocument() throws Exception {
      when(guard.entryIfChecked(LINE_ENTITY)).thenReturn(Optional.of(entry));
      givenLineIds("L-1");
      when(guard.refusalFor(entry, LINE_ENTITY, List.of("L-1"))).thenReturn(Optional.of(denial()));
      BaseOBObject header = header();

      NeoQuotaCreateCheck.Refused refused = assertThrows(NeoQuotaCreateCheck.Refused.class,
          () -> NeoQuotaCreateCheck.requireAllowedDocument(header, guard));

      assertRefusal(refused.response());
    }

    @Test
    void childCollectionsKeepsOnlyOneToManyChildrenWithAParentAndATarget() {
      Property noParent = lineCollection();
      when(noParent.getReferencedProperty()).thenReturn(null);
      Property noTarget = lineCollection();
      when(noTarget.getTargetEntity()).thenReturn(null);
      Property lines = lineCollection();
      List<Property> properties =
          Arrays.asList(plainColumn(), nonChildCollection(), noParent, noTarget, lines);
      Entity entity = mock(Entity.class);
      when(entity.getProperties()).thenReturn(properties);

      assertEquals(List.of(lines), NeoQuotaCreateCheck.childCollections(entity));
      assertTrue(NeoQuotaCreateCheck.childCollections(null).isEmpty());
    }
  }

  @Nested
  @DisplayName("rows added by id (requireAllowed(entity, ids))")
  class ById {

    @Test
    void countedRowsAreFlushedAndCheckedByTheirIds() {
      when(guard.entryIfChecked(LINE_ENTITY)).thenReturn(Optional.of(entry));

      NeoQuotaCreateCheck.requireAllowed(LINE_ENTITY, List.of("L-7", "L-8"), guard);

      org.mockito.InOrder order = org.mockito.Mockito.inOrder(dal, guard);
      order.verify(dal).flush();
      order.verify(guard).refusalFor(entry, LINE_ENTITY, List.of("L-7", "L-8"));
    }

    @Test
    void uncountedRowsCostNoFlush() {
      NeoQuotaCreateCheck.requireAllowed(LINE_ENTITY, List.of("L-7"), guard);

      verify(dal, never()).flush();
      verify(guard, never()).refusalFor(any(), anyString(), any());
    }

    @Test
    void aRefusalOfAddedRowsIsThrown() throws Exception {
      when(guard.entryIfChecked(LINE_ENTITY)).thenReturn(Optional.of(entry));
      when(guard.refusalFor(entry, LINE_ENTITY, List.of("L-7"))).thenReturn(Optional.of(denial()));

      NeoQuotaCreateCheck.Refused refused = assertThrows(NeoQuotaCreateCheck.Refused.class,
          () -> NeoQuotaCreateCheck.requireAllowed(LINE_ENTITY, List.of("L-7"), guard));

      assertRefusal(refused.response());
      assertNull(refused.getCause(), "a refusal is a verdict, not a failure");
    }
  }

  @Nested
  @DisplayName("a refusal")
  class Refusal {

    @Test
    void carries403QuotaExceededWithTheQuotaBodyAndItsMessage() throws Exception {
      when(guard.entryIfChecked(HEADER_ENTITY)).thenReturn(Optional.of(entry));
      when(guard.refusalFor(entry, HEADER_ENTITY, List.of(HEADER_ID)))
          .thenReturn(Optional.of(denial()));

      NeoQuotaCreateCheck.Refused refused = assertThrows(NeoQuotaCreateCheck.Refused.class,
          () -> NeoQuotaCreateCheck.requireAllowed(List.of(row(HEADER_ENTITY, HEADER_ID)),
              guard));

      assertRefusal(refused.response());
      assertEquals(refused.response().getBody().getJSONObject("error").getString("message"),
          refused.getMessage());
      // response() never touches the transaction.
      verify(dal, never()).rollbackAndClose();
    }

    @Test
    void theFirstRefusedEntityStopsTheCheck() {
      when(guard.entryIfChecked(HEADER_ENTITY)).thenReturn(Optional.of(entry));
      when(guard.entryIfChecked(LINE_ENTITY)).thenReturn(Optional.of(entry));
      when(guard.refusalFor(entry, HEADER_ENTITY, List.of(HEADER_ID)))
          .thenReturn(Optional.of(denial()));

      assertThrows(NeoQuotaCreateCheck.Refused.class, () -> NeoQuotaCreateCheck.requireAllowed(
          List.of(row(HEADER_ENTITY, HEADER_ID), row(LINE_ENTITY, "L-1")), guard));

      verify(guard, never()).refusalFor(any(), eq(LINE_ENTITY), any());
    }
  }

  @Nested
  @DisplayName("a failed check")
  class FailedCheck {

    @Test
    void anUnreadableQuotaEntryFailsClosedBeforeAnyFlush() throws Exception {
      when(guard.entryIfChecked(HEADER_ENTITY)).thenThrow(databaseFailure());

      NeoQuotaCreateCheck.Refused refused = assertThrows(NeoQuotaCreateCheck.Refused.class,
          () -> NeoQuotaCreateCheck.requireAllowed(List.of(row(HEADER_ENTITY, HEADER_ID)),
              guard));

      assertCheckFailed(refused.response());
      verify(dal, never()).flush();
      verify(guard, never()).refusalFor(any(), anyString(), any());
    }

    @Test
    void anEntryThatCannotBeReadEvenWithoutADatabaseErrorFailsClosed() throws Exception {
      when(guard.entryIfChecked(LINE_ENTITY)).thenThrow(new IllegalStateException("no cache"));
      BaseOBObject header = header();

      NeoQuotaCreateCheck.Refused refused = assertThrows(NeoQuotaCreateCheck.Refused.class,
          () -> NeoQuotaCreateCheck.requireAllowedDocument(header, guard));

      assertCheckFailed(refused.response());
      verify(dal, never()).flush();
    }

    @Test
    void aDatabaseErrorDuringTheCheckFailsClosedWith500() throws Exception {
      when(guard.entryIfChecked(HEADER_ENTITY)).thenReturn(Optional.of(entry));
      RuntimeException failure = databaseFailure();
      when(guard.refusalFor(entry, HEADER_ENTITY, List.of(HEADER_ID))).thenThrow(failure);

      NeoQuotaCreateCheck.Refused refused = assertThrows(NeoQuotaCreateCheck.Refused.class,
          () -> NeoQuotaCreateCheck.requireAllowed(List.of(row(HEADER_ENTITY, HEADER_ID)),
              guard));

      assertCheckFailed(refused.response());
      assertSame(failure, refused.getCause());
    }

    @Test
    void aCheckThatLeftTheTransactionRollbackOnlyFailsClosedWith500() throws Exception {
      when(guard.entryIfChecked(HEADER_ENTITY)).thenReturn(Optional.of(entry));
      when(guard.refusalFor(entry, HEADER_ENTITY, List.of(HEADER_ID)))
          .thenThrow(new IllegalStateException("boom"));
      when(transaction.getStatus()).thenReturn(TransactionStatus.MARKED_ROLLBACK);

      NeoQuotaCreateCheck.Refused refused = assertThrows(NeoQuotaCreateCheck.Refused.class,
          () -> NeoQuotaCreateCheck.requireAllowed(List.of(row(HEADER_ENTITY, HEADER_ID)),
              guard));

      assertCheckFailed(refused.response());
    }

    /**
     * The CRUD gate's rule: a failure raised before any SQL ran (an invalid restriction fragment)
     * left the transaction usable, so it is logged and the created rows stand.
     */
    @Test
    void aFailureBeforeAnySqlOnAHealthyTransactionLetsTheRowsStand() {
      when(guard.entryIfChecked(HEADER_ENTITY)).thenReturn(Optional.of(entry));
      when(guard.refusalFor(entry, HEADER_ENTITY, List.of(HEADER_ID)))
          .thenThrow(new IllegalArgumentException("bad restriction fragment"));

      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> NeoQuotaCreateCheck
          .requireAllowed(List.of(row(HEADER_ENTITY, HEADER_ID)), guard));

      verify(guard).refusalFor(entry, HEADER_ENTITY, List.of(HEADER_ID));
      verify(dal, never()).rollbackAndClose();
    }
  }

  @Nested
  @DisplayName("responseFor and toResponse")
  class Responses {

    private NeoQuotaCreateCheck.Refused refused() {
      return new NeoQuotaCreateCheck.Refused(NeoQuotaWriteGate.refusalResponse(denial()), null);
    }

    @Test
    void aRefusalThatEscapedIsAnsweredAndTheTransactionRolledBack() throws Exception {
      NeoResponse response = NeoQuotaCreateCheck.responseFor(refused());

      assertRefusal(response);
      verify(dal).rollbackAndClose();
    }

    @Test
    void aRefusalWrappedInTheCauseChainIsFound() throws Exception {
      RuntimeException wrapped = new RuntimeException("handler",
          new IllegalStateException("service", refused()));

      assertRefusal(NeoQuotaCreateCheck.responseFor(wrapped));
      verify(dal).rollbackAndClose();
    }

    @Test
    void aFailureWithoutARefusalIsNotMapped() {
      assertNull(NeoQuotaCreateCheck.responseFor(new RuntimeException("boom",
          new IllegalStateException("cause"))));
      assertNull(NeoQuotaCreateCheck.responseFor(null));
      verify(dal, never()).rollbackAndClose();
    }

    @Test
    void aRefusalDeeperThanTheWalkLimitIsNotMapped() {
      Throwable chain = refused();
      for (int i = 0; i < 40; i++) {
        chain = new RuntimeException("level " + i, chain);
      }

      assertNull(NeoQuotaCreateCheck.responseFor(chain));
    }

    @Test
    void insideABatchTheResponseLeavesTheTransactionToTheBatch() throws Exception {
      BatchService.beginCallerOwnedTransaction();
      NeoResponse response;
      try {
        response = refused().toResponse();
      } finally {
        BatchService.endCallerOwnedTransaction();
      }

      assertRefusal(response);
      verify(dal, never()).rollbackAndClose();
    }

    @Test
    void insideABatchAWrappedRefusalIsStillAnsweredWithoutRollingBack() throws Exception {
      BatchService.beginCallerOwnedTransaction();
      NeoResponse response;
      try {
        response = NeoQuotaCreateCheck.responseFor(new RuntimeException(refused()));
      } finally {
        BatchService.endCallerOwnedTransaction();
      }

      assertRefusal(response);
      verify(dal, never()).rollbackAndClose();
    }

    @Test
    void aRefusalWithoutAnErrorObjectFallsBackToTheBodyOrTheDefaultMessage() throws Exception {
      assertEquals("Quota refusal", new NeoQuotaCreateCheck.Refused(null, null).getMessage());
      JSONObject body = new JSONObject().put("detail", "x");
      assertEquals(body.toString(),
          new NeoQuotaCreateCheck.Refused(NeoResponse.error(403, body), null).getMessage());
    }
  }
}
