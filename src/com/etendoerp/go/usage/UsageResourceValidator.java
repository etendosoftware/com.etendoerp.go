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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.QueryTimeoutException;
import org.hibernate.query.Query;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
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

  private static final Logger log = LogManager.getLogger(UsageResourceValidator.class);

  /**
   * How long the save-time probe may run. A fragment that cannot count a single day within
   * this is far too expensive to run nightly across every tenant, so timing out is the
   * correct answer rather than a limitation.
   */
  private static final int PROBE_TIMEOUT_SECONDS = 10;

  /** Declarative HQL: count rows of an entity, by a date property, with a restriction. */
  public static final String MODE_DECLARATIVE = "D";
  /** Named strategy: resolve a {@link UsageResourceCounter} by CDI qualifier. */
  public static final String MODE_STRATEGY = "S";

  /** Prefix shared by the Date Property validation messages. */
  private static final String DATE_PROPERTY_PREFIX = "Date Property '";

  private UsageResourceValidator() {
  }

  /**
   * Validates the row and, for a declarative resource, runs the composed query once over a
   * bounded window to record what it costs.
   *
   * <p>The probe is what turns "this fragment would table-scan every tenant nightly" into
   * something visible at configuration time. A fragment that cannot even parse also fails
   * here rather than at 02:00.
   *
   * <p><b>The returned stamp is the authoritative result, not the setters this method also
   * calls.</b> When the caller is a persistence observer, Hibernate has already snapshotted
   * the row's state by the time the event fires, so a plain setter is silently discarded and
   * the columns persist as null — see {@link BillingResourceEventHandler}, which writes the
   * stamp through {@code event.setCurrentState} instead. The setters remain for callers
   * outside a flush, such as a backfill re-validating the catalog.
   *
   * @param resource the catalog row to validate and probe
   * @return when the row was validated, and how long the probe took for a declarative row
   */
  public static ProbeStamp validateAndProbe(BillingResource resource) {
    validate(resource);
    if (!MODE_DECLARATIVE.equals(resource.getCountingMode())) {
      Date validatedAt = new Date();
      resource.setLastValidated(validatedAt);
      return new ProbeStamp(validatedAt, null);
    }
    OBContext.setAdminMode(false);
    try {
      String hql = UsageQueryComposer.composeGroupedCount(resource.getCountedEntity(),
          resource.getDateProperty(), resource.getHQLRestriction());
      Query<Object[]> query = compile(resource, hql);
      Date today = UsageDayRange.startOfDay(new Date());
      query.setParameter(UsageQueryComposer.PARAM_DAY_START,
          UsageDayRange.minusDays(today, 1));
      query.setParameter(UsageQueryComposer.PARAM_DAY_END, today);
      // No setMaxResults: timing the first group would measure neither the nightly cost nor
      // the same query plan, and this number exists precisely to predict the nightly cost.
      // Bounded instead, so a catastrophically expensive fragment is REJECTED here rather
      // than hanging the save it is being validated by — which would be the worst case of
      // exactly the problem the probe is meant to surface.
      query.setTimeout(PROBE_TIMEOUT_SECONDS);
      long elapsed = execute(query);
      Date validatedAt = new Date();
      resource.setLastValidated(validatedAt);
      resource.setLastValidationMs(elapsed);
      log.debug("Resource '{}' probe took {} ms", resource.getSearchKey(), elapsed);
      return new ProbeStamp(validatedAt, elapsed);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Compiles the composed query, reporting a fragment that cannot parse as what it is.
   *
   * <p>Kept separate from {@link #execute} because the two failures have nothing to do with
   * each other and lead the reader somewhere different: this one means "the fragment is
   * wrong, here is the property that does not exist", while a timeout means "the fragment is
   * valid but too expensive". Reporting a typo as a performance problem sends whoever is
   * fixing it looking for an index.
   */
  private static Query<Object[]> compile(BillingResource resource, String hql) {
    try {
      return OBDal.getInstance().getSession().createQuery(hql, Object[].class);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("The HQL Restriction is not valid for entity '"
          + UsageMessages.atSafe(resource.getCountedEntity()) + "': "
          + UsageMessages.atSafe(rootCauseMessage(e))
          + ". Use DAL property names with the alias 'e', for example"
          + " \"e.salesTransaction = true\". The composed query was: "
          + UsageMessages.atSafe(hql), e);
    }
  }

  /**
   * Runs the probe once and returns how long it took.
   *
   * @throws IllegalArgumentException if it exceeds {@link #PROBE_TIMEOUT_SECONDS}, which is
   *     the cost guard doing its job, or fails for any other reason at execution time
   */
  private static long execute(Query<Object[]> query) {
    long startedAt = System.currentTimeMillis();
    try {
      query.list();
    } catch (QueryTimeoutException e) {
      throw new IllegalArgumentException("The counting query took longer than "
          + PROBE_TIMEOUT_SECONDS + "s to count a single day, which is too expensive to"
          + " schedule nightly across every tenant. Narrow the restriction, or add an index"
          + " on the date property it filters.", e);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("The counting query compiled but could not be run: "
          + UsageMessages.atSafe(rootCauseMessage(e)), e);
    }
    return System.currentTimeMillis() - startedAt;
  }

  /**
   * The deepest message in the chain. Hibernate wraps a parse failure several layers deep and
   * only the innermost one names the offending property, which is the whole point of the
   * message.
   */
  private static String rootCauseMessage(Throwable error) {
    Throwable cause = error;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getMessage();
  }

  /**
   * Validates a catalog row, rejecting anything that could not be counted safely.
   *
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
          "Unknown counting mode '" + UsageMessages.atSafe(mode) + "'; expected '"
              + MODE_DECLARATIVE + "' or '"
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
      // No '@' anywhere in this text on purpose: Openbravo treats '@' as its message
      // parameter delimiter, so a message containing one is parsed as a placeholder and can
      // reach the user blank. Naming the annotations without the at-sign keeps the advice
      // while letting the message survive translateError.
      throw new IllegalArgumentException("No UsageResourceCounter is deployed with the"
          + " qualifier \"" + UsageMessages.atSafe(qualifier) + "\". Deployed qualifiers: "
          + UsageMessages.atSafe(UsageCounterLookup.deployedQualifiers())
          + ". Note that a counter annotated with a normal scope such as ApplicationScoped is"
          + " silently invisible here: use the Named annotation alone.");
    }
  }

  private static Entity resolveEntity(String entityName) {
    Entity entity = ModelProvider.getInstance().getEntity(entityName, false);
    if (entity == null) {
      throw new IllegalArgumentException("Counted Entity '" + UsageMessages.atSafe(entityName)
          + "' does not exist in the runtime model. Use the DAL entity name, for example"
          + " 'Invoice', not the database table name.");
    }
    return entity;
  }

  private static Property resolveDateProperty(Entity entity, String dateProperty) {
    if (!entity.hasProperty(dateProperty)) {
      throw new IllegalArgumentException(DATE_PROPERTY_PREFIX + UsageMessages.atSafe(dateProperty)
          + "' does not exist on entity '" + UsageMessages.atSafe(entity.getName()) + "'");
    }
    return entity.getProperty(dateProperty, false);
  }

  private static void requireDateType(String entityName, String dateProperty,
      Property property) {
    if (property == null || !property.isPrimitive()) {
      throw new IllegalArgumentException(DATE_PROPERTY_PREFIX + UsageMessages.atSafe(dateProperty)
          + "' on entity '" + UsageMessages.atSafe(entityName)
          + "' is not a simple column, so it cannot be a counting date");
    }
    Class<?> type = property.getPrimitiveObjectType();
    if (type == null || !Date.class.isAssignableFrom(type)) {
      throw new IllegalArgumentException(DATE_PROPERTY_PREFIX + UsageMessages.atSafe(dateProperty)
          + "' on entity '" + UsageMessages.atSafe(entityName)
          + "' holds " + (type == null ? "an unknown type" : type.getSimpleName())
          + ", not a date; counting buckets by it would be meaningless");
    }
  }

  /**
   * What a save-time validation established: when it ran, and — for a declarative row — how
   * many milliseconds its counting query took over a single day.
   *
   * <p>Returned rather than only written onto the entity because the observer that calls this
   * runs inside a flush, where a plain setter does not survive.
   */
  public static final class ProbeStamp {
    private final Date validatedAt;
    private final Long elapsedMs;

    ProbeStamp(Date validatedAt, Long elapsedMs) {
      this.validatedAt = validatedAt;
      this.elapsedMs = elapsedMs;
    }

    public Date getValidatedAt() {
      return validatedAt;
    }

    /** @return the probe duration, or {@code null} for a strategy row, which is not probed */
    public Long getElapsedMs() {
      return elapsedMs;
    }
  }
}
