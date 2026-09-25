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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Shared dispatch helper for header handlers that fan out ACTION requests to multiple delegates.
 */
public final class NeoHeaderActionRouter {

  private NeoHeaderActionRouter() {
  }

  static NeoResponse dispatch(NeoContext context, NeoHandler... handlers) {
    if (handlers == null) {
      return null;
    }
    for (NeoHandler handler : handlers) {
      if (handler == null) {
        continue;
      }
      NeoResponse result = handler.handle(context);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  /**
   * Concatenate the {@link NeoHandler#declaredActions} of the delegates a header handler fans
   * out to (ETP-5447), de-duplicated by action name.
   *
   * <p>The first delegate to declare a name wins — the same precedence {@link #dispatch} applies
   * at run time, where the first delegate that answers short-circuits the rest — so the catalog
   * describes the action that will actually run.</p>
   *
   * @param specName   the spec being described
   * @param entityName the entity being described
   * @param delegates  the delegates, in dispatch order; {@code null} entries are skipped
   * @return the combined declarations, in delegate order; never {@code null}
   */
  public static List<NeoActionContract> declaredActions(String specName, String entityName,
      NeoHandler... delegates) {
    List<NeoActionContract> combined = new ArrayList<>();
    if (delegates == null) {
      return combined;
    }
    Set<String> seen = new HashSet<>();
    for (NeoHandler delegate : delegates) {
      if (delegate != null) {
        addNew(combined, seen, delegate.declaredActions(specName, entityName));
      }
    }
    return combined;
  }

  /**
   * Append the declarations whose name is not in {@code seen} yet, recording each name added.
   *
   * @param combined the accumulated declarations
   * @param seen     the names already in {@code combined}
   * @param declared one delegate's declarations; {@code null} and {@code null} entries are skipped
   */
  private static void addNew(List<NeoActionContract> combined, Set<String> seen,
      List<NeoActionContract> declared) {
    if (declared == null) {
      return;
    }
    for (NeoActionContract action : declared) {
      if (action != null && seen.add(action.getName())) {
        combined.add(action);
      }
    }
  }
}
