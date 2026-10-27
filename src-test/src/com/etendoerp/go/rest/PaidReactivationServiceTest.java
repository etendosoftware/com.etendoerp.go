/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.openbravo.base.exception.OBException;

/**
 * Specs for {@link PaidReactivationService}, which applies a paid re-subscription whose payment
 * webhook was lost through the webhook's own {@code applyReactivationIfPending}: it delegates with
 * the provider instant, answers what the webhook path answered, and lets a failure through
 * unchanged so nothing is reported recovered that was not recorded.
 *
 * <p>The webhook path itself, and its fence, are specified in
 * {@code EtendoGoJwtServletCoverageTest} and {@code CheckoutRequestStoreIntegrationTest}.
 *
 * @covers com.etendoerp.go.rest.PaidReactivationService
 */
public class PaidReactivationServiceTest {

  private static final Instant PAID_AT = Instant.ofEpochSecond(1_792_200_000L);

  @Test
  public void itAppliesThroughTheWebhookPathWithTheProviderInstant() {
    EtendoGoJwtServlet runner = mock(EtendoGoJwtServlet.class);
    when(runner.applyReactivationIfPending("req-1", PAID_AT)).thenReturn(true);

    assertTrue(new PaidReactivationService(() -> runner).applyIfPending("req-1", PAID_AT));

    verify(runner).applyReactivationIfPending("req-1", PAID_AT);
  }

  @Test
  public void aRequestNoLongerPendingIsReportedAsNotApplied() {
    EtendoGoJwtServlet runner = mock(EtendoGoJwtServlet.class);
    when(runner.applyReactivationIfPending("req-1", null)).thenReturn(false);

    assertFalse(new PaidReactivationService(() -> runner).applyIfPending("req-1", null));
  }

  @Test
  public void aFailureToRecordItIsPropagatedUnchanged() {
    EtendoGoJwtServlet runner = mock(EtendoGoJwtServlet.class);
    OBException failure = new OBException("could not mark the tenant productive");
    when(runner.applyReactivationIfPending("req-1", PAID_AT)).thenThrow(failure);

    try {
      new PaidReactivationService(() -> runner).applyIfPending("req-1", PAID_AT);
      fail("The reconciliation must see the failure, or it would count the request applied");
    } catch (OBException thrown) {
      assertSame(failure, thrown);
    }
  }

  @Test
  public void everyCallGetsAFreshRunner() {
    AtomicInteger made = new AtomicInteger();
    PaidReactivationService service = new PaidReactivationService(() -> {
      made.incrementAndGet();
      return mock(EtendoGoJwtServlet.class);
    });

    service.applyIfPending("req-1", null);
    service.applyIfPending("req-2", null);

    assertEquals(2, made.get());
  }
}
