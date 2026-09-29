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

package com.etendoerp.go.schemaforge.email;

import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import com.etendoerp.go.schemaforge.NeoResponse;

/** Best-effort internal alerts; callers MUST settle their business transaction before sending. */
public class InternalAlertService {
  private static final Logger log = LogManager.getLogger(InternalAlertService.class);
  private final TransactionalEmailService emailService;
  private final Supplier<InternalAlertConfig> configSupplier;

  public InternalAlertService() {
    this(new TransactionalEmailService(), InternalAlertConfig::fromRuntime);
  }
  public InternalAlertService(TransactionalEmailService emailService,
      Supplier<InternalAlertConfig> configSupplier) {
    this.emailService = emailService;
    this.configSupplier = configSupplier;
  }
  public void sendAfterTransaction(InternalAlertEvent event) {
    OBContext previous = null;
    boolean contextChanged = false;
    boolean admin = false;
    try {
      InternalAlertConfig config = configSupplier.get();
      if (!config.accepts(event.getStatus())) return;
      previous = OBContext.getOBContext();
      OBContext.setOBContext("0", "0", "0", "0");
      contextChanged = true;
      OBContext.setAdminMode(true);
      admin = true;
      NeoResponse response = emailService.send(InternalAlertEmailContract.NAME,
          InternalAlertEmailContract.body(event, config));
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      if (response == null || response.getHttpStatus() >= 400) {
        log.warn("Internal alert event={} attempt={} result={} deliveryHTTP={}", event.getEvent(),
            event.getAttemptId(), event.getStatus(), response == null ? null : response.getHttpStatus());
      }
    } catch (RuntimeException e) {
      if (contextChanged) {
        try { OBDal.getInstance().rollbackAndClose(); }
        catch (RuntimeException rollback) { log.warn("Internal alert rollback failed: {}", rollback.getClass().getSimpleName()); }
      }
      log.warn("Internal alert event={} attempt={} delivery failed: {}", event.getEvent(),
          event.getAttemptId(), e.getClass().getSimpleName());
    } finally {
      try {
        if (admin) OBContext.restorePreviousMode();
        if (contextChanged) OBContext.setOBContext(previous);
      } catch (RuntimeException e) {
        log.warn("Internal alert context restore failed: {}", e.getClass().getSimpleName());
      }
    }
  }
}
