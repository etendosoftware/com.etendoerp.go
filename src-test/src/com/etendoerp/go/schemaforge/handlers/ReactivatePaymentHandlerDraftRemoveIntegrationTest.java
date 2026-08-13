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
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentPropDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;

/**
 * Real-Hibernate-session regression test for ETP-4841: deleting a DRAFT ("Borrador") payment from
 * the Pago window must leave its invoice installment PAYABLE, and must leave the invoice's
 * paid/outstanding aggregates untouched.
 *
 * <p><b>The bug.</b> Deleting a draft payment through the Pago window's toolbar trash icon (the
 * {@code eTPRRemovePayment} action, i.e. {@link ReactivatePaymentHandler}) and then trying to
 * register a new payment on the same invoice failed with HTTP 400 <em>"No pending payment schedule
 * details found for this installment"</em> ({@code PaymentRegistrationService.MSG_NO_PENDING_PSD},
 * thrown when {@code findPendingPSDs(scheduleId)} returns zero rows — i.e. no {@code
 * FIN_PaymentScheduleDetail} with {@code paymentDetails IS NULL} exists for that installment).
 *
 * <p><b>How it was diagnosed</b> (empirically, not by reading — an earlier fix premised on Core's
 * internal branching was wrong and got reverted): direct DB inspection of 11 broken invoices showed
 * each installment had ZERO schedule-detail rows, not merely no unlinked one. A controlled A/B
 * experiment then ran the same draft-then-delete cycle on two equivalent clean invoices, once per
 * delete route, with the DB inspected immediately afterwards — the Pago-window route left 0 rows,
 * while the invoice-modal route ({@code PaymentDraftEditService.deleteDraftPayment}, which already
 * released each installment through Core first) left exactly 1 correct pending row. Root cause:
 * {@code removeApplicationDetails} deletes every schedule detail outright and never restores a
 * pending fragment; harmless for a PROCESSED payment (whose plan {@code
 * PaymentRemovalUtil.reactivate()} has already reversed through Core) but fatal for a DRAFT, which
 * skips reactivation entirely.
 *
 * <p><b>What this test adds over the mocked suite.</b> The {@code wasProcessed} branching itself
 * lives in two conditionals and is fully covered by mocks in {@link ReactivatePaymentHandlerTest}
 * (see its ETP-4841 section). What mocks structurally cannot show is the resulting DB state, which
 * is the whole point of the bug: whether a payable, unlinked schedule-detail fragment actually
 * survives, and whether the invoice aggregates stayed put. This test asserts exactly the end state a
 * live run produced (invoice 10000074, 6.05 EUR purchase: after the delete, 1 schedule-detail row of
 * 6.05 with {@code fin_payment_detail_id} free to be relinked, {@code paidamt = 0}, {@code
 * outstandingamt = 6.05}, {@code ispaid = 'N'}), against a real Hibernate session and real Core code
 * ({@code FIN_AddPayment.updatePaymentDetail}).
 *
 * <p><b>Why the aggregate assertions matter as much as the pending-row one.</b> With only the
 * release in place and the invoice recompute left running on the draft path, live testing showed the
 * invoice becoming flagged PAID and still unpayable: {@code PaymentRemovalUtil.sumDetails()} sums
 * EVERY schedule detail of the installment without checking {@code paymentDetails}, so the
 * freshly-restored PENDING fragment was counted as "paid" — {@code paidAmount} = full, {@code
 * outstandingAmount} = 0, {@code Invoice.paymentComplete = true} (observed live on a 39.93 EUR
 * invoice). A draft never contributed to those aggregates in the first place, so the draft path must
 * not recompute them at all. Assertions (c) and (d) below are that guard; unlike the 400, that
 * failure mode is silent and corrupts data rather than erroring.
 *
 * <p><b>Seams used, and why (no production visibility was widened for this test).</b>
 * <ul>
 *   <li>{@code handleRemove} is {@code private} and needs a {@code NeoContext}; following the
 *       precedent already set by {@link ReactivatePaymentHandlerRemoveIntegrationTest} (which drives
 *       the package-visible {@link ReactivatePaymentHandler#removeApplicationDetails} directly), this
 *       test runs the exact same sequence {@code handleRemove} runs for a draft — collect ids,
 *       release to pending, delete join rows, flush, <em>no</em> aggregate recompute, then {@code
 *       PaymentRemovalUtil.remove(payment)}.
 *   <li>{@link ReactivatePaymentHandler#releaseInstallmentsToPending} is package-visible for this
 *       test, exactly as {@code removeApplicationDetails} already was, so it is called directly — a
 *       future rename then fails at COMPILE time instead of silently turning this test into a no-op.
 *       {@code PaymentRegistrationService} is a package-private class in a different package, so
 *       {@code findPendingPSDs} still has to be reached reflectively; that is worth it because
 *       asserting against the REAL production query is the whole point (the bug is defined precisely
 *       as that query returning zero rows), and re-implementing it here would assert against a copy
 *       of the logic rather than the logic.
 *   <li>Consequently, the {@code wasProcessed} branching decision itself is NOT covered here — only
 *       the DB end state of the draft branch is. See {@link ReactivatePaymentHandlerTest}.
 * </ul>
 *
 * <p><b>Data dependency.</b> Scoped to {@link #TEST_CLIENT_ID} for safety (this test deletes real
 * rows mid-transaction, even though {@link #rollbackChanges} always rolls back afterwards — it must
 * never reach for data belonging to an unrelated real client). Every isolation condition lives in the
 * HQL {@code WHERE} clause, ordered deterministically by id — see {@link #DRAFT_CANDIDATE_WHERE} and
 * the reject-cycle-4 note in {@link ReactivatePaymentHandlerRemoveIntegrationTest} on why a
 * client-side scan-and-filter is unreliable (it made candidate selection depend on physical row
 * order, i.e. "fails locally, never in CI"). Skips via {@code assumeTrue} with an explicit message
 * when the test client has no qualifying candidate.
 *
 * <p><b>Known environment limitation — disclosed, not silently skipped.</b> This test was NOT
 * executed while it was written: {@code OBBaseTest} needs the full DAL/Hibernate bootstrap against a
 * live DB, and running it from an ad-hoc classpath in this sandbox is exactly the fragile setup that
 * produced {@code MappingNotFoundException: org/openbravo/base/model/Table.hbm.xml} for other
 * {@code OBBaseTest}s in this module. Its presence in the suite is therefore not evidence the fix
 * works — only a green run is. Run it from the Etendo root with:
 * {@code ./gradlew test --tests
 * "com.etendoerp.go.schemaforge.handlers.ReactivatePaymentHandlerDraftRemoveIntegrationTest"}
 */
public class ReactivatePaymentHandlerDraftRemoveIntegrationTest extends OBBaseTest {

  /**
   * Every isolation condition for the candidate installment, pushed into the query itself:
   * <ol>
   *   <li>belongs to {@link #TEST_CLIENT_ID};
   *   <li>linked to a payment detail whose payment is NOT processed — the draft case this test
   *       exercises (a processed payment is the mirror case, covered by
   *       {@link ReactivatePaymentHandlerRemoveIntegrationTest});
   *   <li>linked to an INVOICE installment (not an order-only or credit/GL fragment), since the
   *       aggregate assertions read {@code FIN_PaymentSchedule} and {@code Invoice};
   *   <li>the installment currently has EXACTLY ONE schedule detail, whose amount equals the
   *       installment amount and whose write-off is zero — the single-fragment shape of the live
   *       reproduction, which makes "the released amount equals the installment amount" an
   *       unambiguous assertion;
   *   <li>the installment's aggregates are still untouched ({@code paidAmount = 0}, {@code
   *       outstandingAmount = amount}) and the invoice is not flagged {@code paymentComplete} — the
   *       documented pre-state for a draft-only application, so assertions (c)/(d) can prove the
   *       cleanup did not move them rather than merely observing whatever they already were;
   *   <li>no {@code FIN_PaymentPropDetail} row on it, so the processed-Payment-Proposal trigger
   *       guard ({@code aprm_fin_prop_detail_check_trg}) cannot interfere;
   *   <li>the payment has exactly one {@code FIN_PaymentDetail}, keeping the fixture to a single
   *       installment so the end-state assertions are unambiguous (which, combined with condition 4,
   *       also makes this schedule detail the payment's ONLY one — so the single-row prop-detail
   *       check above covers the whole payment).
   * </ol>
   *
   * <p>The associations are declared as EXPLICIT joins with short aliases rather than traversed as
   * multi-level implicit paths ({@code e.paymentDetails.finPayment.processed}). Implicit joins on a
   * correlated outer path inside a subquery are the fragile corner of HQL — Hibernate has to hoist
   * the join into the outer {@code FROM}, and the generated SQL varies with the surrounding clause.
   * Explicit aliases make every subquery correlate on a plain alias ({@code ps}, {@code pmt}, {@code
   * e}) instead.
   */
  private static final String DRAFT_CANDIDATE_WHERE =
      // "as e ..." declares the "e" alias — OBQuery only recognizes a leading alias declaration,
      // not a bare "e." reference (same pattern as ReactivatePaymentHandlerRemoveIntegrationTest).
      // Note the joins sit BEFORE the "where", which is what OBQuery#createQueryString expects when
      // splitting the alias/join clause from the where clause.
      "as e "
          + "join e." + FIN_PaymentScheduleDetail.PROPERTY_PAYMENTDETAILS + " pd "
          + "join pd." + FIN_PaymentDetail.PROPERTY_FINPAYMENT + " pmt "
          + "join e." + FIN_PaymentScheduleDetail.PROPERTY_INVOICEPAYMENTSCHEDULE + " ps "
          + "join ps." + FIN_PaymentSchedule.PROPERTY_INVOICE + " inv "
          + "where e." + FIN_PaymentScheduleDetail.PROPERTY_CLIENT + ".id = :clientId "
          + "AND pmt." + FIN_Payment.PROPERTY_PROCESSED + " = false "
          + "AND e." + FIN_PaymentScheduleDetail.PROPERTY_WRITEOFFAMOUNT + " = 0 "
          + "AND e." + FIN_PaymentScheduleDetail.PROPERTY_AMOUNT + " = ps."
          + FIN_PaymentSchedule.PROPERTY_AMOUNT + " "
          + "AND ps." + FIN_PaymentSchedule.PROPERTY_PAIDAMOUNT + " = 0 "
          + "AND ps." + FIN_PaymentSchedule.PROPERTY_OUTSTANDINGAMOUNT + " = ps."
          + FIN_PaymentSchedule.PROPERTY_AMOUNT + " "
          + "AND inv." + Invoice.PROPERTY_PAYMENTCOMPLETE + " = false "
          // Exactly one schedule detail on the installment (the live reproduction's shape).
          + "AND (select count(psd2." + FIN_PaymentScheduleDetail.PROPERTY_ID + ") from "
          + FIN_PaymentScheduleDetail.ENTITY_NAME + " psd2 where psd2."
          + FIN_PaymentScheduleDetail.PROPERTY_INVOICEPAYMENTSCHEDULE + " = ps) = 1 "
          // Exactly one payment detail on the payment (single-installment fixture).
          + "AND (select count(pd2." + FIN_PaymentDetail.PROPERTY_ID + ") from "
          + FIN_PaymentDetail.ENTITY_NAME + " pd2 where pd2."
          + FIN_PaymentDetail.PROPERTY_FINPAYMENT + " = pmt) = 1 "
          // No prop-detail row (proposal trigger guard).
          + "AND not exists (select 1 from " + FIN_PaymentPropDetail.ENTITY_NAME + " ppd where ppd."
          + FIN_PaymentPropDetail.PROPERTY_FINPAYMENTSCHEDULEDETAIL + " = e) "
          + "order by e." + FIN_PaymentScheduleDetail.PROPERTY_ID;

  @Before
  public void setUp() {
    setTestAdminContext();
  }

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  /**
   * ETP-4841 end state, against a real session: after the draft-delete sequence the installment must
   * still be payable and its invoice must still be unpaid.
   *
   * <p>Asserts the four things the live run produced:
   * <ol>
   *   <li>{@code PaymentRegistrationService.findPendingPSDs(scheduleId)} is non-empty — the exact
   *       query whose empty result produced the HTTP 400;
   *   <li>the released amount (sum over the pending rows of amount + write-off) equals the
   *       installment amount, so the whole installment is payable again, not a fragment of it;
   *   <li>{@code FIN_PaymentSchedule.getPaidAmount()} is still 0 and {@code getOutstandingAmount()}
   *       still equals the installment amount;
   *   <li>{@code Invoice.isPaymentComplete()} is still false.
   * </ol>
   * (3) and (4) are the false-"paid" guard.
   */
  @Test
  public void draftRemoveLeavesInstallmentPayableAndInvoiceAggregatesUntouched() throws Exception {
    OBContext.setAdminMode(true);
    try {
      FIN_PaymentScheduleDetail seed = findDraftAppliedInvoiceScheduleDetailForTestClient();
      assumeTrue("Skipping: the test client has no invoice installment applied by a single "
          + "unprocessed (draft) payment via exactly one schedule detail, with untouched "
          + "paid/outstanding aggregates and no payment-proposal rows — see DRAFT_CANDIDATE_WHERE",
          seed != null);

      FIN_PaymentDetail seedDetail = seed.getPaymentDetails();
      assertNotNull("the seed schedule detail must be linked to a payment detail", seedDetail);
      FIN_Payment payment = seedDetail.getFinPayment();
      assertNotNull("the seed detail must belong to a payment", payment);
      assertFalse("sanity check: the candidate payment must be a draft (not processed)",
          Boolean.TRUE.equals(payment.isProcessed()));

      FIN_PaymentSchedule installment = seed.getInvoicePaymentSchedule();
      String scheduleId = installment.getId();
      String invoiceId = installment.getInvoice().getId();
      BigDecimal installmentAmount = installment.getAmount();
      assertTrue("sanity check: the installment amount must be positive",
          installmentAmount.signum() > 0);

      // ── The exact sequence handleRemove runs for a DRAFT payment ──────────────
      // No PaymentRemovalUtil.reactivate() (nothing to reactivate) and, crucially, no
      // updateInvoicesAfterPaymentRemoval(...) — see the class javadoc on why recomputing here
      // corrupts the invoice.
      Set<String> affectedInvoiceIds = PaymentRemovalUtil.collectAffectedInvoiceIds(payment);
      assertTrue("sanity check: the candidate's invoice must be among the affected ids",
          affectedInvoiceIds.contains(invoiceId));

      ReactivatePaymentHandler.releaseInstallmentsToPending(payment);
      ReactivatePaymentHandler.removeApplicationDetails(payment);
      OBDal.getInstance().flush();
      PaymentRemovalUtil.remove(payment);

      // PaymentRemovalUtil.remove() clears the session, so everything below is re-read from the DB
      // (uncommitted-but-flushed transaction state), not from stale in-memory entities.

      // (a) The installment is payable again — the query whose empty result caused the HTTP 400.
      List<FIN_PaymentScheduleDetail> pending = invokeFindPendingPSDs(scheduleId);
      assertFalse("ETP-4841: the installment must keep at least one PENDING (unlinked) "
          + "FIN_PaymentScheduleDetail, or every later payment on this invoice fails with "
          + "\"No pending payment schedule details found for this installment\"", pending.isEmpty());

      // (b) The FULL installment is payable again, not a fragment of it. The row COUNT is
      // deliberately not asserted: the production helper does not depend on which internal branch
      // Core's updatePaymentDetail takes (unlink in place vs. copy), only on the end state, so
      // neither does this test. The live run happened to leave exactly one row.
      BigDecimal releasedTotal = BigDecimal.ZERO;
      for (FIN_PaymentScheduleDetail psd : pending) {
        releasedTotal = releasedTotal.add(PaymentRemovalUtil.nvl(psd.getAmount()))
            .add(PaymentRemovalUtil.nvl(psd.getWriteoffAmount()));
      }
      assertEquals("the released pending amount must equal the full installment amount",
          0, releasedTotal.compareTo(installmentAmount));

      // (c) The invoice aggregates were NOT recomputed — a draft never moved them.
      FIN_PaymentSchedule reloadedInstallment =
          OBDal.getInstance().get(FIN_PaymentSchedule.class, scheduleId);
      assertNotNull("the installment must still exist after removing the payment",
          reloadedInstallment);
      assertEquals("ETP-4841: paidAmount must still be 0 — recomputing it on the draft path counts "
              + "the restored PENDING fragment as paid",
          0, PaymentRemovalUtil.nvl(reloadedInstallment.getPaidAmount()).compareTo(BigDecimal.ZERO));
      assertEquals("ETP-4841: outstandingAmount must still equal the installment amount",
          0, PaymentRemovalUtil.nvl(reloadedInstallment.getOutstandingAmount())
              .compareTo(installmentAmount));

      // (d) ...and the invoice must not be flagged as paid.
      Invoice reloadedInvoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      assertNotNull("the invoice must still exist after removing the payment", reloadedInvoice);
      assertFalse("ETP-4841: the invoice must NOT be flagged paymentComplete after deleting a "
              + "draft payment — that is the silent-corruption half of the bug",
          Boolean.TRUE.equals(reloadedInvoice.isPaymentComplete()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private FIN_PaymentScheduleDetail findDraftAppliedInvoiceScheduleDetailForTestClient() {
    OBQuery<FIN_PaymentScheduleDetail> query = OBDal.getInstance()
        .createQuery(FIN_PaymentScheduleDetail.class, DRAFT_CANDIDATE_WHERE);
    query.setNamedParameter("clientId", TEST_CLIENT_ID);
    query.setMaxResult(1);
    List<FIN_PaymentScheduleDetail> results = query.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Invokes the real {@code PaymentRegistrationService.findPendingPSDs(String)}. The class is
   * package-private in {@code com.etendoerp.go.schemaforge}, hence {@code Class.forName} — asserting
   * against the actual production query is the point, since the bug is defined precisely as that
   * query returning zero rows.
   */
  @SuppressWarnings("unchecked")
  private static List<FIN_PaymentScheduleDetail> invokeFindPendingPSDs(String scheduleId)
      throws Exception {
    Class<?> serviceClass = Class.forName("com.etendoerp.go.schemaforge.PaymentRegistrationService");
    Method method = serviceClass.getDeclaredMethod("findPendingPSDs", String.class);
    method.setAccessible(true);
    return (List<FIN_PaymentScheduleDetail>) invokeUnwrapped(method, null, scheduleId);
  }

  /**
   * Rethrows the underlying failure instead of an {@code InvocationTargetException} wrapper, so a
   * real production exception (e.g. a trigger rejecting a delete) shows up directly in the test
   * report rather than buried one cause deep.
   */
  private static Object invokeUnwrapped(Method method, Object target, Object... args)
      throws Exception {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw e;
    }
  }
}
