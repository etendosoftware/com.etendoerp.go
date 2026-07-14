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
import static org.junit.Assume.assumeTrue;

import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.test.base.OBBaseTest;

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
 * {@code com.etendoerp.go.onboarding.OnboardingPsd2SyncServiceTest}) — that turned out to be
 * specific to those tests, not a blanket sandbox failure: THIS test's {@code OBBaseTest} DAL
 * layer initialized successfully and executed against the real local DB (that is how the two
 * trigger issues above were found). Re-run this test after any future change to {@link
 * ReactivatePaymentHandler#removeApplicationDetails} — its mere presence in the suite is not
 * proof the fix works; only a green run of it is.
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
