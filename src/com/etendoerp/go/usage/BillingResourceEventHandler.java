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

package com.etendoerp.go.usage;

import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;

import com.etendoerp.go.schemaforge.data.BillingResource;

/**
 * Validates a usage resource when its catalog row is saved, so a misconfigured resource is a
 * configuration-time error with a clear message rather than a nightly job that fails at 02:00
 * or, worse, silently counts nothing.
 *
 * <p>This observer sits on a <b>System configuration table</b>, not a business table, so it
 * does not contradict the design's "no code on the write path" rule — that rule is about
 * tenant saves, where a defect would break ERP operation rather than just billing.
 *
 * <p>It also records the cost of the composed query in {@code LAST_VALIDATION_MS}, which is
 * what makes a fragment that would table-scan every tenant nightly visible before it is ever
 * scheduled. Containment is guaranteed at compose time regardless; this is about discovering
 * the problem at the right moment.
 */
public class BillingResourceEventHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(BillingResourceEventHandler.class);

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(BillingResource.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  public void onNew(@Observes EntityNewEvent event) {
    validate(event);
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    validate(event);
  }

  private void validate(EntityPersistenceEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    BillingResource resource = (BillingResource) event.getTargetInstance();
    // Throwing here is deliberate: it aborts the save, which is the whole point of
    // validating at configuration time rather than discovering the problem in the job.
    UsageResourceValidator.ProbeStamp stamp;
    try {
      stamp = UsageResourceValidator.validateAndProbe(resource);
    } catch (IllegalArgumentException e) {
      // Rethrown as OBException so the reason actually reaches the user. The validator is
      // also called outside a request -- from a backfill re-validating the catalog -- where
      // IllegalArgumentException is the right type, so the translation belongs here at the UI
      // boundary rather than in the validator. Without it the save is still refused, but
      // silently: the record simply does not save and the screen says nothing, which is worse
      // than no validation at all because the user cannot tell what is wrong.
      throw new OBException(e.getMessage(), e);
    }
    stamp(event, stamp);
    log.debug("Validated billing resource '{}'", resource.getSearchKey());
  }

  /**
   * Writes the probe result through {@link EntityPersistenceEvent#setCurrentState}, NOT through
   * the entity's setters.
   *
   * <p>By the time this event fires, Hibernate has already snapshotted the row's state, so a
   * plain {@code resource.setLastValidated(...)} is silently dropped and the columns persist as
   * null — with no exception and no log line, so the only symptom is a catalog row that looks
   * like it was never validated. {@code setCurrentState} is the API for changing a value on the
   * row currently being flushed; the same rule is documented at length on
   * {@code AbstractAccessOverlapCorruptionGuard#correctInheritedOwnership}.
   */
  private void stamp(EntityPersistenceEvent event, UsageResourceValidator.ProbeStamp stamp) {
    Entity entity = event.getTargetInstance().getEntity();
    Property validatedAt = entity.getProperty(BillingResource.PROPERTY_LASTVALIDATED);
    event.setCurrentState(validatedAt, stamp.getValidatedAt());
    if (stamp.getElapsedMs() != null) {
      Property elapsedMs = entity.getProperty(BillingResource.PROPERTY_LASTVALIDATIONMS);
      event.setCurrentState(elapsedMs, stamp.getElapsedMs());
    }
  }
}
