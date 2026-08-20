/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import javax.enterprise.event.Observes;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;

/**
 * Flags the Etendo Go payment the moment a bank transfer is recorded as rejected, whoever records
 * it — the PSD2 module's scheduled refresh, its manual "Refresh Payment Status" button, the Salt
 * Edge webhook, or Etendo Go's own poll.
 *
 * <p><b>Why an event and not a call.</b> All of those writers live in PSD2, and PSD2 has no business
 * knowing what {@code ETGOERR} is. Observing the row it saves inverts the dependency: PSD2 keeps
 * doing exactly what it did, and Etendo Go reacts. Without this the flag would only be applied when
 * somebody happened to open the invoice or the payment (see
 * {@code PisDeferredPaymentService#reconcileAttemptsFor}, which stays as the safety net for rows
 * changed outside a DAL flush).
 *
 * <p><b>Why this one is safe to write from.</b> The repo's other observer,
 * {@code BankStatementLinePendingAmountHandler}, deliberately only touches its own row through
 * {@code setCurrentState}, and the deferred-payment design explicitly rejected an observer that
 * would have created the payment here — {@code FIN_AddPayment.processPayment} runs several flushes
 * of its own and would recurse into PSD2's. This one does far less: a single field on an entity
 * that is already loaded and is never a {@code PisPayment}, so it cannot re-enter this observer,
 * and no flush of its own — the enclosing one persists it.
 *
 * <p>Only {@code authorized} onwards leaves a payment behind, so an earlier rejection finds nothing
 * to flag and this is a no-op. See {@code PisDeferredPaymentService} for the whole lifecycle.
 */
public class PisRejectedPaymentHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(PisRejectedPaymentHandler.class);

  /** Mirrors {@code PisDeferredPaymentService.PAYMENT_STATUS_ERROR}, which is not visible here. */
  private static final String PAYMENT_STATUS_ERROR = "ETGOERR";

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[]{ ModelProvider.getInstance().getEntity(PisPayment.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    try {
      Entity pisEntity = resolveEntities()[0];
      String status = (String) event.getCurrentState(
          pisEntity.getProperty(PisPayment.PROPERTY_STATUS));
      if (!BankIntegrationConstants.PIS_STATUS_FAILED.equalsIgnoreCase(status)) {
        return;
      }
      FIN_Payment payment = (FIN_Payment) event.getCurrentState(
          pisEntity.getProperty(PisPayment.PROPERTY_PAYMENT));
      if (payment == null || StringUtils.equals(PAYMENT_STATUS_ERROR, payment.getStatus())) {
        // No payment means the bank never committed, so nothing was ever created. Already flagged
        // means another writer got here first — both are ordinary, not errors.
        return;
      }
      // No OBDal.save/flush: the payment is already managed by the session running this flush, and
      // flushing from inside a flush is what makes observers dangerous.
      payment.setStatus(PAYMENT_STATUS_ERROR);
      log.info("Bank transfer {} was rejected — payment {} flagged as {}",
          event.getId(), payment.getDocumentNo(), PAYMENT_STATUS_ERROR);
    } catch (Exception e) {
      // Never let this break the write that triggered it: PSD2 must still record the status even
      // if the Etendo Go side cannot be flagged. reconcileAttemptsFor picks it up on the next read.
      log.error("Could not flag the payment behind rejected bank transfer {}", event.getId(), e);
    }
  }
}
