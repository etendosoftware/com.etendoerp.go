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
 * Thrown when un-reconciling one or more selected operations fails at the Core payment-removal
 * layer (transaction detach / payment reversal / whole-reconciliation undo). It wraps the generic
 * checked failure raised by {@code com.etendoerp.payment.removal} so the reconciliation helpers can
 * declare a dedicated exception (Sonar java:S112) instead of a bare {@code throws Exception}.
 *
 * <p>Being a checked {@link Exception} (not an {@link org.openbravo.base.exception.OBException}), it
 * keeps the servlet's mapping unchanged: system failures wrapped here still fall through
 * {@code ReconciliationHandlerSupport.runPostAction}'s generic {@code catch} to a 500, while
 * business {@code OBException}s are propagated unwrapped and still map to 400.
 */
public class ReconciliationRemovalException extends Exception {

  private static final long serialVersionUID = 1L;

  public ReconciliationRemovalException(Throwable cause) {
    super(cause);
  }
}
