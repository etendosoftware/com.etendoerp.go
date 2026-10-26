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

package com.etendoerp.go.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads what the demo trial notices need from the environment data (ETP-5453).
 *
 * <p>Both the scheduled job and the email contract go through this one lookup, so the contract
 * re-derives on its own whether a notice is owed instead of trusting what the caller says.</p>
 */
public interface DemoTrialNoticeSource {

  /**
   * Lists the environments that may owe a notice: active clients carrying a trial start.
   *
   * @return candidate {@code AD_Client_ID}s
   */
  List<String> findCandidateClientIds();

  /**
   * Resolves the notice an environment owes right now, with everything needed to deliver it.
   *
   * @param clientId the environment's {@code AD_Client_ID}
   * @param now the evaluation instant
   * @return the owed notice, or empty when none is owed or it cannot be delivered (inactive
   *     environment, no owner, no active platform account)
   */
  Optional<DemoTrialNoticeRecord> findOwedNotice(String clientId, Instant now);
}
