package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.etendoerp.go.schemaforge.data.BillingEvent;

/** @covers com.etendoerp.go.payment.PromotionRecovery */
class PromotionRecoveryTest {
  @Test void failedIntentIsBackedOffAndLaterIntentsStillRun() {
    List<String> seen = new ArrayList<>();
    PromotionRecovery recovery = new PromotionRecovery() {
      @Override void recoverOne(BillingEvent intent) {
        seen.add(intent.getRequestID());
        if ("bad".equals(intent.getRequestID())) throw new IllegalStateException("expired");
      }
    };
    recovery.events = mock(BillingEventStore.class);
    assertThrows(IllegalStateException.class, () -> recovery.replay(List.of(intent("bad"), intent("good")), false));
    assertEquals(List.of("bad", "good"), seen);
    verify(recovery.events).touchReconciliation(eq("promotion-retry:bad"), eq("promotion.retry"), eq("bad"), anyString());
    verifyNoMoreInteractions(recovery.events);
  }
  @Test void dryRunDoesNotApplyOrWriteAnyMarker() {
    PromotionRecovery recovery = new PromotionRecovery() {
      @Override void recoverOne(BillingEvent intent) { fail("dry run must not apply a provider mutation"); }
    };
    recovery.events = mock(BillingEventStore.class);
    recovery.replay(List.of(intent("pending")), true);
    verifyNoInteractions(recovery.events);
  }
  private static BillingEvent intent(String request) {
    BillingEvent event = mock(BillingEvent.class);
    when(event.getRequestID()).thenReturn(request);
    return event;
  }
}
