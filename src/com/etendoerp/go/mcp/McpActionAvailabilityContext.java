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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.base.structure.OrganizationEnabled;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

/**
 * The one record an {@code etendo_schema(view:"actions")} call was asked about (ETP-5714): how to
 * read its properties by name, and which actions its customization says do not apply to it.
 *
 * <p>Advisory data for the catalogue only: nothing built here is ever used to refuse a call. The
 * reader answers {@code null} for a property the record does not have or has no value for, which
 * the availability evaluator reads as "cannot tell" rather than "unavailable".</p>
 */
final class McpActionAvailabilityContext {

  private static final Logger log = LogManager.getLogger(McpActionAvailabilityContext.class);

  private final String recordId;
  private final UnaryOperator<String> reader;
  private final Map<String, String> derivedUnavailable;

  /**
   * Builds a context from already-resolved parts.
   *
   * @param recordId           the record's id
   * @param reader             reads a property by name as a string, {@code null} when it cannot
   * @param derivedUnavailable {@code action → reason} the customization derived; may be {@code null}
   */
  McpActionAvailabilityContext(String recordId, UnaryOperator<String> reader,
      Map<String, String> derivedUnavailable) {
    this.recordId = recordId;
    this.reader = reader;
    this.derivedUnavailable = derivedUnavailable == null ? Collections.emptyMap()
        : derivedUnavailable;
  }

  /**
   * Resolves the record and what its customization derives about it.
   *
   * @param sfEntity  the entity the schema call is about
   * @param dalEntity its DAL entity
   * @param recordId  the id the caller passed
   * @return the context, or {@code null} when the record does not exist for this role
   */
  static McpActionAvailabilityContext forRecord(SFEntity sfEntity, Entity dalEntity,
      String recordId) {
    BaseOBObject subject = OBDal.getInstance().get(dalEntity.getName(), recordId);
    if (subject == null || !isVisibleToRole(subject)) {
      return null;
    }
    return new McpActionAvailabilityContext(recordId, propertyReader(dalEntity, subject),
        McpDeclaredActions.unavailableFor(sfEntity, subject));
  }

  /**
   * Whether the caller's role may see the record: its client is among the role's readable clients
   * and its organisation among the readable organisations. The router runs in administrator mode,
   * which switches those checks off, so without this a role that cannot see a document's
   * organisation could read its state through {@code availableNow} while a missing id answered 404
   * — an existence oracle. A record that fails this is answered exactly like a missing one.
   * Structural: it reads the generic client/organisation of the record, never an entity.
   */
  static boolean isVisibleToRole(BaseOBObject subject) {
    OBContext context = OBContext.getOBContext();
    if (subject instanceof ClientEnabled && !contains(context.getReadableClients(),
        ((ClientEnabled) subject).getClient())) {
      return false;
    }
    return !(subject instanceof OrganizationEnabled) || contains(context.getReadableOrganizations(),
        ((OrganizationEnabled) subject).getOrganization());
  }

  private static boolean contains(String[] readable, BaseOBObject owner) {
    if (owner == null) {
      return false;
    }
    String id = String.valueOf(owner.getId());
    for (String candidate : readable) {
      if (id.equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads a record property as a string: a foreign key as the referenced id, anything else through
   * {@code String.valueOf}. Structural: the property is whatever name a declaration asks for.
   */
  private static UnaryOperator<String> propertyReader(Entity dalEntity, BaseOBObject subject) {
    return property -> {
      if (StringUtils.isBlank(property) || !dalEntity.hasProperty(property)) {
        return null;
      }
      try {
        Object value = subject.get(property);
        if (value instanceof BaseOBObject) {
          return ((BaseOBObject) value).getId().toString();
        }
        return value == null ? null : String.valueOf(value);
      } catch (RuntimeException e) {
        log.debug("Could not read property {} for the availability view: {}", property,
            e.getMessage());
        return null;
      }
    };
  }

  /** @return the record's id */
  String getRecordId() {
    return recordId;
  }

  /** @return reads a record property by name; {@code null} when it cannot be read */
  UnaryOperator<String> getReader() {
    return reader;
  }

  /**
   * The values of {@code action} the customization rules out for this record, each with its
   * reason. A derived key of the form {@code action:value} rules out ONE value of a list-backed
   * action — Reactivate on an order with linked documents — without claiming the whole action
   * (Close still applies).
   *
   * @param action the action, under any of its names
   * @return {@code value → reason}; empty when none
   */
  Map<String, String> derivedValueReasonsOf(String action) {
    Map<String, String> out = new LinkedHashMap<>();
    String prefix = action + NeoActionAvailability.VALUE_KEY_SEPARATOR;
    for (Map.Entry<String, String> entry : derivedUnavailable.entrySet()) {
      if (entry.getKey().startsWith(prefix)) {
        out.put(entry.getKey().substring(prefix.length()), entry.getValue());
      }
    }
    return out;
  }

  /**
   * The reason the customization gives for an action not applying to this record.
   *
   * @param action the action, under any of its names
   * @return the reason, or {@code null} when the customization does not rule it out
   */
  String derivedReasonOf(String action) {
    return derivedUnavailable.get(action);
  }
}
