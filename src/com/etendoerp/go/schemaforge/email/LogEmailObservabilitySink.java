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

import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default email observability sink backed by structured, redacted logs.
 */
final class LogEmailObservabilitySink implements EmailObservabilitySink {

  private static final Logger log = LogManager.getLogger(LogEmailObservabilitySink.class);

  @Override
  public void emit(EmailObservabilityEvent event) {
    if (event == null) {
      return;
    }
    log.log(level(event),
        "event=email_contract metrics={} status={} httpStatus={} contract={} version={} "
            + "tenantId={} userId={} recordId={} template={} recipientDomain={} "
            + "recipientHash={} providerStatus={} duplicate={} throttleScope={} "
            + "killSwitchScope={} errorClass={} durationMs={} providerDurationMs={}",
        metrics(event), event.getStatus(), event.getHttpStatus(),
        event.getContractName(), event.getVersion(), event.getTenantId(), event.getUserId(),
        event.getRecordId(), event.getTemplate(), event.getRecipientDomain(),
        event.getRecipientHash(), event.getProviderStatus(), event.isDuplicate(),
        event.getThrottleScope(), event.getKillSwitchScope(), event.getErrorClass(),
        event.getDurationMillis(), event.getProviderDurationMillis());
  }

  private static Level level(EmailObservabilityEvent event) {
    String status = event.getStatus();
    if (TransactionalEmailService.STATUS_PROVIDER_FAILED.equals(status)
        || TransactionalEmailService.STATUS_THROTTLED.equals(status)
        || TransactionalEmailService.STATUS_SUPPRESSED.equals(status)) {
      return Level.WARN;
    }
    return Level.INFO;
  }

  private static String metrics(EmailObservabilityEvent event) {
    List<String> metricNames = event.getMetricNames();
    return metricNames == null ? "" : String.join(",", metricNames);
  }
}
