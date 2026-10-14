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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.openbravo.dal.service.OBDal;

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
 * <p>What it holds is generic: the request's options ({@code _onlyReturnIds}), the per-batch
 * callout-discard memory and a lookup memo ({@link #memoize}). Nothing in it names an entity; a
 * customization that memoizes names its own keys.</p>
 */
public final class NeoBatchScope {

  private static final ThreadLocal<NeoBatchScope> CURRENT = new ThreadLocal<>();

  private final NeoCalloutDiscardMemory calloutDiscardMemory = new NeoCalloutDiscardMemory();
  private final boolean onlyReturnIds;
  private final Map<String, Object> memo = new HashMap<>();
  /** The DAL session the memo's values were loaded in; see {@link #memoize}. */
  private Object memoSession;

  private NeoBatchScope(boolean onlyReturnIds) {
    this.onlyReturnIds = onlyReturnIds;
  }

  /**
   * Opens a scope for the current thread. <b>Must be paired with {@link #close(NeoBatchScope)} in
   * a finally block</b>, passing the value returned here.
   *
   * @param onlyReturnIds the request's {@code _onlyReturnIds} option
   * @return the scope that was current before (normally {@code null}), to restore on close
   */
  static NeoBatchScope open(boolean onlyReturnIds) {
    NeoBatchScope previous = CURRENT.get();
    CURRENT.set(new NeoBatchScope(onlyReturnIds));
    return previous;
  }

  /**
   * @return {@code true} when the request was sent with {@code _onlyReturnIds}: each operation's
   *     write may skip the decoration of the echoed record, since only its id is read
   */
  boolean isOnlyReturnIds() {
    return onlyReturnIds;
  }

  /**
   * Closes the current scope, logs what it saved, and restores {@code previous}.
   *
   * @param previous the value {@link #open(boolean)} returned
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
   * Resolves {@code key} once per batch request and reuses the answer for every later operation
   * of the same batch (ETP-5676). Outside a batch it simply calls {@code loader}.
   *
   * <p><b>The key must carry everything the lookup depends on</b> — the client, the organisation,
   * the entity class — so two operations that would get different answers never share one. A
   * {@code null} answer is remembered too.</p>
   *
   * <p>The whole memo is dropped when the DAL session changes. A process that commits inside the
   * batch closes the session ({@code BatchService.TransactionTracker} detects the same thing), and
   * a DAL object loaded in the old one is detached: reusing it would fail on its first lazy read.</p>
   *
   * @param key    the lookup's identity; namespace it by the owning class
   * @param loader the lookup itself
   * @param <T>    the answer's type
   * @return the remembered or freshly loaded answer
   */
  @SuppressWarnings("unchecked")
  public static <T> T memoize(String key, Supplier<T> loader) {
    NeoBatchScope scope = CURRENT.get();
    if (scope == null) {
      return loader.get();
    }
    Object session = currentSession();
    if (session != scope.memoSession) {
      scope.memo.clear();
      scope.memoSession = session;
    }
    if (scope.memo.containsKey(key)) {
      return (T) scope.memo.get(key);
    }
    T value = loader.get();
    scope.memo.put(key, value);
    return value;
  }

  private static Object currentSession() {
    try {
      return OBDal.getInstance().getSession();
    } catch (Exception e) {
      return null;
    }
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
