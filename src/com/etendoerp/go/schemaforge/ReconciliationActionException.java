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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

/**
 * Thrown when a mutating reconciliation POST action (reconcile-group, apply-suggestions, reactivate,
 * remove-operation) fails with a non-business error. It wraps the bare {@code Exception} that the
 * business methods declare so the dispatch seam can use a dedicated exception (Sonar java:S112)
 * instead of a generic {@code throws}.
 *
 * <p>Being a checked {@link Exception} (not an {@link org.openbravo.base.exception.OBException}), it
 * keeps the servlet's mapping unchanged: a system failure wrapped here falls through
 * {@code ReconciliationHandlerSupport.runPostAction}'s generic {@code catch} to a 500, while a
 * business {@code OBException} is propagated unwrapped and still maps to 400.
 */
public class ReconciliationActionException extends Exception {

  private static final long serialVersionUID = 1L;

  public ReconciliationActionException(Throwable cause) {
    super(cause);
  }
}
