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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

/**
 * State that lives for exactly one {@code /sws/neo/batch} (or MCP {@code etendo_batch}) request
 * and is shared by its operations (ETP-5676).
 *
 * <p>A batch runs its operations sequentially on the request thread, through call frames that
 * belong to the servlet, to core and to the customizations, none of which can carry an extra
 * parameter — the same reason {@link BatchService} keeps its transaction claim in a
 * {@link ThreadLocal}. This class follows that precedent: {@link BatchService} opens the scope
 * before the first operation and closes it in a {@code finally}, so nothing here can outlive the
 * request or leak into the next one served by the pooled thread. Outside a batch
 * {@link #current()} is {@code null} and every reader behaves exactly as before.</p>
 *
 * <p>What it holds is generic: the per-batch callout-discard memory, plus nothing that names an
 * entity.</p>
 */
public final class NeoBatchScope {

  private static final ThreadLocal<NeoBatchScope> CURRENT = new ThreadLocal<>();

  private final NeoCalloutDiscardMemory calloutDiscardMemory = new NeoCalloutDiscardMemory();

  private NeoBatchScope() {
  }

  /**
   * Opens a scope for the current thread. <b>Must be paired with {@link #close(NeoBatchScope)} in
   * a finally block</b>, passing the value returned here.
   *
   * @return the scope that was current before (normally {@code null}), to restore on close
   */
  static NeoBatchScope open() {
    NeoBatchScope previous = CURRENT.get();
    CURRENT.set(new NeoBatchScope());
    return previous;
  }

  /**
   * Closes the current scope, logs what it saved, and restores {@code previous}.
   *
   * @param previous the value {@link #open()} returned
   */
  static void close(NeoBatchScope previous) {
    NeoBatchScope closing = CURRENT.get();
    if (closing != null) {
      closing.calloutDiscardMemory.logSummary();
    }
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
  }

  /** @return the scope of the batch running on this thread, or {@code null} outside a batch */
  public static NeoBatchScope current() {
    return CURRENT.get();
  }

  /**
   * @return the callout-discard memory of the batch running on this thread, or {@code null}
   *     outside a batch (the cascade then runs every callout, as it always has)
   */
  static NeoCalloutDiscardMemory currentCalloutDiscardMemory() {
    NeoBatchScope scope = CURRENT.get();
    return scope != null ? scope.calloutDiscardMemory : null;
  }
}
