/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;

/**
 * Runs a unit of work as the system user ({@code "0","0","0","0"}) with admin mode on, and hands
 * the calling thread back exactly the execution context it arrived with.
 *
 * <p>The single implementation of a pattern that was hand-written — and got wrong — several times
 * (ETP-5045, ETP-5046): {@link OBContext#restorePreviousMode()} pops the <em>admin-mode stack</em>,
 * it does not undo {@link OBContext#setOBContext(String, String, String, String)}, so the caller's
 * context has to be captured and put back explicitly.
 *
 * <p><b>{@code null} is a legitimate previous context, not a missing one.</b> The Stripe webhook is
 * matched before the authentication chain and genuinely has none, so "no context" is restored as
 * no context: {@link OBContext#setOBContext(OBContext)} clears the thread-local when handed
 * {@code null}. Substituting a system context would leave the thread more privileged than it was
 * found.
 *
 * <p>Both unwinding steps run in a {@code finally} and never throw: an exception there would
 * replace the body's real failure with a misleading one, and — worse — a failure leaving admin mode
 * must not skip putting the caller's context back.
 */
public final class SystemContext {

  private static final Logger log = LogManager.getLogger();
  private static final String SYSTEM_ID = "0";

  private SystemContext() {
  }

  /**
   * Runs {@code body} as system and returns its result.
   *
   * @param operation a short description of the caller's work, used only in error logs
   * @param body the work to run as system
   * @param <T> the body's result type
   * @return whatever the body returned
   */
  public static <T> T call(String operation, Supplier<T> body) {
    OBContext previousContext = OBContext.getOBContext();
    OBContext.setOBContext(SYSTEM_ID, SYSTEM_ID, SYSTEM_ID, SYSTEM_ID);
    OBContext.setAdminMode(true);
    try {
      return body.get();
    } finally {
      // Order is load-bearing. Admin mode was entered on top of the system context, so it has to
      // be left before that context is taken away: restorePreviousMode() pops the admin-mode stack
      // and then looks at whichever context is current at that moment, clearing it outright when
      // the stack empties on the shared admin context. Putting the caller's context back first
      // would expose that context to the check and could null it out.
      exitAdminModeQuietly(operation);
      restoreContextQuietly(operation, previousContext);
    }
  }

  /**
   * Void form of {@link #call(String, Supplier)}.
   *
   * @param operation a short description of the caller's work, used only in error logs
   * @param body the work to run as system
   */
  public static void run(String operation, Runnable body) {
    call(operation, () -> {
      body.run();
      return null;
    });
  }

  private static void exitAdminModeQuietly(String operation) {
    try {
      OBContext.restorePreviousMode();
    } catch (RuntimeException e) {
      log.error("Could not leave admin mode after {}", operation, e);
    }
  }

  private static void restoreContextQuietly(String operation, OBContext previousContext) {
    try {
      OBContext.setOBContext(previousContext);
    } catch (RuntimeException e) {
      log.error("Could not restore the caller's OBContext after {}", operation, e);
    }
  }
}
