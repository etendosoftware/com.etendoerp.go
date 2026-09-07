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
package com.etendoerp.go.mcp;

import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Drops a record's cached {@code MCP_CONFIG} as soon as that record is written, so an edit made in
 * the window takes effect on the next MCP call.
 *
 * <p><b>Why an observer and not just a TTL.</b> {@link McpConfigCache} expires entries by
 * inactivity, which is what keeps memory honest but never refreshes an entry that stays warm: an
 * entity queried every few minutes would serve its old configuration for the whole write ceiling.
 * For a column whose sections can be gating access, that is too long to be wrong. This observer is
 * the correctness mechanism; the cache's {@code expireAfterWrite} is only the fallback for writes
 * that never reach the DAL — a dataset import, a modulescript, a direct {@code UPDATE}.</p>
 *
 * <p><b>Scope: only the cache.</b> This observer validates nothing and rejects nothing. Bad
 * configuration is caught where it is read ({@link McpEntityConfig}) and by the deploy-time
 * validator — not here. Refusing a save would block an operator mid-edit, and the record is not
 * what is wrong: an unusable payload simply leaves its entity unpublished until it is fixed.</p>
 *
 * <p>No {@code @Priority}: this runs after core's own observers and its ordering relative to them
 * does not matter, since it touches nothing Hibernate is about to write.</p>
 */
public class McpConfigInvalidationObserver extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(McpConfigInvalidationObserver.class);

  private static Entity[] entities;

  /**
   * The three SchemaForge tables that carry {@code MCP_CONFIG}.
   *
   * <p>Resolved lazily and cached, as core's own observers do: {@code ModelProvider} is not
   * available while CDI is constructing beans at startup.</p>
   */
  @Override
  protected Entity[] getObservedEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(SFSpec.ENTITY_NAME),
          ModelProvider.getInstance().getEntity(SFEntity.ENTITY_NAME),
          ModelProvider.getInstance().getEntity(SFField.ENTITY_NAME) };
    }
    return entities;
  }

  /**
   * A new record cannot have a cached entry, but a re-created id can — and clearing an absent key
   * costs nothing, so this is handled for symmetry rather than left as a gap to reason about.
   *
   * @param event the persistence event
   */
  public void onSave(@Observes EntityNewEvent event) {
    invalidate(event);
  }

  /**
   * The case this class exists for: an operator edits {@code MCP_CONFIG} and expects the next call
   * to honour it.
   *
   * @param event the persistence event
   */
  public void onUpdate(@Observes EntityUpdateEvent event) {
    invalidate(event);
  }

  /**
   * Deleting the record must not leave its configuration readable from cache.
   *
   * @param event the persistence event
   */
  public void onDelete(@Observes EntityDeleteEvent event) {
    invalidate(event);
  }

  /**
   * Invalidate the written record, and its whole spec when a spec is what changed.
   *
   * <p>Precedence is why the spec case is wider: a spec-level section is part of what every one of
   * its entities resolves, so editing it has to drop those entries too. Doing that precisely would
   * mean querying the spec's entities inside a persistence event; clearing both caches is coarser
   * but bounded — the caches hold hundreds of entries, rebuilding them is a parse and one query per
   * entry, and editing a spec is a rare, human-paced operation.</p>
   */
  private void invalidate(EntityPersistenceEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    BaseOBObject record = event.getTargetInstance();
    if (record == null) {
      return;
    }
    if (SFSpec.ENTITY_NAME.equals(record.getEntityName())) {
      log.debug("Spec {} changed — clearing MCP config caches", record.getId());
      McpConfigCache.invalidateAll();
      return;
    }
    McpConfigCache.invalidateConfig((String) record.getId());
  }
}
