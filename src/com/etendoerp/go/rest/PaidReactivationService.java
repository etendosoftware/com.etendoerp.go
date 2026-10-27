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
package com.etendoerp.go.rest;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * ETP-5048 — applies a paid re-subscription (ETP-5742) whose payment webhook was lost.
 *
 * <p>The payment webhook records the payment and then applies the re-subscription to the canceled
 * environment it names. When that webhook is lost, the billing reconciliation recovers the payment
 * from the provider; without this service the request would stay {@code PAID} and the customer
 * blocked, since a re-subscription is never provisioned.
 *
 * <p><b>One path, two callers.</b> Like {@link PaidProvisioningService}, this class re-implements
 * nothing: it calls {@link EtendoGoJwtServlet#applyReactivationIfPending}, the method the webhook
 * itself runs. That method is fenced (the request row is locked and re-read before anything is
 * written) and idempotent (a request already applied is {@code PROVISIONED} and is left alone), so
 * the webhook and the reconciliation can race on one request safely.
 */
public class PaidReactivationService {

  private final Supplier<EtendoGoJwtServlet> runnerFactory;

  /** Production wiring: a fresh servlet instance per call, as the paid provisioning uses. */
  public PaidReactivationService() {
    this(EtendoGoJwtServlet::new);
  }

  /**
   * Wiring with an explicit runner, for tests.
   *
   * @param runnerFactory supplies the servlet whose re-subscription path is reused
   */
  PaidReactivationService(Supplier<EtendoGoJwtServlet> runnerFactory) {
    this.runnerFactory = runnerFactory;
  }

  /**
   * Applies the re-subscription a paid request names, when it is one and still pending.
   *
   * @param requestId the checkout {@code REQUEST_ID}
   * @param paidAt the provider instant of the payment, or null when unknown
   * @return whether this call applied it; false for a new purchase, or one already applied
   * @throws RuntimeException when the re-subscription could not be recorded; nothing is then
   *     committed and the request stays {@code PAID} for a later retry
   */
  public boolean applyIfPending(String requestId, Instant paidAt) {
    return runnerFactory.get().applyReactivationIfPending(requestId, paidAt);
  }
}
