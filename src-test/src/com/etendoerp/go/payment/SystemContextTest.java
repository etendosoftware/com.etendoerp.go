/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

/**
 * Unit specs for {@link SystemContext}, the one runner every "do this as System" path shares
 * (the Stripe lifecycle webhook, {@code BillingEventStore}, {@code CheckoutRequestStore}).
 *
 * <p>What it must guarantee, each pinned below: the body runs as System with admin mode on; the
 * caller's context — {@code null} included, which is the webhook's — is handed back afterwards;
 * admin mode is left <em>before</em> that context is put back; the body's own exception is the one
 * the caller sees; and a failure while unwinding never skips restoring the caller's context nor
 * masks the body's result.
 *
 * <p>{@link OBContext} is mocked statically and every call is recorded in order, so the specs read
 * as the exact sequence the thread goes through.
 */
class SystemContextTest {

  private MockedStatic<OBContext> context;
  private final List<String> calls = new ArrayList<>();
  /** The context {@code OBContext.getOBContext()} hands back — i.e. the caller's. */
  private final AtomicReference<OBContext> current = new AtomicReference<>();

  @BeforeEach
  void recordContextCalls() {
    context = mockStatic(OBContext.class);
    context.when(OBContext::getOBContext).thenAnswer(invocation -> current.get());
    context.when(() -> OBContext.setOBContext("0", "0", "0", "0"))
        .thenAnswer(invocation -> calls.add("system"));
    context.when(() -> OBContext.setAdminMode(anyBoolean()))
        .thenAnswer(invocation -> calls.add("adminMode(" + invocation.getArgument(0) + ")"));
    context.when(OBContext::restorePreviousMode)
        .thenAnswer(invocation -> calls.add("restorePreviousMode"));
    context.when(() -> OBContext.setOBContext(org.mockito.ArgumentMatchers.<OBContext>any()))
        .thenAnswer(invocation -> calls.add("restore(" + describe(invocation.getArgument(0)) + ")"));
  }

  @AfterEach
  void close() {
    context.close();
  }

  private String describe(OBContext restored) {
    if (restored == null) {
      return "null";
    }
    return restored == current.get() ? "caller" : "other";
  }

  @Test
  void returnsTheBodysValueHavingRunItAsSystemWithAdminMode() {
    String result = SystemContext.call("a test", () -> {
      calls.add("body");
      return "value";
    });

    assertEquals("value", result);
    assertEquals(List.of("system", "adminMode(true)", "body", "restorePreviousMode",
        "restore(null)"), calls);
  }

  @Test
  void restoresTheCallersContextNotASystemOne() {
    current.set(mock(OBContext.class));

    SystemContext.run("a test", () -> calls.add("body"));

    // Admin mode is left BEFORE the caller's context is put back: restorePreviousMode inspects the
    // context current at that moment, and could clear the caller's if it ran afterwards.
    assertEquals(List.of("system", "adminMode(true)", "body", "restorePreviousMode",
        "restore(caller)"), calls);
  }

  @Test
  void restoresNoContextAsNoContext() {
    // The webhook genuinely has no context. Leaving the System one behind would make the thread
    // more privileged than it was found.
    current.set(null);

    SystemContext.run("a test", () -> calls.add("body"));

    assertEquals("restore(null)", calls.get(calls.size() - 1));
    context.verify(() -> OBContext.setOBContext((OBContext) null));
  }

  @Test
  void propagatesTheBodysExceptionAfterUnwinding() {
    current.set(mock(OBContext.class));
    IllegalStateException boom = new IllegalStateException("body failed");

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> SystemContext.run("a test", () -> {
          throw boom;
        }));

    assertSame(boom, thrown);
    assertEquals(List.of("system", "adminMode(true)", "restorePreviousMode", "restore(caller)"),
        calls);
  }

  @Test
  void aFailureLeavingAdminModeIsQuietAndStillRestoresTheContext() {
    current.set(mock(OBContext.class));
    context.when(OBContext::restorePreviousMode).thenAnswer(invocation -> {
      calls.add("restorePreviousMode!");
      throw new IllegalStateException("admin mode stack corrupted");
    });
    // Re-stubbing a static mock invokes the previous answer once; start the record afresh.
    calls.clear();

    String result = SystemContext.call("a test", () -> "value");

    // Neither the result is lost nor the caller's context skipped.
    assertEquals("value", result);
    assertEquals(List.of("system", "adminMode(true)", "restorePreviousMode!", "restore(caller)"),
        calls);
  }

  @Test
  void aFailureLeavingAdminModeDoesNotMaskTheBodysException() {
    context.when(OBContext::restorePreviousMode).thenThrow(new IllegalStateException("unwind"));
    IllegalArgumentException boom = new IllegalArgumentException("the real failure");

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
        () -> SystemContext.run("a test", () -> {
          throw boom;
        }));

    assertSame(boom, thrown);
    context.verify(() -> OBContext.setOBContext((OBContext) null));
  }

  @Test
  void aFailureRestoringTheContextIsQuiet() {
    context.when(() -> OBContext.setOBContext(org.mockito.ArgumentMatchers.<OBContext>any()))
        .thenThrow(new IllegalStateException("cannot restore"));

    assertEquals("value", SystemContext.call("a test", () -> "value"));
  }
}
