/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentProposal;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentPropDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.PisDeferredPaymentService;
import com.etendoerp.go.schemaforge.ReconciliationHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.payment.removal.util.PaymentRemovalUtil;

/**
 * Unit tests for {@link ReactivatePaymentHandler}.
 *
 * <p>Covers the ETP-4479 addition: on a single-record GET, {@code afterHandle} injects a
 * nullable {@code financialTransactionId} so the payment-in / payment-out detail view can
 * navigate to the reconciled {@code FIN_Finacc_Transaction}. The Reactivate/Confirm action
 * behavior is pre-existing and only re-verified at the {@code @Named} qualifier level here.
 * The Remove action ({@code eTPRRemovePayment}) is fully covered below — it is the new
 * behavior fixing the "cannot be deleted, see Linked Items" FK violation on applied payments.
 */
public class ReactivatePaymentHandlerTest {

  /**
   * The tenant re-check reads the thread's {@link OBContext}; a context another test class left on
   * the shared worker thread made every own payment look foreign (404). Start and end clean.
   */
  @Before
  public void clearContextBefore() {
    OBContext.setOBContext((OBContext) null);
  }

  @After
  public void clearContextAfter() {
    OBContext.setOBContext((OBContext) null);
  }

  private static NeoContext getCtx(String recordId, String method) {
    return NeoContext.builder()
        .specName("payment-in")
        .entityName("finPayment")
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  private static NeoContext removeActionCtx(String recordId) {
    return NeoContext.builder()
        .specName("payment-in")
        .entityName("finPayment")
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("eTPRRemovePayment")
        .recordId(recordId)
        .build();
  }

  /** An ACTION context for {@code fieldName} on the payment record. */
  private static NeoContext actionCtx(String fieldName, String recordId) {
    return NeoContext.builder()
        .specName("payment-out")
        .entityName("finPayment")
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(fieldName)
        .recordId(recordId)
        .build();
  }

  private static JSONObject singleRecordBody(String id) throws JSONException {
    JSONArray data = new JSONArray().put(new JSONObject().put("id", id));
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static NeoContext ctxWithPreviousResult(String recordId, JSONObject body) {
    NeoContext ctx = getCtx(recordId, "GET");
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  private static FIN_PaymentScheduleDetail mockScheduleDetail() {
    FIN_PaymentScheduleDetail scheduleDetail = mock(FIN_PaymentScheduleDetail.class);
    // Default to no prop-details (the common case) so findProcessedProposalPropDetail's and
    // removeApplicationDetails' for-each loops over this collection don't NPE on an unstubbed
    // mock. Tests exercising the Payment Proposal guard override this explicitly.
    when(scheduleDetail.getFINPaymentPropDetailList()).thenReturn(new ArrayList<>());
    return scheduleDetail;
  }

  /**
   * A schedule detail linked to an INVOICE installment — i.e. a "document-linked" row, the only
   * kind {@code releaseInstallmentsToPending} hands back to Core (ETP-4841).
   */
  private static FIN_PaymentScheduleDetail mockInvoiceLinkedScheduleDetail() {
    FIN_PaymentScheduleDetail scheduleDetail = mockScheduleDetail();
    when(scheduleDetail.getInvoicePaymentSchedule()).thenReturn(mock(FIN_PaymentSchedule.class));
    return scheduleDetail;
  }

  private static FIN_PaymentDetail mockDetailWith(List<FIN_PaymentScheduleDetail> scheduleDetails) {
    FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
    when(detail.getFINPaymentScheduleDetailList()).thenReturn(scheduleDetails);
    return detail;
  }

  /**
   * Convenience wrapper for the exact {@code FIN_AddPayment.updatePaymentDetail} overload and
   * argument tuple {@code releaseInstallmentsToPending} uses: zero amount, no write-off. Verifying
   * this precise tuple (rather than {@code any()}) is deliberate — releasing with any amount other
   * than {@code ZERO}, or with {@code writeoffDifference = true}, would NOT leave the unlinked
   * pending fragment the later payment registration depends on.
   */
  private static void verifyReleasedToPending(MockedStatic<FIN_AddPayment> addPaymentMock,
      FIN_PaymentScheduleDetail scheduleDetail, FIN_Payment payment) {
    addPaymentMock.verify(
        () -> FIN_AddPayment.updatePaymentDetail(scheduleDetail, payment, BigDecimal.ZERO, false));
  }

  private static void verifyNothingReleasedToPending(MockedStatic<FIN_AddPayment> addPaymentMock) {
    addPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        Mockito.any(FIN_PaymentScheduleDetail.class), Mockito.any(FIN_Payment.class),
        Mockito.any(BigDecimal.class), Mockito.anyBoolean()), never());
  }

  private static void verifyNoInvoiceRecompute(MockedStatic<PaymentRemovalUtil> removalUtilMock) {
    removalUtilMock.verify(
        () -> PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(Mockito.anySet()), never());
  }

  // ── @Named qualifier ──────────────────────────────────────────────────────

  /**
   * The class must carry {@code @Named("payment-reactivate")} so {@code lookupHandler()} can
   * match it against the {@code ETGO_SF_ENTITY.Java_Qualifier} value shared by both the
   * payment-in and payment-out windows.
   */
  @Test
  public void carriesPaymentReactivateNamedQualifier() {
    Named named = ReactivatePaymentHandler.class.getAnnotation(Named.class);
    assertNotNull("ReactivatePaymentHandler must be annotated @Named", named);
    assertEquals("payment-reactivate", named.value());
  }

  // ── handle — Remove action (eTPRRemovePayment) ────────────────────────────
  //
  // IMPORTANT — coverage gap (reject-cycle 1, ETP-4479): every test below mocks OBDal and
  // returns plain Mockito/ArrayList collections for getFINPaymentDetailList() /
  // getFINPaymentScheduleDetailList(). That structurally CANNOT catch the class of bug that
  // caused the live 500 ("EntityNotFoundException: deleted object would be re-saved by
  // cascade"): a real Hibernate session dirty-checks a still-loaded PARENT collection against
  // its own change-tracking metadata at flush time, and a mocked List has no such semantics —
  // verify(dal).remove(child) passes whether or not the child was also detached from the
  // parent's collection, which is exactly the distinction that mattered here. These tests only
  // prove "our code called remove()/reactivate() on the right objects, in the right order" —
  // they do NOT prove the fix is safe against a real Hibernate flush. That can only be verified
  // by an OBBaseTest-based integration test against a real session (see
  // ReactivatePaymentHandlerRemoveIntegrationTest in this package) or by manual live
  // verification against a real applied payment, as was done to find (and, across three
  // reject cycles, fix) this regression. QA: re-test the live scenario again after this fix,
  // don't rely on this unit suite passing as sufficient evidence.
  //
  // ETP-4841 note — what mocks CAN pin here, and why: unlike the reject-cycle-1 Hibernate-cascade
  // bug above, the draft-delete regression lives entirely in two CONDITIONALS inside handleRemove
  // — one calling releaseInstallmentsToPending when the payment was NOT processed, the other
  // calling updateInvoicesAfterPaymentRemoval when it was — both keyed off a wasProcessed flag
  // captured BEFORE reactivation. Branch selection is exactly what a static mock observes
  // reliably, so the tests below are genuine regression guards for both halves of that fix,
  // not smoke tests. What they
  // still cannot prove is the resulting DB state (that an unlinked, payable FIN_PaymentScheduleDetail
  // fragment actually survives, and that the invoice's paid/outstanding amounts are untouched) —
  // that is what ReactivatePaymentHandlerDraftRemoveIntegrationTest in this package asserts against
  // a real session, and what was confirmed live on invoice 10000074.
  //
  // Reject-cycle 3 note: an earlier design delegated the final removal step to
  // NeoButtonActionHelper.executeButtonActionCore(context.getSfEntity(), ...), which broke
  // because reactivating a reconciled payment clears the whole Hibernate session, stranding
  // context.getSfEntity()'s lazily-loaded AD_Tab proxy. That delegation (and the
  // context.getSfEntity() dependency for this action) has been removed entirely — handleRemove
  // now calls PaymentRemovalUtil.remove(payment) directly — so these tests no longer need to
  // mock NeoButtonActionHelper or stub an sfEntity's AD_Tab/Table at all.

  /**
   * Payment applied to an invoice (the reported bug scenario): {@code handle} must delete the
   * {@code FIN_PaymentScheduleDetail} and {@code FIN_PaymentDetail} join rows itself (via
   * {@code OBDal.remove}), call {@code collectAffectedInvoiceIds} BEFORE that cleanup (it
   * reads the same list being deleted), then remove the payment itself via {@code
   * PaymentRemovalUtil.remove(payment)}.
   *
   * <p><b>Updated for ETP-4841.</b> This test previously asserted that {@code
   * updateInvoicesAfterPaymentRemoval} IS called here. That assertion was wrong (it merely
   * mirrored the then-unconditional call): the payment in this fixture is NOT processed
   * ({@code isProcessed()} is unstubbed, i.e. {@code null}), and recomputing the invoice
   * aggregates on the draft path actively corrupts the invoice — {@code
   * PaymentRemovalUtil.sumDetails()} sums every schedule detail of the installment without
   * checking {@code paymentDetails}, so the pending fragment just restored by {@code
   * releaseInstallmentsToPending} is counted as "paid", leaving {@code paidAmount} = full,
   * {@code outstandingAmount} = 0 and the invoice flagged {@code paymentComplete} (observed live
   * on a 39.93 EUR invoice). A draft never moved those aggregates in the first place, so there is
   * nothing to recompute. The expectation is therefore now {@code never()}.
   */
  @Test
  public void handleRemoveCleansUpInvoiceAppliedDetailsBeforeRemovingPayment() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail scheduleDetail = mockInvoiceLinkedScheduleDetail();
    // Real mutable ArrayLists (not Arrays.asList/List.of), matching how a real Hibernate-backed
    // getter returns the actual persistent collection instance: production code calls
    // .remove(...) on these directly to detach the child, so an immutable list here would throw
    // UnsupportedOperationException and (correctly) fail this test if that detachment call were
    // ever removed by a future edit.
    List<FIN_PaymentScheduleDetail> scheduleDetailList = new ArrayList<>(List.of(scheduleDetail));
    FIN_PaymentDetail detail = mockDetailWith(scheduleDetailList);
    List<FIN_PaymentDetail> detailList = new ArrayList<>(List.of(detail));
    when(payment.getFINPaymentDetailList()).thenReturn(detailList);

    Set<String> affectedInvoiceIds = new HashSet<>(Collections.singletonList("inv-1"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-1")).thenReturn(payment);

      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(affectedInvoiceIds);

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-1"));

      assertEquals(200, result.getHttpStatus());
      // Cleanup happened: both the schedule-detail and the detail itself were removed.
      verify(dal).remove(scheduleDetail);
      verify(dal).remove(detail);
      // Invoice ids are still collected BEFORE cleanup (they are what the processed path uses).
      removalUtilMock.verify(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment));
      // ETP-4841: draft path — no aggregate recompute (see javadoc).
      verifyNoInvoiceRecompute(removalUtilMock);
      // The payment itself is removed directly — no more button-action delegation.
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(payment));
      assertFalse("detail must be removed from payment.getFINPaymentDetailList()",
          detailList.contains(detail));
      assertFalse("scheduleDetail must be removed from detail.getFINPaymentScheduleDetailList()",
          scheduleDetailList.contains(scheduleDetail));
      // payment.isProcessed() is null (unstubbed) here, i.e. "not processed" — reactivate must
      // NOT be called (reject-cycle 2 regression check: only processed payments reactivate).
      removalUtilMock.verify(
          () -> PaymentRemovalUtil.reactivate(Mockito.anyString(), Mockito.anyString()), never());
    }
  }

  /**
   * Reject-cycle 2: a still-{@code Processed} payment must be reactivated (via {@code
   * PaymentRemovalUtil.reactivate(id, "R")}) BEFORE any detail row is touched, because a core
   * AD trigger ({@code aprm_fin_pmt_detail_check_trg}) blocks deleting {@code
   * FIN_Payment_Detail} rows while {@code FIN_Payment.Processed = 'Y'}. After reactivating, the
   * handler must re-fetch the payment (not reuse the stale local reference) before running
   * cleanup and the final {@code PaymentRemovalUtil.remove(...)} call.
   */
  @Test
  public void handleRemoveReactivatesProcessedPaymentBeforeCleanup() throws Exception {
    FIN_Payment processedPayment = mock(FIN_Payment.class);
    when(processedPayment.isProcessed()).thenReturn(true);
    when(processedPayment.getId()).thenReturn("pay-processed");
    when(processedPayment.getFINPaymentDetailList()).thenReturn(new ArrayList<>());

    FIN_Payment reactivatedPayment = mock(FIN_Payment.class);
    when(reactivatedPayment.isProcessed()).thenReturn(false);
    when(reactivatedPayment.getFINPaymentDetailList()).thenReturn(new ArrayList<>());

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // First load returns the still-processed payment; the post-reactivate re-fetch returns a
      // fresh (already-reactivated) instance — exactly what a real Hibernate session would
      // hand back after PaymentRemovalUtil.reactivate() mutates and reloads state internally.
      when(dal.get(FIN_Payment.class, "pay-processed"))
          .thenReturn(processedPayment, reactivatedPayment);

      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(reactivatedPayment))
          .thenReturn(Collections.emptySet());

      NeoResponse result =
          new ReactivatePaymentHandler().handle(removeActionCtx("pay-processed"));

      assertEquals(200, result.getHttpStatus());
      removalUtilMock.verify(() -> PaymentRemovalUtil.reactivate("pay-processed", "R"));
      // Cleanup and the final removal both operated on the RE-FETCHED (reactivated) instance,
      // not the stale one from before reactivation.
      removalUtilMock.verify(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(reactivatedPayment));
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(reactivatedPayment));
      verify(dal, Mockito.times(2)).get(FIN_Payment.class, "pay-processed");
    }
  }

  /**
   * Reject-cycle 2: a payment generated from a processed Payment Proposal cannot be removed
   * through this action at all — {@code aprm_fin_prop_detail_check_trg} blocks mutating its
   * {@code FIN_Payment_Prop_Detail} rows regardless of the payment's own processed state
   * (reactivating the payment does not touch the proposal's separate {@code Processed} flag).
   * The handler must detect this upfront and return a clear 400 — no cleanup, no reactivate,
   * no removal.
   */
  @Test
  public void handleRemoveRefusesPaymentTiedToProcessedProposal() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail scheduleDetail = mock(FIN_PaymentScheduleDetail.class);
    FIN_PaymentPropDetail propDetail = mock(FIN_PaymentPropDetail.class);
    FIN_PaymentProposal proposal = mock(FIN_PaymentProposal.class);
    when(proposal.isProcessed()).thenReturn(true);
    when(proposal.getIdentifier()).thenReturn("MPP-001");
    when(propDetail.getFinPaymentProposal()).thenReturn(proposal);
    when(scheduleDetail.getFINPaymentPropDetailList())
        .thenReturn(new ArrayList<>(List.of(propDetail)));
    FIN_PaymentDetail detail = mockDetailWith(new ArrayList<>(List.of(scheduleDetail)));
    when(payment.getFINPaymentDetailList()).thenReturn(new ArrayList<>(List.of(detail)));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-proposal")).thenReturn(payment);

      NeoResponse result =
          new ReactivatePaymentHandler().handle(removeActionCtx("pay-proposal"));

      assertEquals(400, result.getHttpStatus());
      assertTrue("error message should name the blocking proposal",
          result.getBody().toString().contains("MPP-001"));
      verify(dal, never()).remove(Mockito.any());
      removalUtilMock.verifyNoInteractions();
    }
  }

  /**
   * Payment applied to an order rather than an invoice: {@code FIN_PaymentScheduleDetail}
   * exposes the association the same way via {@code getOrderPaymentSchedule()}. The handler's
   * own cleanup does not distinguish invoice vs. order — it removes the join rows
   * unconditionally — so this must succeed identically; only the affected-invoice-ids
   * collection differs (empty, since order applications do not recalculate an invoice).
   *
   * <p><b>Updated for ETP-4841</b> (same reason as {@link
   * #handleRemoveCleansUpInvoiceAppliedDetailsBeforeRemovingPayment}): the fixture payment is not
   * processed, so the aggregate recompute must NOT run. An order-linked schedule detail IS still
   * released back to pending — {@code releaseInstallmentsToPending} treats {@code
   * getOrderPaymentSchedule() != null} exactly like the invoice case.
   */
  @Test
  public void handleRemoveCleansUpOrderAppliedDetailsBeforeRemovingPayment() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail scheduleDetail = mockScheduleDetail();
    FIN_PaymentSchedule orderSchedule = mock(FIN_PaymentSchedule.class);
    when(scheduleDetail.getOrderPaymentSchedule()).thenReturn(orderSchedule);
    List<FIN_PaymentScheduleDetail> scheduleDetailList = new ArrayList<>(List.of(scheduleDetail));
    FIN_PaymentDetail detail = mockDetailWith(scheduleDetailList);
    List<FIN_PaymentDetail> detailList = new ArrayList<>(List.of(detail));
    when(payment.getFINPaymentDetailList()).thenReturn(detailList);

    Set<String> noAffectedInvoices = Collections.emptySet();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-2")).thenReturn(payment);

      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(noAffectedInvoices);

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-2"));

      assertEquals(200, result.getHttpStatus());
      verify(dal).remove(scheduleDetail);
      verify(dal).remove(detail);
      verifyReleasedToPending(addPaymentMock, scheduleDetail, payment);
      verifyNoInvoiceRecompute(removalUtilMock);
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(payment));
      assertFalse("detail must be removed from payment.getFINPaymentDetailList()",
          detailList.contains(detail));
      assertFalse("scheduleDetail must be removed from detail.getFINPaymentScheduleDetailList()",
          scheduleDetailList.contains(scheduleDetail));
    }
  }

  /**
   * QA edge case (ETP-4479): a payment applied to MULTIPLE documents (two independent
   * {@code FIN_PaymentDetail} rows, each with its own {@code FIN_PaymentScheduleDetail}) must
   * have every detail/scheduleDetail pair cleaned up, not just the first one the loop visits.
   * Guards against a regression where only {@code details.get(0)} (or an early-exit loop) gets
   * detached/removed while the second application silently keeps its stale join row.
   *
   * <p><b>Updated for ETP-4841</b> (same reason as {@link
   * #handleRemoveCleansUpInvoiceAppliedDetailsBeforeRemovingPayment}): the fixture payment is not
   * processed, so no aggregate recompute. Additionally asserts the ETP-4841 multi-application
   * analogue: EVERY installment is released back to pending, not just the first — otherwise the
   * second invoice would be left permanently unpayable.
   */
  @Test
  public void handleRemoveCleansUpAllDetailsWhenPaymentHasMultipleApplications() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);

    FIN_PaymentScheduleDetail scheduleDetail1 = mockInvoiceLinkedScheduleDetail();
    List<FIN_PaymentScheduleDetail> scheduleDetailList1 = new ArrayList<>(List.of(scheduleDetail1));
    FIN_PaymentDetail detail1 = mockDetailWith(scheduleDetailList1);

    FIN_PaymentScheduleDetail scheduleDetail2 = mockInvoiceLinkedScheduleDetail();
    List<FIN_PaymentScheduleDetail> scheduleDetailList2 = new ArrayList<>(List.of(scheduleDetail2));
    FIN_PaymentDetail detail2 = mockDetailWith(scheduleDetailList2);

    List<FIN_PaymentDetail> detailList = new ArrayList<>(List.of(detail1, detail2));
    when(payment.getFINPaymentDetailList()).thenReturn(detailList);

    Set<String> affectedInvoiceIds = new HashSet<>(List.of("inv-1", "inv-2"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-multi")).thenReturn(payment);

      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(affectedInvoiceIds);

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-multi"));

      assertEquals(200, result.getHttpStatus());
      // Both applications were cleaned up, not just the first.
      verify(dal).remove(scheduleDetail1);
      verify(dal).remove(detail1);
      verify(dal).remove(scheduleDetail2);
      verify(dal).remove(detail2);
      assertTrue("detail1 must be detached from its own scheduleDetail list",
          scheduleDetailList1.isEmpty());
      assertTrue("detail2 must be detached from its own scheduleDetail list",
          scheduleDetailList2.isEmpty());
      assertTrue("both details must be detached from payment.getFINPaymentDetailList()",
          detailList.isEmpty());
      // ETP-4841: both installments released, no aggregate recompute on the draft path.
      verifyReleasedToPending(addPaymentMock, scheduleDetail1, payment);
      verifyReleasedToPending(addPaymentMock, scheduleDetail2, payment);
      verifyNoInvoiceRecompute(removalUtilMock);
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(payment));
    }
  }

  /**
   * QA edge case (ETP-4479): {@code findProcessedProposalPropDetail} must only block on a
   * PROCESSED proposal — a prop-detail tied to an unprocessed (still-draft/not-yet-finalized)
   * {@code FIN_Payment_Proposal} must NOT prevent removal. Distinguishes the guard's condition
   * ({@code isProcessed() == true}) from merely "prop-detail exists".
   */
  @Test
  public void handleRemoveProceedsWhenProposalIsNotProcessed() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail scheduleDetail = mock(FIN_PaymentScheduleDetail.class);
    FIN_PaymentPropDetail propDetail = mock(FIN_PaymentPropDetail.class);
    FIN_PaymentProposal unprocessedProposal = mock(FIN_PaymentProposal.class);
    when(unprocessedProposal.isProcessed()).thenReturn(false);
    when(propDetail.getFinPaymentProposal()).thenReturn(unprocessedProposal);
    List<FIN_PaymentPropDetail> propDetailList = new ArrayList<>(List.of(propDetail));
    when(scheduleDetail.getFINPaymentPropDetailList()).thenReturn(propDetailList);
    List<FIN_PaymentScheduleDetail> scheduleDetailList = new ArrayList<>(List.of(scheduleDetail));
    FIN_PaymentDetail detail = mockDetailWith(scheduleDetailList);
    List<FIN_PaymentDetail> detailList = new ArrayList<>(List.of(detail));
    when(payment.getFINPaymentDetailList()).thenReturn(detailList);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-unproc-proposal")).thenReturn(payment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(Collections.emptySet());

      NeoResponse result =
          new ReactivatePaymentHandler().handle(removeActionCtx("pay-unproc-proposal"));

      assertEquals(200, result.getHttpStatus());
      // The prop-detail itself is still cleaned up (its owning proposal is not processed, so
      // aprm_fin_prop_detail_check_trg does not block it).
      verify(dal).remove(propDetail);
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(payment));
    }
  }

  /**
   * QA edge case (ETP-4479): a {@code FIN_PaymentPropDetail} whose {@code
   * getFinPaymentProposal()} is {@code null} (e.g. a data inconsistency, or a prop-detail type
   * this handler wasn't specifically designed around) must not NPE the guard — {@code
   * findProcessedProposalPropDetail} explicitly null-checks {@code proposal} before calling
   * {@code isProcessed()}. Removal must proceed normally.
   */
  @Test
  public void handleRemoveProceedsWhenPropDetailHasNullProposal() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail scheduleDetail = mock(FIN_PaymentScheduleDetail.class);
    FIN_PaymentPropDetail propDetail = mock(FIN_PaymentPropDetail.class);
    // getFinPaymentProposal() deliberately left unstubbed -> returns null.
    List<FIN_PaymentPropDetail> propDetailList = new ArrayList<>(List.of(propDetail));
    when(scheduleDetail.getFINPaymentPropDetailList()).thenReturn(propDetailList);
    List<FIN_PaymentScheduleDetail> scheduleDetailList = new ArrayList<>(List.of(scheduleDetail));
    FIN_PaymentDetail detail = mockDetailWith(scheduleDetailList);
    List<FIN_PaymentDetail> detailList = new ArrayList<>(List.of(detail));
    when(payment.getFINPaymentDetailList()).thenReturn(detailList);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-null-proposal")).thenReturn(payment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(Collections.emptySet());

      NeoResponse result =
          new ReactivatePaymentHandler().handle(removeActionCtx("pay-null-proposal"));

      assertEquals(200, result.getHttpStatus());
      verify(dal).remove(propDetail);
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(payment));
    }
  }

  /**
   * QA edge case (ETP-4479): the processed-proposal guard must keep scanning past the FIRST
   * schedule-detail/prop-detail it visits — a payment with two applications where only the
   * SECOND one is tied to a processed proposal must still be blocked (400), not waved through
   * because the first application looked clean. Also confirms no cleanup runs on either
   * application once the guard trips.
   */
  @Test
  public void handleRemoveRefusesWhenSecondApplicationHasProcessedProposal() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);

    // First application: clean, no prop-details at all.
    FIN_PaymentScheduleDetail scheduleDetail1 = mockScheduleDetail();
    FIN_PaymentDetail detail1 = mockDetailWith(new ArrayList<>(List.of(scheduleDetail1)));

    // Second application: tied to a processed proposal.
    FIN_PaymentScheduleDetail scheduleDetail2 = mock(FIN_PaymentScheduleDetail.class);
    FIN_PaymentPropDetail propDetail = mock(FIN_PaymentPropDetail.class);
    FIN_PaymentProposal processedProposal = mock(FIN_PaymentProposal.class);
    when(processedProposal.isProcessed()).thenReturn(true);
    when(processedProposal.getIdentifier()).thenReturn("MPP-002");
    when(propDetail.getFinPaymentProposal()).thenReturn(processedProposal);
    when(scheduleDetail2.getFINPaymentPropDetailList())
        .thenReturn(new ArrayList<>(List.of(propDetail)));
    FIN_PaymentDetail detail2 = mockDetailWith(new ArrayList<>(List.of(scheduleDetail2)));

    when(payment.getFINPaymentDetailList())
        .thenReturn(new ArrayList<>(List.of(detail1, detail2)));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-second-blocked")).thenReturn(payment);

      NeoResponse result =
          new ReactivatePaymentHandler().handle(removeActionCtx("pay-second-blocked"));

      assertEquals(400, result.getHttpStatus());
      assertTrue("error message should name the blocking proposal from the SECOND application",
          result.getBody().toString().contains("MPP-002"));
      verify(dal, never()).remove(Mockito.any());
      removalUtilMock.verifyNoInteractions();
    }
  }

  /**
   * Regression check: a payment with no applied details (draft/unapplied payment) must go
   * through unchanged — the cleanup loop is a no-op (nothing removed), and the handler still
   * removes the payment itself exactly as it did before this fix.
   */
  @Test
  public void handleRemoveIsNoOpCleanupWhenPaymentHasNoDetails() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getFINPaymentDetailList()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-3")).thenReturn(payment);

      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(Collections.emptySet());

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-3"));

      assertEquals(200, result.getHttpStatus());
      verify(dal, never()).remove(Mockito.any(FIN_PaymentDetail.class));
      verify(dal, never()).remove(Mockito.any(FIN_PaymentScheduleDetail.class));
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(payment));
    }
  }

  /**
   * If cleanup throws (e.g. an unexpected DB error while removing the join rows), {@code
   * handle} must not propagate the exception — it logs and returns a 500 {@code
   * NeoResponse.error(...)} instead.
   */
  @Test
  public void handleRemoveReturns500WhenCleanupThrows() {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getFINPaymentDetailList()).thenThrow(new RuntimeException("boom"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-4")).thenReturn(payment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(Collections.emptySet());

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-4"));

      assertEquals(500, result.getHttpStatus());
    }
  }

  /**
   * If the final {@code PaymentRemovalUtil.remove(payment)} call itself throws after a
   * successful cleanup, {@code handle} must still catch it and return a 500 error rather than
   * letting the exception propagate (and, notably, without having committed anything —
   * cleanup and the final removal share one transaction).
   */
  @Test
  public void handleRemoveReturns500WhenFinalRemoveThrows() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getFINPaymentDetailList()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-5")).thenReturn(payment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(Collections.emptySet());
      removalUtilMock.when(() -> PaymentRemovalUtil.remove(payment))
          .thenThrow(new RuntimeException("remove failed"));

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-5"));

      assertEquals(500, result.getHttpStatus());
    }
  }

  /**
   * A missing/stale record id (payment not found) must return a 404 immediately — no cleanup,
   * no reactivate, no {@code PaymentRemovalUtil} call of any kind.
   */
  @Test
  public void handleRemoveReturns404WhenPaymentNotFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "missing")).thenReturn(null);

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("missing"));

      assertEquals(404, result.getHttpStatus());
      removalUtilMock.verifyNoInteractions();
    }
  }

  // ── handle — Remove action, ETP-4841 draft-vs-processed branching ──────────
  //
  // Regression suite for the "deleting a DRAFT payment leaves its invoice permanently unpayable"
  // bug. Symptom: delete a Borrador payment from the Pago window's trash icon, then try to
  // register a new payment on the same invoice -> HTTP 400 "No pending payment schedule details
  // found for this installment" (PaymentRegistrationService.MSG_NO_PENDING_PSD, thrown when
  // findPendingPSDs(scheduleId) returns zero rows, i.e. no FIN_PaymentScheduleDetail with
  // paymentDetails IS NULL for that installment).
  //
  // Root cause: removeApplicationDetails deletes every schedule detail outright and never restores
  // a pending fragment. Harmless for a PROCESSED payment, because PaymentRemovalUtil.reactivate()
  // has by then already reversed the invoice's plan through Core — but a DRAFT skips reactivation
  // entirely, so nothing restored the fragment and the installment was left with NO schedule-detail
  // rows at all (confirmed by direct DB inspection of 11 broken invoices, and by an A/B experiment
  // against the invoice-modal delete route, which left exactly 1 correct pending row).
  //
  // The fix is two interdependent conditionals keyed off a `wasProcessed` flag captured BEFORE
  // reactivation; each of the tests below pins one specific way that branching can regress.

  /**
   * ETP-4841 half 1 — the 400 guard: for a payment that is NOT processed (a draft), each
   * document-linked schedule detail must be handed back to Core with a ZERO amount via {@code
   * FIN_AddPayment.updatePaymentDetail(psd, payment, ZERO, false)} BEFORE {@code
   * removeApplicationDetails} deletes it. Core's "editing an existing link" branch then leaves an
   * unlinked ({@code paymentDetails IS NULL}) fragment carrying the released amount — exactly what
   * {@code PaymentRegistrationService.findPendingPSDs} needs to find for any later payment on the
   * same installment to be registrable.
   *
   * <p>The ordering half of that sentence is asserted structurally, not by comment: the stubbed
   * {@code updatePaymentDetail} answer checks that the schedule detail is still attached to its
   * parent collection at the moment it is called. Releasing AFTER the delete would restore nothing.
   */
  @Test
  public void handleRemoveReleasesDocumentLinkedInstallmentsForDraftPayment() throws Exception {
    FIN_Payment draftPayment = mock(FIN_Payment.class);
    when(draftPayment.isProcessed()).thenReturn(false);
    FIN_PaymentScheduleDetail scheduleDetail = mockInvoiceLinkedScheduleDetail();
    List<FIN_PaymentScheduleDetail> scheduleDetailList = new ArrayList<>(List.of(scheduleDetail));
    FIN_PaymentDetail detail = mockDetailWith(scheduleDetailList);
    when(draftPayment.getFINPaymentDetailList())
        .thenReturn(new ArrayList<>(List.of(detail)));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-draft")).thenReturn(draftPayment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(draftPayment))
          .thenReturn(Collections.emptySet());

      // The release must happen while the schedule detail is still attached — i.e. BEFORE
      // removeApplicationDetails runs. Releasing a row that is already deleted restores nothing.
      addPaymentMock.when(() -> FIN_AddPayment.updatePaymentDetail(
              scheduleDetail, draftPayment, BigDecimal.ZERO, false))
          .thenAnswer(invocation -> {
            assertTrue("the installment must be released BEFORE its schedule detail is deleted",
                scheduleDetailList.contains(scheduleDetail));
            return BigDecimal.ZERO;
          });

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-draft"));

      assertEquals(200, result.getHttpStatus());
      verifyReleasedToPending(addPaymentMock, scheduleDetail, draftPayment);
      // The join rows are still deleted afterwards, exactly as before this fix.
      verify(dal).remove(scheduleDetail);
      verify(dal).remove(detail);
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(draftPayment));
    }
  }

  /**
   * ETP-4841 half 2 — the silent-corruption guard, and the more valuable of the two: on the draft
   * path the invoice aggregates must NOT be recomputed.
   *
   * <p>With only half 1 in place, live testing showed the invoice becoming flagged PAID and STILL
   * unpayable: {@code PaymentRemovalUtil.sumDetails()} sums EVERY schedule detail of the installment
   * without checking whether it is linked to a payment detail, so the pending fragment that {@code
   * releaseInstallmentsToPending} had just restored was counted as "paid" — {@code paidAmount} =
   * full, {@code outstandingAmount} = 0, {@code Invoice.paymentComplete = true} (observed live on a
   * 39.93 EUR invoice). A draft never contributed to those aggregates in the first place — verified
   * against real data: installments whose only linked detail belongs to an unprocessed payment
   * report {@code paidAmount} 0 — so the draft path has nothing to recompute.
   *
   * <p>This failure mode is silent (wrong data, no error), which is why it gets its own named test
   * rather than only riding along on the cleanup tests above.
   */
  @Test
  public void handleRemoveSkipsInvoiceAggregateRecomputeForDraftPayment() throws Exception {
    FIN_Payment draftPayment = mock(FIN_Payment.class);
    when(draftPayment.isProcessed()).thenReturn(false);
    FIN_PaymentScheduleDetail scheduleDetail = mockInvoiceLinkedScheduleDetail();
    FIN_PaymentDetail detail = mockDetailWith(new ArrayList<>(List.of(scheduleDetail)));
    when(draftPayment.getFINPaymentDetailList()).thenReturn(new ArrayList<>(List.of(detail)));

    Set<String> affectedInvoiceIds = new HashSet<>(Collections.singletonList("inv-draft"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-draft-2")).thenReturn(draftPayment);
      // Deliberately non-empty: the recompute must be skipped because the payment was a draft, NOT
      // merely because there happened to be no affected invoice to recompute.
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(draftPayment))
          .thenReturn(affectedInvoiceIds);

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-draft-2"));

      assertEquals(200, result.getHttpStatus());
      verifyReleasedToPending(addPaymentMock, scheduleDetail, draftPayment);
      verifyNoInvoiceRecompute(removalUtilMock);
      // Reactivation is also skipped — a draft has nothing to reactivate.
      removalUtilMock.verify(
          () -> PaymentRemovalUtil.reactivate(Mockito.anyString(), Mockito.anyString()), never());
    }
  }

  /**
   * ETP-4841 — the mirror image, so the fix cannot be "corrected" into always taking the draft
   * branch: for a PROCESSED payment the handler must reactivate through Core, must NOT release the
   * installments itself (Core's reversal inside {@code PaymentRemovalUtil.reactivate()} has already
   * done it — doing it a second time would double-release), and MUST recompute the invoice
   * aggregates (a processed payment genuinely did move {@code paidAmount}/{@code
   * outstandingAmount}, so skipping the recompute there would leave the invoice looking paid).
   */
  @Test
  public void handleRemoveRecomputesInvoicesAndSkipsReleaseForProcessedPayment() throws Exception {
    FIN_Payment processedPayment = mock(FIN_Payment.class);
    when(processedPayment.isProcessed()).thenReturn(true);
    when(processedPayment.getId()).thenReturn("pay-processed-applied");
    when(processedPayment.getFINPaymentDetailList()).thenReturn(new ArrayList<>());

    FIN_Payment reactivatedPayment = mock(FIN_Payment.class);
    when(reactivatedPayment.isProcessed()).thenReturn(false);
    FIN_PaymentScheduleDetail scheduleDetail = mockInvoiceLinkedScheduleDetail();
    FIN_PaymentDetail detail = mockDetailWith(new ArrayList<>(List.of(scheduleDetail)));
    when(reactivatedPayment.getFINPaymentDetailList()).thenReturn(new ArrayList<>(List.of(detail)));

    Set<String> affectedInvoiceIds = new HashSet<>(Collections.singletonList("inv-processed"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-processed-applied"))
          .thenReturn(processedPayment, reactivatedPayment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(reactivatedPayment))
          .thenReturn(affectedInvoiceIds);

      NeoResponse result =
          new ReactivatePaymentHandler().handle(removeActionCtx("pay-processed-applied"));

      assertEquals(200, result.getHttpStatus());
      removalUtilMock.verify(() -> PaymentRemovalUtil.reactivate("pay-processed-applied", "R"));
      verifyNothingReleasedToPending(addPaymentMock);
      removalUtilMock.verify(
          () -> PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(affectedInvoiceIds));
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(reactivatedPayment));
    }
  }

  /**
   * ETP-4841 — {@code wasProcessed} must be captured BEFORE {@code PaymentRemovalUtil.reactivate()}
   * runs, and reused thereafter. This is subtle enough to deserve its own test: reactivation clears
   * the payment's {@code Processed} flag, so any later re-read of {@code payment.isProcessed()}
   * (whether on the stale reference or on the re-fetched instance) returns {@code false} and would
   * wrongly route a PROCESSED payment down the draft branch — releasing installments Core has
   * already reversed, and skipping a recompute that is genuinely needed.
   *
   * <p>Simulated by stubbing {@code isProcessed()} to answer {@code true} once and {@code false}
   * afterwards — the state transition a real reactivation performs — and by returning the SAME
   * instance from both {@code OBDal.get(...)} calls so a re-read cannot accidentally hit a
   * still-processed object. The processed behaviour must survive unchanged.
   */
  @Test
  public void handleRemoveCapturesProcessedFlagBeforeReactivation() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    // true on the first read (before reactivation), false on every read after it.
    when(payment.isProcessed()).thenReturn(true, false);
    when(payment.getId()).thenReturn("pay-flag-order");
    FIN_PaymentScheduleDetail scheduleDetail = mockInvoiceLinkedScheduleDetail();
    FIN_PaymentDetail detail = mockDetailWith(new ArrayList<>(List.of(scheduleDetail)));
    when(payment.getFINPaymentDetailList()).thenReturn(new ArrayList<>(List.of(detail)));

    Set<String> affectedInvoiceIds = new HashSet<>(Collections.singletonList("inv-flag-order"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-flag-order")).thenReturn(payment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(affectedInvoiceIds);

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-flag-order"));

      assertEquals(200, result.getHttpStatus());
      removalUtilMock.verify(() -> PaymentRemovalUtil.reactivate("pay-flag-order", "R"));
      // PROCESSED behaviour must hold even though isProcessed() now reports false.
      verifyNothingReleasedToPending(addPaymentMock);
      removalUtilMock.verify(
          () -> PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(affectedInvoiceIds));
    }
  }

  /**
   * ETP-4841 — scope guard: {@code releaseInstallmentsToPending} must only touch DOCUMENT-linked
   * schedule details. A row with neither {@code getInvoicePaymentSchedule()} nor {@code
   * getOrderPaymentSchedule()} (a credit/refund/GL-only fragment) has no installment to release
   * back to, and handing it to {@code FIN_AddPayment.updatePaymentDetail} would mutate an unrelated
   * credit row. It must still be deleted by the normal cleanup.
   */
  @Test
  public void handleRemoveDoesNotReleaseNonDocumentScheduleDetail() throws Exception {
    FIN_Payment draftPayment = mock(FIN_Payment.class);
    when(draftPayment.isProcessed()).thenReturn(false);
    // Neither getInvoicePaymentSchedule() nor getOrderPaymentSchedule() stubbed -> both null.
    FIN_PaymentScheduleDetail creditScheduleDetail = mockScheduleDetail();
    FIN_PaymentDetail detail = mockDetailWith(new ArrayList<>(List.of(creditScheduleDetail)));
    when(draftPayment.getFINPaymentDetailList()).thenReturn(new ArrayList<>(List.of(detail)));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-credit")).thenReturn(draftPayment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(draftPayment))
          .thenReturn(Collections.emptySet());

      NeoResponse result = new ReactivatePaymentHandler().handle(removeActionCtx("pay-credit"));

      assertEquals(200, result.getHttpStatus());
      verifyNothingReleasedToPending(addPaymentMock);
      verify(dal).remove(creditScheduleDetail);
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(draftPayment));
    }
  }

  /**
   * ETP-4841 — ordering guard for a dependency-internal recompute the handler cannot skip:
   * {@code PaymentRemovalUtil.remove(payment)} itself ends with {@code
   * updateInvoicesAfterPaymentRemoval(collectAffectedInvoiceIds(payment))}, unconditionally, inside
   * the payment.removal module. Skipping the handler's own recompute on the draft path is therefore
   * only effective because {@code removeApplicationDetails} has ALREADY detached every {@code
   * FIN_PaymentDetail} by then, so that internal collect yields an empty set and the recompute is a
   * no-op.
   *
   * <p>Reordering the two calls — removing the payment before cleaning up its join rows — would
   * silently reintroduce the false-"paid" corruption through the dependency, with nothing in the
   * handler's own source looking wrong. This test pins the invariant by asserting the detail list is
   * already empty at the moment {@code remove(payment)} is invoked.
   */
  @Test
  public void handleRemoveDetachesAllDetailsBeforeFinalRemoveSoDependencyRecomputeIsNoOp()
      throws Exception {
    FIN_Payment draftPayment = mock(FIN_Payment.class);
    when(draftPayment.isProcessed()).thenReturn(false);
    FIN_PaymentScheduleDetail scheduleDetail = mockInvoiceLinkedScheduleDetail();
    FIN_PaymentDetail detail = mockDetailWith(new ArrayList<>(List.of(scheduleDetail)));
    List<FIN_PaymentDetail> detailList = new ArrayList<>(List.of(detail));
    when(draftPayment.getFINPaymentDetailList()).thenReturn(detailList);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<FIN_AddPayment> addPaymentMock = Mockito.mockStatic(FIN_AddPayment.class);
         MockedStatic<PaymentRemovalUtil> removalUtilMock =
             Mockito.mockStatic(PaymentRemovalUtil.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "pay-order-invariant")).thenReturn(draftPayment);
      removalUtilMock.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(draftPayment))
          .thenReturn(Collections.emptySet());
      removalUtilMock.when(() -> PaymentRemovalUtil.remove(draftPayment)).thenAnswer(invocation -> {
        assertTrue("every FIN_PaymentDetail must already be detached when "
            + "PaymentRemovalUtil.remove() runs its own internal invoice recompute",
            detailList.isEmpty());
        return true;
      });

      NeoResponse result =
          new ReactivatePaymentHandler().handle(removeActionCtx("pay-order-invariant"));

      assertEquals(200, result.getHttpStatus());
      removalUtilMock.verify(() -> PaymentRemovalUtil.remove(draftPayment));
    }
  }

  // ── afterHandle — guard conditions ────────────────────────────────────────

  /**
   * A list response now gets the one enrichment the grid needs — {@code pisLocked}, which decides
   * whether its rows may offer Reactivate and Delete (ETP-4895). It used to be left untouched, but
   * enforcing that rule only on the detail form left a way around it from the grid.
   *
   * <p>The single-record enrichments stay single-record: each costs its own query and the grid does
   * not show them.
   */
  @Test
  public void afterHandleAddsOnlyTheLockFlagToListResponses() throws JSONException {
    JSONObject body = singleRecordBody("pay-1");
    NeoContext ctx = getCtx(null, "GET");
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new ReactivatePaymentHandler().afterHandle(ctx);

    assertNotNull(result);
    JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data")
        .getJSONObject(0);
    assertTrue(row.has("pisLocked"));
    assertFalse(row.has("financialTransactionId"));
    assertFalse(row.has("pisPaymentId"));
  }

  /**
   * An empty list is left untouched: there is nothing to flag and no reason to rebuild the body.
   */
  @Test
  public void afterHandleReturnsNullForAnEmptyListResponse() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtx(null, "GET");
    ctx.setPreviousResult(NeoResponse.ok(body));

    assertNull(new ReactivatePaymentHandler().afterHandle(ctx));
  }

  /**
   * Non-GET methods (e.g. the Reactivate/Confirm ACTION requests handled by the pre-hook)
   * must not be touched by the post-hook.
   */
  @Test
  public void afterHandleReturnsNullForNonGetMethod() throws JSONException {
    JSONObject body = singleRecordBody("pay-1");
    NeoContext ctx = getCtx("pay-1", "PATCH");
    ctx.setPreviousResult(NeoResponse.ok(body));

    assertNull(new ReactivatePaymentHandler().afterHandle(ctx));
  }

  /**
   * When there is no previous result (e.g. the default CRUD handler failed upstream), the
   * post-hook must not attempt to inject anything.
   */
  @Test
  public void afterHandleReturnsNullWhenPreviousResultMissing() {
    NeoContext ctx = getCtx("pay-1", "GET");
    assertNull(new ReactivatePaymentHandler().afterHandle(ctx));
  }

  /**
   * An empty data array (record not found) must be left untouched rather than throwing.
   */
  @Test
  public void afterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = ctxWithPreviousResult("pay-1", body);

    assertNull(new ReactivatePaymentHandler().afterHandle(ctx));
  }

  // ── afterHandle — single record ───────────────────────────────────────────

  /**
   * Reconciled payment (has a linked {@code FIN_Finacc_Transaction}): the transaction id is
   * injected into the record.
   */
  @Test
  public void afterHandleInjectsTransactionIdWhenReconciled() throws Exception {
    ReactivatePaymentHandler handler = spy(new ReactivatePaymentHandler());
    Mockito.doReturn("trx-1").when(handler).resolveFinancialTransactionId("pay-1");

    JSONObject body = singleRecordBody("pay-1");
    NeoContext ctx = ctxWithPreviousResult("pay-1", body);

    NeoResponse result = handler.afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
    String injected = result.getBody()
        .getJSONObject("response").getJSONArray("data")
        .getJSONObject(0).getString("financialTransactionId");
    assertEquals("trx-1", injected);
  }

  /**
   * Not-yet-reconciled payment (e.g. status is not {@code RPPC}): no linked transaction, so
   * the field is present but JSON {@code null} — never absent, never an error.
   */
  @Test
  public void afterHandleInjectsJsonNullWhenNotReconciled() throws Exception {
    ReactivatePaymentHandler handler = spy(new ReactivatePaymentHandler());
    Mockito.doReturn(null).when(handler).resolveFinancialTransactionId("pay-2");

    JSONObject body = singleRecordBody("pay-2");
    NeoContext ctx = ctxWithPreviousResult("pay-2", body);

    NeoResponse result = handler.afterHandle(ctx);

    assertNotNull(result);
    JSONObject paymentRecord = result.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertTrue(paymentRecord.has("financialTransactionId"));
    assertEquals(JSONObject.NULL, paymentRecord.get("financialTransactionId"));
  }

  /**
   * If the transaction lookup throws (unexpected DB error), afterHandle must not propagate
   * the exception — it logs and returns {@code null} so the original payment response is
   * still served to the UI.
   */
  @Test
  public void afterHandleReturnsNullWhenResolutionThrows() throws Exception {
    ReactivatePaymentHandler handler = spy(new ReactivatePaymentHandler());
    Mockito.doThrow(new RuntimeException("boom")).when(handler).resolveFinancialTransactionId("pay-3");

    JSONObject body = singleRecordBody("pay-3");
    NeoContext ctx = ctxWithPreviousResult("pay-3", body);

    assertNull(handler.afterHandle(ctx));
  }

  // ── resolveFinancialTransactionId — OBDal query behavior ──────────────────

  /**
   * No FIN_Payment found for the given id (stale/invalid id) — resolves to {@code null}
   * without querying FIN_Finacc_Transaction.
   */
  @Test
  public void resolveFinancialTransactionIdReturnsNullWhenPaymentNotFound() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "missing")).thenReturn(null);

      String result = new ReactivatePaymentHandler().resolveFinancialTransactionId("missing");

      assertNull(result);
    }
  }

  /**
   * FIN_Payment exists but no FIN_Finacc_Transaction references it yet (not reconciled) —
   * resolves to {@code null}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void resolveFinancialTransactionIdReturnsNullWhenNoTransactionLinked() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(dal.get(FIN_Payment.class, "pay-1")).thenReturn(payment);

      OBCriteria<FIN_FinaccTransaction> crit = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinaccTransaction.class)).thenReturn(crit);
      when(crit.list()).thenReturn(Collections.emptyList());

      String result = new ReactivatePaymentHandler().resolveFinancialTransactionId("pay-1");

      assertNull(result);
    }
  }

  /**
   * FIN_Payment exists and is reconciled — resolves to the linked transaction id.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void resolveFinancialTransactionIdReturnsIdWhenLinked() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(dal.get(FIN_Payment.class, "pay-1")).thenReturn(payment);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      when(trx.getId()).thenReturn("trx-1");
      OBCriteria<FIN_FinaccTransaction> crit = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinaccTransaction.class)).thenReturn(crit);
      List<FIN_FinaccTransaction> results = Collections.singletonList(trx);
      when(crit.list()).thenReturn(results);

      String result = new ReactivatePaymentHandler().resolveFinancialTransactionId("pay-1");

      assertEquals("trx-1", result);
    }
  }

  /** Routes the transfer poll, so a retry started here can be followed to its outcome. */
  @Test
  public void routesPisPaymentStatus() {
    // Without this route the payment window had no way to ask Salt Edge how the new attempt ended:
    // the action was only reachable through an invoice header, the webhook cannot reach a server
    // that is not publicly addressable, and PSD2's periodic refresh is not scheduled by default —
    // so a retry sat at 'requested' and the payment read as in progress indefinitely (ETP-4895).
    // Reaching the service means the route matched; the missing body is what it rejects on.
    NeoResponse result = new ReactivatePaymentHandler().handle(actionCtx("pisPaymentStatus", "pay-1"));

    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /** An action this handler does not own is left to the default CRUD path. */
  @Test
  public void ignoresUnknownActions() {
    assertNull(new ReactivatePaymentHandler().handle(actionCtx("somethingElse", "pay-1")));
  }

  /**
   * Core's reactivation restores the invoice's paid amounts only when the payment's status matches
   * the one its payment method implies. ETGOERR is not in that sequence
   * ({@code aprm_seqnumberpaymentstatus} answers 70 for anything unknown, 40 for PPM), so the
   * comparison never held and the payment came back to draft with its invoice still fully paid.
   */
  @Test
  public void clearsTheTransferErrorFlagBeforeCoreReactivates() {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getStatus()).thenReturn("ETGOERR");

    try (MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctx = Mockito.mockStatic(OBContext.class);
        MockedStatic<FIN_Utility> util = Mockito.mockStatic(FIN_Utility.class)) {
      OBDal instance = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(instance);
      when(instance.get(FIN_Payment.class, "pay-err")).thenReturn(payment);
      // The value Core is about to compare against — not a literal, so an account with automatic
      // withdrawal on (PWNC, not PPM) is restored correctly too.
      util.when(() -> FIN_Utility.invoicePaymentStatus(payment)).thenReturn("PPM");

      new ReactivatePaymentHandler().handle(actionCtx("etprReactivatePayment", "pay-err"));

      verify(payment).setStatus("PPM");
    }
  }

  /** A payment that was never flagged keeps whatever status it has. */
  @Test
  public void leavesAnUnflaggedPaymentAlone() {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getStatus()).thenReturn("PWNC");

    try (MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctx = Mockito.mockStatic(OBContext.class)) {
      OBDal instance = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(instance);
      when(instance.get(FIN_Payment.class, "pay-ok")).thenReturn(payment);

      new ReactivatePaymentHandler().handle(actionCtx("etprReactivatePayment", "pay-ok"));

      verify(payment, never()).setStatus(anyString());
    }
  }

  // ── ETP-5558 (a1a863f83): the handler re-checks the payment belongs to the tenant ──

  /**
   * Runs {@code fieldName} on another tenant's ETGOERR-flagged payment, called on the handler
   * directly — as if the action path's guard had been bypassed. The caller keeps the
   * {@link NeoButtonActionHelper} and {@link PaymentRemovalUtil} static mocks open around it.
   */
  private static NeoResponse runOnForeignPayment(String fieldName, FIN_Payment[] paymentOut,
      OBDal[] dalOut) {
    Client other = mock(Client.class);
    when(other.getId()).thenReturn("client-other");
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getClient()).thenReturn(other);
    when(payment.getStatus()).thenReturn("ETGOERR");
    paymentOut[0] = payment;

    try (MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctx = Mockito.mockStatic(OBContext.class);
        MockedStatic<FIN_Utility> util = Mockito.mockStatic(FIN_Utility.class)) {
      OBDal instance = mock(OBDal.class);
      dalOut[0] = instance;
      dal.when(OBDal::getInstance).thenReturn(instance);
      OBContext session = mock(OBContext.class);
      when(session.getReadableClients()).thenReturn(new String[] { "client-own" });
      when(session.getReadableOrganizations()).thenReturn(new String[] { "org-own" });
      ctx.when(OBContext::getOBContext).thenReturn(session);
      when(instance.get(FIN_Payment.class, "pay-foreign")).thenReturn(payment);
      util.when(() -> FIN_Utility.invoicePaymentStatus(payment)).thenReturn("PPM");
      return new ReactivatePaymentHandler().handle(actionCtx(fieldName, "pay-foreign"));
    }
  }

  private static void assertForeignPaymentRefused(String fieldName) {
    FIN_Payment[] payment = new FIN_Payment[1];
    OBDal[] dal = new OBDal[1];
    try (MockedStatic<NeoButtonActionHelper> buttons =
             Mockito.mockStatic(NeoButtonActionHelper.class);
        MockedStatic<PaymentRemovalUtil> removal = Mockito.mockStatic(PaymentRemovalUtil.class)) {
      NeoResponse result = runOnForeignPayment(fieldName, payment, dal);

      assertEquals(fieldName, 404, result.getHttpStatus());
      try {
        assertEquals("Payment not found: pay-foreign",
            result.getBody().getJSONObject("error").getString("message"));
      } catch (JSONException e) {
        throw new AssertionError(e);
      }
      buttons.verify(() -> NeoButtonActionHelper.executeButtonActionCore(Mockito.any(),
          anyString(), anyString(), Mockito.any()), never());
      removal.verifyNoInteractions();
      verify(payment[0], never()).setStatus(anyString());
      verify(dal[0], never()).save(Mockito.any());
    }
  }

  @Test
  public void reactivateRefusesAnotherTenantsPayment() {
    // Also covers clearTransferErrorFlag: the ETGOERR flag of a foreign payment is never cleared.
    assertForeignPaymentRefused("etprReactivatePayment");
  }

  @Test
  public void confirmRefusesAnotherTenantsPayment() {
    assertForeignPaymentRefused("aPRMProcessPayment");
  }

  @Test
  public void removeRefusesAnotherTenantsPayment() {
    assertForeignPaymentRefused("eTPRRemovePayment");
  }

  // ── ETP-5558: an agent's Eliminar gets the UI's gate (RPVOID, pisLocked) ──

  private static NeoContext mcpRemoveCtx(String recordId) {
    return NeoContext.builder()
        .specName("payment-in")
        .entityName("finPayment")
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("eTPRRemovePayment")
        .recordId(recordId)
        .mcpOrigin(true)
        .build();
  }

  /** A processed payment of the tenant, with no details, in {@code status}. */
  private static FIN_Payment processedPayment(String id, String status) {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn(id);
    when(payment.getStatus()).thenReturn(status);
    when(payment.isProcessed()).thenReturn(true);
    when(payment.getFINPaymentDetailList()).thenReturn(new ArrayList<>());
    return payment;
  }

  /**
   * Runs Eliminar on {@code payment} with {@code withTransfer} as the payments that have a bank
   * transfer; the predicate itself ({@code isLifecycleLockedByTransfer}) is the real one.
   */
  private static NeoResponse runRemove(NeoContext ctx, FIN_Payment payment,
      Set<String> withTransfer, MockedStatic<PaymentRemovalUtil> removal,
      MockedStatic<PisDeferredPaymentService> pis) {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, payment.getId())).thenReturn(payment);
      pis.when(() -> PisDeferredPaymentService.paymentsWithBankTransfer(Mockito.anyCollection()))
          .thenReturn(withTransfer);
      removal.when(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment))
          .thenReturn(Collections.emptySet());
      return new ReactivatePaymentHandler().handle(ctx);
    }
  }

  private static String errorMessage(NeoResponse response) throws JSONException {
    return response.getBody().getJSONObject("error").getString("message");
  }

  @Test
  public void mcpRemoveRefusesVoidPayment() throws Exception {
    FIN_Payment payment = processedPayment("pay-void", "RPVOID");
    try (MockedStatic<PaymentRemovalUtil> removal = Mockito.mockStatic(PaymentRemovalUtil.class);
        MockedStatic<PisDeferredPaymentService> pis = Mockito.mockStatic(
            PisDeferredPaymentService.class, Mockito.CALLS_REAL_METHODS)) {
      NeoResponse result = runRemove(mcpRemoveCtx("pay-void"), payment, Set.of(), removal, pis);

      assertEquals(422, result.getHttpStatus());
      assertTrue(errorMessage(result).contains("RPVOID"));
      removal.verifyNoInteractions();
    }
  }

  @Test
  public void mcpRemoveRefusesPaymentLockedByItsBankTransfer() throws Exception {
    FIN_Payment payment = processedPayment("pay-pis", "RPPC");
    try (MockedStatic<PaymentRemovalUtil> removal = Mockito.mockStatic(PaymentRemovalUtil.class);
        MockedStatic<PisDeferredPaymentService> pis = Mockito.mockStatic(
            PisDeferredPaymentService.class, Mockito.CALLS_REAL_METHODS)) {
      NeoResponse result = runRemove(mcpRemoveCtx("pay-pis"), payment, Set.of("pay-pis"),
          removal, pis);

      assertEquals(422, result.getHttpStatus());
      assertTrue(errorMessage(result).contains("pisLocked"));
      removal.verifyNoInteractions();
      pis.verify(() -> PisDeferredPaymentService.paymentsWithBankTransfer(Set.of("pay-pis")));
    }
  }

  /** The transfer the bank refused (ETGOERR) is not a lock, exactly as {@code pisLocked} says. */
  @Test
  public void mcpRemoveProceedsOnPaymentWhoseTransferWasRejected() throws Exception {
    FIN_Payment payment = processedPayment("pay-err", "ETGOERR");
    try (MockedStatic<PaymentRemovalUtil> removal = Mockito.mockStatic(PaymentRemovalUtil.class);
        MockedStatic<PisDeferredPaymentService> pis = Mockito.mockStatic(
            PisDeferredPaymentService.class, Mockito.CALLS_REAL_METHODS)) {
      NeoResponse result = runRemove(mcpRemoveCtx("pay-err"), payment, Set.of("pay-err"),
          removal, pis);

      assertEquals(200, result.getHttpStatus());
      removal.verify(() -> PaymentRemovalUtil.remove(payment));
    }
  }

  /** A processed payment of the tenant is reactivated and removed, as the UI's trash icon does. */
  @Test
  public void mcpRemoveReactivatesAndRemovesProcessedPayment() throws Exception {
    FIN_Payment payment = processedPayment("pay-ok", "RPPC");
    try (MockedStatic<PaymentRemovalUtil> removal = Mockito.mockStatic(PaymentRemovalUtil.class);
        MockedStatic<PisDeferredPaymentService> pis = Mockito.mockStatic(
            PisDeferredPaymentService.class, Mockito.CALLS_REAL_METHODS)) {
      NeoResponse result = runRemove(mcpRemoveCtx("pay-ok"), payment, Set.of(), removal, pis);

      assertEquals(200, result.getHttpStatus());
      removal.verify(() -> PaymentRemovalUtil.reactivate("pay-ok", "R"));
      removal.verify(() -> PaymentRemovalUtil.remove(payment));
    }
  }

  /** REST keeps its behaviour: the SPA withholds the button, the handler does not gate it. */
  @Test
  public void restRemoveIsNotGatedOnVoidOrLockedPayment() throws Exception {
    FIN_Payment payment = processedPayment("pay-void", "RPVOID");
    try (MockedStatic<PaymentRemovalUtil> removal = Mockito.mockStatic(PaymentRemovalUtil.class);
        MockedStatic<PisDeferredPaymentService> pis = Mockito.mockStatic(
            PisDeferredPaymentService.class, Mockito.CALLS_REAL_METHODS)) {
      NeoResponse result = runRemove(removeActionCtx("pay-void"), payment, Set.of("pay-void"),
          removal, pis);

      assertEquals(200, result.getHttpStatus());
      removal.verify(() -> PaymentRemovalUtil.remove(payment));
      pis.verify(() -> PisDeferredPaymentService.paymentsWithBankTransfer(
          Mockito.anyCollection()), never());
    }
  }

  // ── ETP-5558: an action named by its DB column reaches the same branch ──
  //
  // etendo_schema publishes each button as {name: <property>, action: <DB column>}, and the button
  // lookup accepts either spelling. Matched on the property name alone, the column spelling skipped
  // this handler: Reactivate ran without action=RE and Eliminar without the agent's gate.

  private static final String ENTITY_ID = "entity-payment";

  /** An ACTION context carrying the SF entity, as the action path builds it. */
  private static NeoContext buttonCtx(String fieldName, String recordId, boolean mcpOrigin) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn(ENTITY_ID);
    return NeoContext.builder()
        .specName("payment-in")
        .entityName("finPayment")
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(fieldName)
        .recordId(recordId)
        .sfEntity(entity)
        .mcpOrigin(mcpOrigin)
        .build();
  }

  /** Makes {@code columnName} resolve to the button whose DAL property is {@code propertyName}. */
  private static void stubButton(MockedStatic<NeoButtonActionHelper> buttons,
      MockedStatic<ModelProvider> models, String columnName, String propertyName) {
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("FIN_Payment");
    Column column = mock(Column.class);
    when(column.getTable()).thenReturn(table);
    when(column.getDBColumnName()).thenReturn(columnName);
    buttons.when(() -> NeoButtonActionHelper.findButtonColumn(ENTITY_ID, columnName))
        .thenReturn(column);

    Property property = mock(Property.class);
    when(property.getName()).thenReturn(propertyName);
    Entity dal = mock(Entity.class);
    when(dal.getPropertyByColumnName(columnName)).thenReturn(property);
    ModelProvider provider = mock(ModelProvider.class);
    when(provider.getEntityByTableName("FIN_Payment")).thenReturn(dal);
    models.when(ModelProvider::getInstance).thenReturn(provider);
  }

  /**
   * Runs {@code columnName} on an own payment and returns the parameters the handler passed on to
   * the button, which must still be called by the name the caller used.
   */
  private static JSONObject paramsSentForColumn(String columnName, String propertyName) {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getStatus()).thenReturn("RPAP");
    try (MockedStatic<NeoButtonActionHelper> buttons =
             Mockito.mockStatic(NeoButtonActionHelper.class);
        MockedStatic<ModelProvider> models = Mockito.mockStatic(ModelProvider.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctx = Mockito.mockStatic(OBContext.class)) {
      stubButton(buttons, models, columnName, propertyName);
      OBDal instance = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(instance);
      when(instance.get(FIN_Payment.class, "pay-1")).thenReturn(payment);
      buttons.when(() -> NeoButtonActionHelper.executeButtonActionCore(Mockito.any(),
          anyString(), anyString(), Mockito.any())).thenReturn(NeoResponse.ok(new JSONObject()));

      NeoResponse result = new ReactivatePaymentHandler()
          .handle(buttonCtx(columnName, "pay-1", true));

      assertNotNull(result);
      ArgumentCaptor<JSONObject> params = ArgumentCaptor.forClass(JSONObject.class);
      buttons.verify(() -> NeoButtonActionHelper.executeButtonActionCore(Mockito.any(),
          Mockito.eq("pay-1"), Mockito.eq(columnName), params.capture()));
      return params.getValue();
    }
  }

  @Test
  public void reactivateByColumnNameInjectsActionRe() throws JSONException {
    JSONObject params = paramsSentForColumn("EM_Etpr_Reactivate_Payment", "etprReactivatePayment");

    assertEquals("RE", params.getString("action"));
  }

  @Test
  public void confirmByColumnNameInjectsActionP() throws JSONException {
    JSONObject params = paramsSentForColumn("EM_APRM_Process_Payment", "aPRMProcessPayment");

    assertEquals("P", params.getString("action"));
    assertEquals("pay-1", params.getString("Fin_Payment_ID"));
  }

  /** The lowercase column spelling no longer bypasses the agent's refusal on a void payment. */
  @Test
  public void mcpRemoveByColumnNameRefusesVoidPayment() throws Exception {
    FIN_Payment payment = processedPayment("pay-void", "RPVOID");
    try (MockedStatic<NeoButtonActionHelper> buttons =
             Mockito.mockStatic(NeoButtonActionHelper.class);
        MockedStatic<ModelProvider> models = Mockito.mockStatic(ModelProvider.class);
        MockedStatic<PaymentRemovalUtil> removal = Mockito.mockStatic(PaymentRemovalUtil.class);
        MockedStatic<PisDeferredPaymentService> pis = Mockito.mockStatic(
            PisDeferredPaymentService.class, Mockito.CALLS_REAL_METHODS)) {
      stubButton(buttons, models, "em_etpr_remove_payment", "eTPRRemovePayment");

      NeoResponse result = runRemove(buttonCtx("em_etpr_remove_payment", "pay-void", true),
          payment, Set.of(), removal, pis);

      assertEquals(422, result.getHttpStatus());
      assertTrue(errorMessage(result).contains("RPVOID"));
      removal.verifyNoInteractions();
      buttons.verify(() -> NeoButtonActionHelper.executeButtonActionCore(Mockito.any(),
          anyString(), anyString(), Mockito.any()), never());
    }
  }

  /** A name that is no button of the entity leaves the request to the default path, as before. */
  @Test
  public void unknownActionWithEntityIsLeftToDefaultPath() {
    try (MockedStatic<NeoButtonActionHelper> buttons =
             Mockito.mockStatic(NeoButtonActionHelper.class)) {
      buttons.when(() -> NeoButtonActionHelper.findButtonColumn(ENTITY_ID, "somethingElse"))
          .thenReturn(null);

      assertNull(new ReactivatePaymentHandler()
          .handle(buttonCtx("somethingElse", "pay-1", true)));
    }
  }

  /** A button this handler does not own, named by its column, is left to the default path too. */
  @Test
  public void otherButtonByColumnNameIsLeftToDefaultPath() {
    try (MockedStatic<NeoButtonActionHelper> buttons =
             Mockito.mockStatic(NeoButtonActionHelper.class);
        MockedStatic<ModelProvider> models = Mockito.mockStatic(ModelProvider.class)) {
      stubButton(buttons, models, "Posted", "posted");

      assertNull(new ReactivatePaymentHandler().handle(buttonCtx("Posted", "pay-1", true)));
      buttons.verify(() -> NeoButtonActionHelper.executeButtonActionCore(Mockito.any(),
          anyString(), anyString(), Mockito.any()), never());
    }
  }
  // ── ETP-5547: Reactivate of a reconciled (RPPC) payment ──────────────────────────────────

  private static final String REACTIVATE_ACTION = "etprReactivatePayment";
  private static final String NORMALIZE_METHOD = "normalizeReactivatedMatchGroup";
  private static final String ETP5547_PAYMENT = "pay-5547";
  private static final String ETP5547_LINE = "bsl-5547";
  private static final String STATUS_RECONCILED = "RPPC";
  private static final String STATUS_WITHDRAWN_NOT_CLEARED = "PWNC";

  /**
   * A stateful payment / transaction / bank-statement-line triple. The delegated reactivation
   * ({@code NeoButtonActionHelper.executeButtonActionCore}, statically mocked) flips
   * {@link #reactivated} when it succeeds, and the mocks answer from that flag the way the
   * database would afterwards: the payment is no longer processed, its transaction no longer
   * belongs to a reconciliation, and the statement line lost its transaction pointer.
   */
  private static final class ReactivateScenario {
    final FIN_Payment payment = mock(FIN_Payment.class);
    final FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    final FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    final OBDal dal = mock(OBDal.class);
    final Connection conn = mock(Connection.class);
    final Savepoint savepoint = mock(Savepoint.class);
    final org.hibernate.Session session = mock(org.hibernate.Session.class);
    final PreparedStatement statusPs = mock(PreparedStatement.class);
    final OBContext obContext = mock(OBContext.class);
    final org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
    boolean reactivated;
    String status;
    String statusAtDelegation;
  }

  @SuppressWarnings("unchecked")
  private static ReactivateScenario wireScenario(MockedStatic<OBDal> obDalMock,
      MockedStatic<OBContext> ctxMock, MockedStatic<NeoButtonActionHelper> actionMock,
      String initialStatus, boolean reconciled, boolean reactivationSucceeds) throws Exception {
    ReactivateScenario sc = new ReactivateScenario();
    sc.status = initialStatus;
    obDalMock.when(OBDal::getInstance).thenReturn(sc.dal);
    // ReconciledPaymentReactivation.writeStatus: raw JDBC UPDATE of fin_payment.status, stamped
    // with the context user, then mirrored onto the entity (setStatus below keeps sc.status).
    ctxMock.when(OBContext::getOBContext).thenReturn(sc.obContext);
    when(sc.obContext.getUser()).thenReturn(sc.user);
    when(sc.user.getId()).thenReturn("user-5547");
    when(sc.conn.prepareStatement(anyString())).thenReturn(sc.statusPs);
    when(sc.dal.getConnection()).thenReturn(sc.conn);
    when(sc.dal.getSession()).thenReturn(sc.session);
    when(sc.conn.setSavepoint()).thenReturn(sc.savepoint);

    when(sc.payment.getId()).thenReturn(ETP5547_PAYMENT);
    // ETP-5558: the handler re-checks the payment is the tenant's before reactivating it.
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-5547");
    when(sc.payment.getClient()).thenReturn(client);
    when(sc.obContext.getReadableClients()).thenReturn(new String[] { "client-5547" });
    when(sc.payment.getStatus()).thenAnswer(inv -> sc.status);
    Mockito.doAnswer(inv -> {
      sc.status = inv.getArgument(0);
      return null;
    }).when(sc.payment).setStatus(anyString());
    when(sc.payment.isProcessed()).thenAnswer(inv -> !sc.reactivated);
    when(sc.dal.get(FIN_Payment.class, ETP5547_PAYMENT)).thenReturn(sc.payment);

    when(sc.trx.getFinPayment()).thenReturn(sc.payment);
    when(sc.trx.getReconciliation()).thenAnswer(
        inv -> reconciled && !sc.reactivated ? mock(FIN_Reconciliation.class) : null);
    List<FIN_BankStatementLine> lines = reconciled
        ? Collections.singletonList(sc.line)
        : Collections.<FIN_BankStatementLine>emptyList();
    when(sc.trx.getFINBankStatementLineList()).thenReturn(lines);
    when(sc.payment.getFINFinaccTransactionList()).thenReturn(Collections.singletonList(sc.trx));

    // Criteria restrictions are not evaluated by a mock: answer as the DB would for
    // "this payment's transaction that belongs to a reconciliation".
    OBCriteria<FIN_FinaccTransaction> trxCrit = mock(OBCriteria.class, Mockito.RETURNS_SELF);
    when(trxCrit.uniqueResult()).thenAnswer(
        inv -> reconciled && !sc.reactivated ? sc.trx : null);
    when(trxCrit.list()).thenAnswer(inv -> reconciled && !sc.reactivated
        ? Collections.singletonList(sc.trx)
        : Collections.emptyList());
    when(sc.dal.createCriteria(FIN_FinaccTransaction.class)).thenReturn(trxCrit);

    when(sc.line.getId()).thenReturn(ETP5547_LINE);
    when(sc.line.getFinancialAccountTransaction()).thenAnswer(
        inv -> reconciled && !sc.reactivated ? sc.trx : null);
    when(sc.dal.get(FIN_BankStatementLine.class, ETP5547_LINE)).thenReturn(sc.line);

    actionMock.when(() -> NeoButtonActionHelper.executeButtonActionCore(
        any(), anyString(), anyString(), any())).thenAnswer(inv -> {
          sc.statusAtDelegation = sc.status;
          if (!reactivationSucceeds) {
            return NeoResponse.error(500, "Process failed");
          }
          sc.reactivated = true;
          return NeoResponse.ok(new JSONObject());
        });
    return sc;
  }

  /** Did any constructed ReconciliationHandler get asked to normalize {@code line}? */
  private static boolean normalized(MockedConstruction<ReconciliationHandler> handlers,
      FIN_BankStatementLine line) {
    return handlers.constructed().stream()
        .flatMap(h -> Mockito.mockingDetails(h).getInvocations().stream())
        .anyMatch(inv -> NORMALIZE_METHOD.equals(inv.getMethod().getName())
            && inv.getArguments().length == 1 && inv.getArguments()[0] == line);
  }

  /** Did any constructed ReconciliationHandler get asked to normalize anything at all? */
  private static boolean normalizedAnything(MockedConstruction<ReconciliationHandler> handlers) {
    return handlers.constructed().stream()
        .flatMap(h -> Mockito.mockingDetails(h).getInvocations().stream())
        .anyMatch(inv -> NORMALIZE_METHOD.equals(inv.getMethod().getName()));
  }

  /**
   * Classic's un-match ({@code APRM_MatchingUtility.unmatch}) leaves the bank statement line
   * clean and re-joins lines Core split for a 1:N match. GO's Reactivate of a reconciled payment
   * left the line fragmented and still flagged as matched. After a successful reactivate the line
   * loses its matching leftovers and goes through
   * {@code ReconciliationHandler.normalizeReactivatedMatchGroup}.
   */
  @Test
  public void testReactivateOfReconciledPaymentNormalizesItsBankStatementLine() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<FIN_Utility> utilMock = Mockito.mockStatic(FIN_Utility.class);
         MockedStatic<NeoButtonActionHelper> actionMock = Mockito.mockStatic(NeoButtonActionHelper.class);
         MockedConstruction<ReconciliationHandler> reconHandlers =
             Mockito.mockConstruction(ReconciliationHandler.class)) {
      ReactivateScenario sc = wireScenario(obDalMock, ctxMock, actionMock, STATUS_RECONCILED, true, true);
      utilMock.when(() -> FIN_Utility.invoicePaymentStatus(sc.payment))
          .thenReturn(STATUS_WITHDRAWN_NOT_CLEARED);

      NeoResponse result =
          new ReactivatePaymentHandler().handle(actionCtx(REACTIVATE_ACTION, ETP5547_PAYMENT));

      assertNotNull(result);
      assertTrue("a successful reactivate must stay successful", result.getHttpStatus() < 300);
      assertTrue("the matched bank statement line must be normalized after the reactivate",
          normalized(reconHandlers, sc.line));
      verify(sc.line).setMatchingtype(null);
      verify(sc.line).setMatchedDocument(null);
    }
  }

  /**
   * Core restores the invoice's paid amounts only when the payment's status matches its method's
   * paid level. For an RDNC/PWNC method an RPPC payment never matched, so it returned to draft
   * while its invoice still read as paid. Like Core's unmatch, the payment must be moved to that
   * level BEFORE the reactivation runs.
   */
  @Test
  public void testReactivateOfReconciledPaymentAlignsStatusBeforeDelegating() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<FIN_Utility> utilMock = Mockito.mockStatic(FIN_Utility.class);
         MockedStatic<NeoButtonActionHelper> actionMock = Mockito.mockStatic(NeoButtonActionHelper.class);
         MockedConstruction<ReconciliationHandler> reconHandlers =
             Mockito.mockConstruction(ReconciliationHandler.class)) {
      ReactivateScenario sc = wireScenario(obDalMock, ctxMock, actionMock, STATUS_RECONCILED, true, true);
      utilMock.when(() -> FIN_Utility.invoicePaymentStatus(sc.payment))
          .thenReturn(STATUS_WITHDRAWN_NOT_CLEARED);

      new ReactivatePaymentHandler().handle(actionCtx(REACTIVATE_ACTION, ETP5547_PAYMENT));

      assertEquals(STATUS_WITHDRAWN_NOT_CLEARED, sc.statusAtDelegation);
    }
  }

  /** A payment that was never reconciled has no bank statement line to clean up. */
  @Test
  public void testReactivateOfNonReconciledPaymentDoesNotNormalizeAnyLine() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<FIN_Utility> utilMock = Mockito.mockStatic(FIN_Utility.class);
         MockedStatic<NeoButtonActionHelper> actionMock = Mockito.mockStatic(NeoButtonActionHelper.class);
         MockedConstruction<ReconciliationHandler> reconHandlers =
             Mockito.mockConstruction(ReconciliationHandler.class)) {
      ReactivateScenario sc =
          wireScenario(obDalMock, ctxMock, actionMock, STATUS_WITHDRAWN_NOT_CLEARED, false, true);

      new ReactivatePaymentHandler().handle(actionCtx(REACTIVATE_ACTION, ETP5547_PAYMENT));

      assertFalse(normalizedAnything(reconHandlers));
      verify(sc.payment, never()).setStatus(anyString());
      verify(sc.line, never()).setMatchingtype(Mockito.<String>any());
    }
  }

  /**
   * A reactivate that answers an error (no exception) must leave a reconciled payment as it was:
   * the line untouched and the status back to RPPC, not stuck at the "not cleared" level it was
   * moved to beforehand. This goes through {@code finish}, which sees the payment still processed.
   */
  @Test
  public void testFailedReactivateOfReconciledPaymentLeavesItReconciled() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<FIN_Utility> utilMock = Mockito.mockStatic(FIN_Utility.class);
         MockedStatic<NeoButtonActionHelper> actionMock = Mockito.mockStatic(NeoButtonActionHelper.class);
         MockedConstruction<ReconciliationHandler> reconHandlers =
             Mockito.mockConstruction(ReconciliationHandler.class)) {
      ReactivateScenario sc = wireScenario(obDalMock, ctxMock, actionMock, STATUS_RECONCILED, true, false);
      utilMock.when(() -> FIN_Utility.invoicePaymentStatus(sc.payment))
          .thenReturn(STATUS_WITHDRAWN_NOT_CLEARED);

      NeoResponse result =
          new ReactivatePaymentHandler().handle(actionCtx(REACTIVATE_ACTION, ETP5547_PAYMENT));

      assertNotNull(result);
      assertTrue(result.getHttpStatus() >= 400);
      assertFalse(normalizedAnything(reconHandlers));
      assertEquals(STATUS_RECONCILED, sc.status);
    }
  }

  /**
   * The delegated reactivate THROWS: the handler answers 500 and goes through
   * {@code finishAfterFailure} — the aborted transaction is rolled back first, then the status
   * prepare applied is written back to RPPC; the statement line is never touched.
   */
  @Test
  public void testThrowingReactivateOfReconciledPaymentRollsBackAndLeavesItReconciled()
      throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<FIN_Utility> utilMock = Mockito.mockStatic(FIN_Utility.class);
         MockedStatic<NeoButtonActionHelper> actionMock = Mockito.mockStatic(NeoButtonActionHelper.class);
         MockedConstruction<ReconciliationHandler> reconHandlers =
             Mockito.mockConstruction(ReconciliationHandler.class)) {
      ReactivateScenario sc = wireScenario(obDalMock, ctxMock, actionMock, STATUS_RECONCILED, true, false);
      utilMock.when(() -> FIN_Utility.invoicePaymentStatus(sc.payment))
          .thenReturn(STATUS_WITHDRAWN_NOT_CLEARED);
      actionMock.when(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(), anyString(), anyString(), any()))
          .thenThrow(new IllegalStateException("process blew up"));

      NeoResponse result =
          new ReactivatePaymentHandler().handle(actionCtx(REACTIVATE_ACTION, ETP5547_PAYMENT));

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      verify(sc.dal).rollbackAndClose();
      assertEquals(STATUS_RECONCILED, sc.status);
      assertFalse(normalizedAnything(reconHandlers));
      verify(sc.line, never()).setMatchingtype(Mockito.<String>any());
    }
  }

}
