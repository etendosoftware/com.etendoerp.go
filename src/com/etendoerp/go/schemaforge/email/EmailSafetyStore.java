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
import java.util.Optional;

/**
 * Store used by anti-abuse checks, idempotency, kill switches, and auditing.
 */
public interface EmailSafetyStore {

  /**
   * Checks whether a kill switch suppresses the send.
   *
   * @param context resolved send context
   * @return kill-switch result
   */
  EmailKillSwitchResult checkKillSwitch(EmailSendContext context);

  /**
   * Finds a previously successful send for an idempotency key.
   *
   * @param context resolved send context
   * @param idempotencyKey resolved idempotency key
   * @return previous successful send when available
   */
  Optional<EmailAuditRecord> findSentByIdempotencyKey(EmailSendContext context,
      String idempotencyKey);

  /**
   * Finds a previously successful send without requiring the original send context.
   *
   * @param contractName email contract name
   * @param tenantId tenant or client id
   * @param idempotencyKey resolved idempotency key
   * @return previous successful send when available
   */
  Optional<EmailAuditRecord> findSentAudit(String contractName, String tenantId,
      String idempotencyKey);

  /**
   * Checks and records throttle counters.
   *
   * @param context resolved send context
   * @param rules throttle rules to apply
   * @return throttle result
   */
  EmailThrottleResult checkAndIncrement(EmailSendContext context, List<EmailThrottleRule> rules);

  /**
   * Records an audit event.
   *
   * @param auditRecord audit record
   */
  void recordAudit(EmailAuditRecord auditRecord);
}
