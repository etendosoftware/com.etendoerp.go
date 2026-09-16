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

import org.openbravo.base.exception.OBException;

/**
 * Thrown when an invoice-generation request targets a source document that has already been
 * invoiced (ETP-5381).
 *
 * <p>Handlers translate this into HTTP <b>409 Conflict</b>, as opposed to the 400 used for
 * "there is nothing to invoice". The distinction is what lets the frontend tell "you already did
 * this" apart from "there is nothing to do" without parsing message text.
 *
 * <p>Extends {@link OBException} so that any caller which does not distinguish it still degrades
 * to the existing 400 business-error path rather than falling through to a generic 500. Handlers
 * that do distinguish must place their {@code catch (AlreadyInvoicedException)} block before
 * {@code catch (OBException)}.
 *
 * <p>Messages are English literals on purpose: they are mapped to the user's locale by
 * {@code tools/app-shell/src/lib/backendErrors.js}, the convention used by every other
 * invoice-flow error in this module. Do not add AD_MESSAGE rows for them.
 */
public class AlreadyInvoicedException extends OBException {

  private static final long serialVersionUID = 1L;

  public AlreadyInvoicedException(String message) {
    super(message);
  }
}
