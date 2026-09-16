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

import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;

import com.etendoerp.go.schemaforge.data.BillingResource;

/**
 * Validates a {@link BillingResource} catalog row when it is saved, so a misconfigured
 * resource is a configuration-time error with a clear message rather than a nightly job
 * that fails at 02:00 or, worse, silently counts nothing.
 *
 * <p>The checks are deliberately whole-row rather than per-field: the required descriptor
 * depends on the counting mode, so only the mode tells us which fields must be present and
 * which must be empty.
 *
 * <p>Pure validation with no persistence, so it is unit-testable; the observer that calls
 * it on save is a thin wrapper.
 */
public final class UsageResourceValidator {

  /** Declarative HQL: count rows of an entity, by a date property, with a restriction. */
  public static final String MODE_DECLARATIVE = "D";
  /** Named strategy: resolve a {@link UsageResourceCounter} by CDI qualifier. */
  public static final String MODE_STRATEGY = "S";

  private UsageResourceValidator() {
  }

  /**
   * @param resource the catalog row about to be saved
   * @throws IllegalArgumentException with a message naming the offending field
   */
  public static void validate(BillingResource resource) {
    if (resource == null) {
      throw new IllegalArgumentException("Billing resource is required");
    }
    String mode = resource.getCountingMode();
    if (MODE_DECLARATIVE.equals(mode)) {
      validateDeclarative(resource);
    } else if (MODE_STRATEGY.equals(mode)) {
      validateStrategy(resource);
    } else {
      throw new IllegalArgumentException(
          "Unknown counting mode '" + mode + "'; expected '" + MODE_DECLARATIVE + "' or '"
              + MODE_STRATEGY + "'");
    }
  }

  /**
   * A declarative row must name an entity that exists in the runtime model and a property on
   * that entity that exists and actually holds a date. Both are resolved against
   * {@link ModelProvider}, so a typo is caught here instead of producing an HQL parse error
   * inside the scheduled job.
   */
  private static void validateDeclarative(BillingResource resource) {
    String entityName = resource.getCountedEntity();
    String dateProperty = resource.getDateProperty();

    if (StringUtils.isBlank(entityName)) {
      throw new IllegalArgumentException(
          "Counted Entity is required when Counting Mode is Declarative HQL");
    }
    if (StringUtils.isBlank(dateProperty)) {
      throw new IllegalArgumentException(
          "Date Property is required when Counting Mode is Declarative HQL");
    }
    if (StringUtils.isNotBlank(resource.getStrategyQualifier())) {
      throw new IllegalArgumentException(
          "Strategy Qualifier must be empty when Counting Mode is Declarative HQL");
    }

    Entity entity = resolveEntity(entityName);
    Property property = resolveDateProperty(entity, dateProperty);
    requireDateType(entityName, dateProperty, property);

    // Rejects unbalanced parentheses, statement terminators and comment markers, and is the
    // same composition the job will run, so a fragment that cannot compose fails here.
    UsageQueryComposer.composeGroupedCount(entityName, dateProperty,
        resource.getHQLRestriction());
  }

  /**
   * A strategy row must name a qualifier that some deployed {@link UsageResourceCounter}
   * actually carries. Without this check a typo produces a resource that is never counted
   * and reports nothing, which is indistinguishable from genuinely zero usage.
   */
  private static void validateStrategy(BillingResource resource) {
    String qualifier = resource.getStrategyQualifier();
    if (StringUtils.isBlank(qualifier)) {
      throw new IllegalArgumentException(
          "Strategy Qualifier is required when Counting Mode is Named strategy");
    }
    if (StringUtils.isNotBlank(resource.getCountedEntity())
        || StringUtils.isNotBlank(resource.getDateProperty())
        || StringUtils.isNotBlank(resource.getHQLRestriction())) {
      throw new IllegalArgumentException(
          "Counted Entity, Date Property and HQL Restriction must be empty when Counting Mode"
              + " is Named strategy; the strategy owns its own counting rule");
    }
    if (!UsageCounterLookup.isDeployed(qualifier)) {
      throw new IllegalArgumentException("No UsageResourceCounter is deployed with @Named(\""
          + qualifier + "\"). Deployed qualifiers: " + UsageCounterLookup.deployedQualifiers()
          + ". Note that a counter annotated with a normal scope such as @ApplicationScoped is"
          + " silently invisible here: use @Named alone.");
    }
  }

  private static Entity resolveEntity(String entityName) {
    Entity entity = ModelProvider.getInstance().getEntity(entityName, false);
    if (entity == null) {
      throw new IllegalArgumentException("Counted Entity '" + entityName
          + "' does not exist in the runtime model. Use the DAL entity name, for example"
          + " 'Invoice', not the database table name.");
    }
    return entity;
  }

  private static Property resolveDateProperty(Entity entity, String dateProperty) {
    if (!entity.hasProperty(dateProperty)) {
      throw new IllegalArgumentException("Date Property '" + dateProperty
          + "' does not exist on entity '" + entity.getName() + "'");
    }
    return entity.getProperty(dateProperty, false);
  }

  private static void requireDateType(String entityName, String dateProperty,
      Property property) {
    if (property == null || !property.isPrimitive()) {
      throw new IllegalArgumentException("Date Property '" + dateProperty + "' on entity '"
          + entityName + "' is not a simple column, so it cannot be a counting date");
    }
    Class<?> type = property.getPrimitiveObjectType();
    if (type == null || !Date.class.isAssignableFrom(type)) {
      throw new IllegalArgumentException("Date Property '" + dateProperty + "' on entity '"
          + entityName + "' holds " + (type == null ? "an unknown type" : type.getSimpleName())
          + ", not a date; counting buckets by it would be meaningless");
    }
  }
}
