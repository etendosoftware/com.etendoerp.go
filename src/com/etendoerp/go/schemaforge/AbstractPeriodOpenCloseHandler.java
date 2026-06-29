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

import org.openbravo.dal.core.OBContext;

/**
 * Template base for period open/close handlers.
 *
 * <p>Shares the common scaffolding: request parsing, OBContext admin-mode lifecycle, and
 * afterHandle no-op. Subclasses implement {@link #doHandle} for entity-specific logic and
 * {@link #onError} for handler-specific logging and error response.
 */
abstract class AbstractPeriodOpenCloseHandler implements NeoHandler {

  @Override
  public final NeoResponse handle(NeoContext context) {
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(context);
    if (req.isAbort()) {
      return req.toHandlerReturn();
    }
    try {
      OBContext.setAdminMode();
      try {
        return doHandle(req.openCloseValue, req.recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      return onError(req.recordId, e);
    }
  }

  /**
   * Entity-specific open/close logic. Called under OBContext admin mode.
   *
   * @param openCloseValue the action code chosen by the user (e.g. {@code "CO"}, {@code "RE"})
   * @param recordId the ID of the record being processed
   * @return the response to return to the caller
   */
  protected abstract NeoResponse doHandle(String openCloseValue, String recordId) throws Exception;

  /**
   * Handler-specific error response. Log the exception and return an appropriate error response.
   *
   * @param recordId the record ID from the request (for log context)
   * @param e the exception that was thrown
   * @return a 500-level {@link NeoResponse}
   */
  protected abstract NeoResponse onError(String recordId, Exception e);

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }
}
