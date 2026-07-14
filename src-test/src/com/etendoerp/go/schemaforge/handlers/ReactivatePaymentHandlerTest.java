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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentProposal;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentPropDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
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

  private static FIN_PaymentDetail mockDetailWith(List<FIN_PaymentScheduleDetail> scheduleDetails) {
    FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
    when(detail.getFINPaymentScheduleDetailList()).thenReturn(scheduleDetails);
    return detail;
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
   * reads the same list being deleted), recalculate the invoice via {@code
   * updateInvoicesAfterPaymentRemoval}, then remove the payment itself via {@code
   * PaymentRemovalUtil.remove(payment)}.
   */
  @Test
  public void handleRemoveCleansUpInvoiceAppliedDetailsBeforeRemovingPayment() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail scheduleDetail = mockScheduleDetail();
    FIN_PaymentSchedule invoiceSchedule = mock(FIN_PaymentSchedule.class);
    when(scheduleDetail.getInvoicePaymentSchedule()).thenReturn(invoiceSchedule);
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
      // Invoice ids were collected BEFORE cleanup, and used to recalculate after.
      removalUtilMock.verify(() -> PaymentRemovalUtil.collectAffectedInvoiceIds(payment));
      removalUtilMock.verify(
          () -> PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(affectedInvoiceIds));
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
      removalUtilMock.verify(
          () -> PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(noAffectedInvoices));
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
   */
  @Test
  public void handleRemoveCleansUpAllDetailsWhenPaymentHasMultipleApplications() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);

    FIN_PaymentScheduleDetail scheduleDetail1 = mockScheduleDetail();
    FIN_PaymentSchedule invoiceSchedule1 = mock(FIN_PaymentSchedule.class);
    when(scheduleDetail1.getInvoicePaymentSchedule()).thenReturn(invoiceSchedule1);
    List<FIN_PaymentScheduleDetail> scheduleDetailList1 = new ArrayList<>(List.of(scheduleDetail1));
    FIN_PaymentDetail detail1 = mockDetailWith(scheduleDetailList1);

    FIN_PaymentScheduleDetail scheduleDetail2 = mockScheduleDetail();
    FIN_PaymentSchedule invoiceSchedule2 = mock(FIN_PaymentSchedule.class);
    when(scheduleDetail2.getInvoicePaymentSchedule()).thenReturn(invoiceSchedule2);
    List<FIN_PaymentScheduleDetail> scheduleDetailList2 = new ArrayList<>(List.of(scheduleDetail2));
    FIN_PaymentDetail detail2 = mockDetailWith(scheduleDetailList2);

    List<FIN_PaymentDetail> detailList = new ArrayList<>(List.of(detail1, detail2));
    when(payment.getFINPaymentDetailList()).thenReturn(detailList);

    Set<String> affectedInvoiceIds = new HashSet<>(List.of("inv-1", "inv-2"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
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
      removalUtilMock.verify(
          () -> PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(affectedInvoiceIds));
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

  // ── afterHandle — guard conditions ────────────────────────────────────────

  /**
   * A list response (no recordId) must be left untouched — the financial-transaction lookup
   * only applies to the single-record detail view.
   */
  @Test
  public void afterHandleReturnsNullForListResponse() throws JSONException {
    JSONObject body = singleRecordBody("pay-1");
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
}
