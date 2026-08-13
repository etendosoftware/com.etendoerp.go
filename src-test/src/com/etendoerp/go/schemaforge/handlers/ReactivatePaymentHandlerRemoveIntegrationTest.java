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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.List;
import java.util.Set;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;

/**
 * Real-Hibernate-session regression test for the ETP-4479 reject-cycle 1 bug
 * (collection-detachment on delete).
 *
 * <p>An earlier version of {@link ReactivatePaymentHandler#removeApplicationDetails} called
 * {@code OBDal.remove()} on each child ({@code FIN_PaymentScheduleDetail} /
 * {@code FIN_PaymentDetail}) without also removing it from its parent's already-loaded
 * in-memory Hibernate collection. Deployed live, that surfaced as an unhandled 500 — the
 * request appeared to succeed, but the end-of-thread flush ({@code DalThreadCleaner
 * .cleanWithCommit}, which runs after the servlet has already returned and so cannot be
 * caught by any try/catch in this handler) threw {@code EntityNotFoundException: deleted
 * object would be re-saved by cascade} because the stale collection still "contained" an
 * entity the session also knew was deleted.
 *
 * <p>This is exactly the class of bug the mocked unit tests in
 * {@link ReactivatePaymentHandlerTest} cannot catch — see the coverage-gap note at the top of
 * that file's Remove-action section. Mockito {@code ArrayList}s have no dirty-checking or
 * cascade semantics; only a real Hibernate {@code Session} can reproduce the failure. This
 * test does: it loads an actually-applied {@code FIN_Payment} from the DB, runs the exact
 * production cleanup code, then calls {@code OBDal.flush()} — the same underlying
 * {@code SessionImpl.doFlush()} that {@code DalThreadCleaner.cleanWithCommit()} runs at the
 * end of every real request. If the collection-detach fix regresses, this flush throws; with
 * the fix in place, it must not.
 *
 * <p><b>Scope — deliberately narrowed after reject-cycle 2.</b> Running this test the first
 * time (against whatever applied payment the test client happened to have) surfaced TWO
 * further, independent core-Etendo triggers that block detail-row mutation for reasons that
 * have nothing to do with collection detachment:
 * <ul>
 *   <li>{@code aprm_fin_pmt_detail_check_trg} blocks touching {@code FIN_Payment_Detail}
 *       while the payment itself is {@code Processed = 'Y'} — handled in production by
 *       reactivating first (see the reject-cycle 2 note on {@link
 *       ReactivatePaymentHandler#handleRemove}), which is covered by a mocked unit test
 *       ({@code handleRemoveReactivatesProcessedPaymentBeforeCleanup}) rather than here.
 *   <li>{@code aprm_fin_prop_detail_check_trg} blocks touching {@code FIN_Payment_Prop_Detail}
 *       while its owning {@code FIN_Payment_Proposal} is processed — handled in production by
 *       refusing the removal outright (see {@link
 *       ReactivatePaymentHandler#findProcessedProposalPropDetail}), also covered by a mocked
 *       unit test ({@code handleRemoveRefusesPaymentTiedToProcessedProposal}).
 * </ul>
 * Both of those are business-rule guards, not Hibernate session-state bugs, and are cheap and
 * reliable to verify with mocks. This test instead seeks out a candidate that isolates the ONE
 * thing only a real session can prove: an <em>unprocessed</em> payment (so the first trigger
 * doesn't fire) with applied details that have NO prop-detail rows (so the second trigger
 * doesn't fire either) — so a real flush failure here can only mean the collection-detachment
 * fix itself has regressed, not one of the other two orthogonal business rules.
 *
 * <p><b>Data dependency.</b> Scoped to {@link #TEST_CLIENT_ID} for safety (this test deletes
 * real rows mid-transaction, even though {@link #rollbackChanges} always rolls back
 * afterward — it must never reach for data belonging to an unrelated real client). Skips via
 * {@code assumeTrue} — matching the established pattern in
 * {@code com.etendoerp.go.mcp.NeoWidgetMcpIntegrationTest} for data-dependent integration
 * tests — when the test client has no matching candidate.
 *
 * <p><b>Known environment limitation — disclosed, not silently skipped:</b> earlier attempts to
 * run any {@code OBBaseTest} in this module in this sandbox failed with
 * {@code org.hibernate.boot.MappingNotFoundException: Mapping (RESOURCE) not found :
 * org/openbravo/base/model/Table.hbm.xml} for OTHER tests
 * ({@code com.etendoerp.go.mcp.NeoWidgetMcpIntegrationTest},
 * {@code com.etendoerp.go.onboarding.OnboardingBankConnectionSyncServiceTest}) — that turned out to be
 * specific to those tests, not a blanket sandbox failure: THIS test's {@code OBBaseTest} DAL
 * layer initialized successfully and executed against the real local DB (that is how the two
 * trigger issues above were found). Re-run this test after any future change to {@link
 * ReactivatePaymentHandler#removeApplicationDetails} — its mere presence in the suite is not
 * proof the fix works; only a green run of it is.
 *
 * <p><b>Reject-cycle 3 note.</b> An earlier version of this file also had a test
 * ({@code reactivatingReconciledPaymentProtectsPreloadedLazyTabProxy}) reproducing "could not
 * initialize proxy [ADTab#...] - no Session" against a real Hibernate proxy loaded before
 * {@code PaymentRemovalUtil.reactivate()}'s internal {@code session.clear()}. That bug was
 * fixed at the root, not patched around: {@code handleRemove} no longer delegates the final
 * removal step to {@code NeoButtonActionHelper.executeButtonActionCore(context.getSfEntity(),
 * ...)} at all — it calls {@code PaymentRemovalUtil.remove(payment)} directly — so there is no
 * longer any {@code context.getSfEntity()}/{@code AD_Tab} proxy for a session clear to strand.
 * That test (and the now-deleted {@code
 * ReactivatePaymentHandler#ensureTabAssociationsInitialized} workaround it exercised) has been
 * removed as dead weight; {@code
 * reactivatesProcessedPaymentThenRemovesItAgainstRealSession} below replaces it with real,
 * still-valuable coverage of the current design: the full reactivate-then-cleanup-then-remove
 * sequence against a real processed payment and a real session.
 */
public class ReactivatePaymentHandlerRemoveIntegrationTest extends OBBaseTest {

  private static final int CANDIDATE_SCAN_LIMIT = 25;

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

  @Test
  public void removeApplicationDetailsSurvivesRealHibernateFlush() {
    OBContext.setAdminMode(true);
    try {
      FIN_PaymentDetail seedDetail = findIsolatedAppliedPaymentDetailForTestClient();
      assumeTrue("Skipping: the test client has no applied FIN_Payment that isolates the "
          + "collection-detachment fix from the two orthogonal trigger-guarded cases "
          + "(processed payment / processed Payment Proposal) — see class javadoc",
          seedDetail != null);

      FIN_Payment payment = seedDetail.getFinPayment();
      assertNotNull("the seed detail must belong to a payment", payment);

      List<FIN_PaymentDetail> detailsBeforeCleanup = payment.getFINPaymentDetailList();
      assertFalse("sanity check: the payment must actually have details to exercise this test",
          detailsBeforeCleanup.isEmpty());

      ReactivatePaymentHandler.removeApplicationDetails(payment);

      // This is the exact operation that crashed in production before the reject-cycle-1 fix:
      // OBDal.flush() runs the same Hibernate SessionImpl.doFlush() that
      // DalThreadCleaner.cleanWithCommit() runs at the end of every real request. If any
      // parent collection still references a deleted child, this line throws
      // EntityNotFoundException: deleted object would be re-saved by cascade.
      OBDal.getInstance().flush();

      assertFalse("payment.getFINPaymentDetailList() must no longer contain the removed "
          + "detail after a real flush", payment.getFINPaymentDetailList().contains(seedDetail));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Reject-cycle 2/3 regression, end-to-end against a real session: a real PROCESSED payment
   * (not reconciled — see below) must be reactivated via the real {@code
   * PaymentRemovalUtil.reactivate(id, "R")}, re-fetched, have its join rows cleaned up via the
   * real {@link ReactivatePaymentHandler#removeApplicationDetails}, and then be removed via the
   * real {@code PaymentRemovalUtil.remove(payment)} — the exact sequence {@code handleRemove}
   * runs, minus the NEO servlet/context plumbing, with no mocks anywhere in the chain.
   *
   * <p><b>Why "not reconciled":</b> an earlier version of this test tried a real reconciled
   * payment and hit an unrelated environment gap — {@code
   * TransactionRemovalUtil.reactivateAndRemove(...)} (called inside {@code reactivate()} only
   * when the payment has a linked {@code FIN_Finacc_Transaction}) needs a live HTTP {@code
   * RequestContext} that a plain {@code OBBaseTest} JUnit run never has, and throws "Error when
   * reactivating the transaction: No request object set". That is a gap in exercising
   * 15-year-old classic-process code outside a servlet container, unrelated to what this test
   * verifies. A processed-but-not-reconciled payment skips that branch inside {@code
   * reactivate()} entirely (confirmed from source: {@code if (transaction != null) {...}}) and
   * exercises everything else for real: the {@code aprm_fin_pmt_detail_check_trg} trigger
   * genuinely blocking (then, post-reactivate, genuinely allowing) the detail-row deletes, and
   * the real final {@code PaymentRemovalUtil.remove(payment)} call.
   *
   * <p><b>Reject-cycle 4 note — a second, independent trigger for the same "No request object
   * set" symptom.</b> Excluding reconciled payments (above) was not sufficient: this test still
   * failed intermittently with the identical-looking error, but from a completely different call
   * site. {@code FIN_PaymentProcess} (invoked by {@code PaymentRemovalUtil.reactivate()} via
   * {@code FIN_AddPayment.processPayment()}) recomputes credit-conversion amounts through {@code
   * FIN_Utility.getConversionRatePrecision(RequestContext.get().getVariablesSecureApp())}
   * whenever the candidate payment's currency differs from its Business Partner's currency —
   * this branch fires for the "R" reactivation action itself, independent of reconciliation
   * status. The original {@link #findIsolatedProcessedAppliedPaymentDetailForTestClient} scanned
   * only the first {@link #CANDIDATE_SCAN_LIMIT} rows client-side with no {@code ORDER BY}, so
   * whichever row Postgres happened to return first could be a currency-mismatched one —
   * explaining why this failed locally but never in CI or for other developers (different
   * physical row order per environment), and why a client-side scan window alone would not have
   * been a reliable fix (only ~1% of this client's candidate rows qualify, so a small window
   * often misses all of them regardless of order). Fixed by moving every isolation condition —
   * including the currency-match check — into the query itself, in {@link
   * #ISOLATED_PROCESSED_CANDIDATE_WHERE}, ordered deterministically by id, so candidate selection
   * is now reproducible across environments and does not depend on scan-window luck.
   */
  @Test
  public void reactivatesProcessedPaymentThenRemovesItAgainstRealSession() throws Exception {
    OBContext.setAdminMode(true);
    try {
      FIN_PaymentDetail seedDetail = findIsolatedProcessedAppliedPaymentDetailForTestClient();
      assumeTrue("Skipping: the test client has no unreconciled PROCESSED payment with applied "
          + "details (and no prop-detail rows) to exercise this end-to-end", seedDetail != null);

      FIN_Payment payment = seedDetail.getFinPayment();
      assertNotNull("the seed detail must belong to a payment", payment);
      assertTrue("sanity check: candidate must actually be processed",
          Boolean.TRUE.equals(payment.isProcessed()));

      Set<String> affectedInvoiceIds = PaymentRemovalUtil.collectAffectedInvoiceIds(payment);

      PaymentRemovalUtil.reactivate(payment.getId(), "R");
      FIN_Payment reactivatedPayment = OBDal.getInstance().get(FIN_Payment.class, payment.getId());
      assertFalse("payment must no longer be processed after reactivate()",
          Boolean.TRUE.equals(reactivatedPayment.isProcessed()));

      ReactivatePaymentHandler.removeApplicationDetails(reactivatedPayment);
      OBDal.getInstance().flush();
      PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(affectedInvoiceIds);
      OBDal.getInstance().flush();

      // The exact final call handleRemove makes instead of delegating to
      // NeoButtonActionHelper.executeButtonActionCore — no exception means the payment header
      // itself was removed cleanly, with no leftover child rows blocking it.
      PaymentRemovalUtil.remove(reactivatedPayment);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Reject-cycle 4 finding: candidate selection for this test must not merely scan the first
   * {@link #CANDIDATE_SCAN_LIMIT} {@code FIN_PaymentDetail} rows for the client (in whatever
   * order the DB happens to return, or even in id order) and filter them client-side — for
   * {@link #TEST_CLIENT_ID} only ~1% of ~2,500 candidate rows satisfy all four isolation
   * conditions below, so a small client-side scan window reliably misses them, and an unordered
   * one made the outcome depend on physical row order (different per environment — the "fails
   * locally, never in CI" symptom). All four conditions are instead pushed into the HQL WHERE
   * clause so the DB itself finds a qualifying row, deterministically (ordered by id), with no
   * scan-window guesswork:
   * <ol>
   *   <li>{@code processed = true} (the case this test exercises: reactivate a processed
   *       payment)
   *   <li>no {@code FIN_FinaccTransaction} linked to the payment — i.e. not reconciled, the
   *       exact same query {@code Utilities.getTransactionFromPayment} in {@code
   *       PaymentRemovalUtil.reactivate()} uses, confirmed identical by reading
   *       {@code com.etendoerp.payment.removal.util.Utilities#getTransactionFromPayment}
   *   <li>no {@code FIN_PaymentPropDetail} rows on any of its schedule details — so the
   *       processed-Payment-Proposal trigger guard doesn't fire (see class javadoc)
   *   <li>payment currency matches its Business Partner's currency (or the BP/currency is
   *       unset) — see {@link #ISOLATED_PROCESSED_CANDIDATE_WHERE} javadoc below for why this
   *       is required
   * </ol>
   */
  private static final String ISOLATED_PROCESSED_CANDIDATE_WHERE =
      // "as e where" declares the "e" alias used throughout — OBQuery only recognizes a leading
      // alias declaration, not a bare "e." reference (see OBQuery#createQuery / the identical
      // pattern in NeoSelectorExecutionHelper#appendSimpleSearchFilter).
      "as e where e." + FIN_PaymentDetail.PROPERTY_CLIENT + ".id = :clientId "
          + "AND e." + FIN_PaymentDetail.PROPERTY_FINPAYMENT + "." + FIN_Payment.PROPERTY_PROCESSED
          + " = true "
          + "AND EXISTS (SELECT 1 FROM " + FIN_PaymentScheduleDetail.ENTITY_NAME + " psd WHERE psd."
          + FIN_PaymentScheduleDetail.PROPERTY_PAYMENTDETAILS + " = e) "
          // Not reconciled — mirrors Utilities#getTransactionFromPayment exactly (see javadoc).
          + "AND NOT EXISTS (SELECT 1 FROM " + FIN_FinaccTransaction.ENTITY_NAME + " t WHERE t."
          + FIN_FinaccTransaction.PROPERTY_FINPAYMENT + " = e." + FIN_PaymentDetail.PROPERTY_FINPAYMENT
          + ") "
          // No FIN_PaymentPropDetail rows on any schedule detail of this payment detail.
          + "AND NOT EXISTS (SELECT 1 FROM " + FIN_PaymentScheduleDetail.ENTITY_NAME + " psd2 JOIN psd2."
          + FIN_PaymentScheduleDetail.PROPERTY_FINPAYMENTPROPDETAILLIST + " ppd WHERE psd2."
          + FIN_PaymentScheduleDetail.PROPERTY_PAYMENTDETAILS + " = e) "
          // Reject-cycle 4: currency conversion during reactivation needs a live HTTP
          // RequestContext (see FIN_PaymentProcess.java ~L869-891/1604-1609) unavailable in
          // OBBaseTest — exclude currency-mismatched candidates, unrelated to reconciliation.
          + "AND (e." + FIN_PaymentDetail.PROPERTY_FINPAYMENT + "." + FIN_Payment.PROPERTY_BUSINESSPARTNER
          + " IS NULL OR e." + FIN_PaymentDetail.PROPERTY_FINPAYMENT + "."
          + FIN_Payment.PROPERTY_BUSINESSPARTNER + "." + BusinessPartner.PROPERTY_CURRENCY
          + " IS NULL OR e." + FIN_PaymentDetail.PROPERTY_FINPAYMENT + "."
          + FIN_Payment.PROPERTY_BUSINESSPARTNER + "." + BusinessPartner.PROPERTY_CURRENCY + " = e."
          + FIN_PaymentDetail.PROPERTY_FINPAYMENT + "." + FIN_Payment.PROPERTY_CURRENCY + ") "
          + "ORDER BY e." + FIN_PaymentDetail.PROPERTY_ID;

  private FIN_PaymentDetail findIsolatedProcessedAppliedPaymentDetailForTestClient() {
    OBQuery<FIN_PaymentDetail> query = OBDal.getInstance()
        .createQuery(FIN_PaymentDetail.class, ISOLATED_PROCESSED_CANDIDATE_WHERE);
    query.setNamedParameter("clientId", TEST_CLIENT_ID);
    query.setMaxResult(1);
    List<FIN_PaymentDetail> results = query.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Scans up to {@link #CANDIDATE_SCAN_LIMIT} applied {@code FIN_PaymentDetail} rows for the
   * test client and returns the first one whose payment is NOT processed and whose schedule
   * details have NO {@code FIN_PaymentPropDetail} rows — i.e. one that isolates the
   * collection-detachment fix from the two orthogonal trigger-guarded cases (see class
   * javadoc). Returns {@code null} if no such candidate exists.
   */
  private FIN_PaymentDetail findIsolatedAppliedPaymentDetailForTestClient() {
    OBCriteria<FIN_PaymentDetail> criteria =
        OBDal.getInstance().createCriteria(FIN_PaymentDetail.class);
    criteria.add(Restrictions.eq(FIN_PaymentDetail.PROPERTY_CLIENT + ".id", TEST_CLIENT_ID));
    criteria.add(Restrictions.isNotEmpty(FIN_PaymentDetail.PROPERTY_FINPAYMENTSCHEDULEDETAILLIST));
    criteria.setMaxResults(CANDIDATE_SCAN_LIMIT);

    for (FIN_PaymentDetail candidate : criteria.list()) {
      if (isIsolatedCandidate(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static boolean isIsolatedCandidate(FIN_PaymentDetail candidate) {
    FIN_Payment payment = candidate.getFinPayment();
    if (payment == null || Boolean.TRUE.equals(payment.isProcessed())) {
      return false;
    }
    for (FIN_PaymentScheduleDetail scheduleDetail : candidate.getFINPaymentScheduleDetailList()) {
      if (!scheduleDetail.getFINPaymentPropDetailList().isEmpty()) {
        return false;
      }
    }
    return true;
  }
}
