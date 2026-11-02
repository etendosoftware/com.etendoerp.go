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

package com.etendoerp.go.usage.quota;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.PlanQuota;

/**
 * ETP-5051 — one active quota row with the catalog descriptor of its billing resource, as plain
 * values. Detached from the DAL on purpose: the evaluator works on this, so it can be tested with no
 * database and holds no entity across a session boundary.
 *
 * <p>Everything a message says about the resource comes from here — its catalog name and unit —
 * never from a per-resource string in code.
 */
public final class QuotaDefinition {

  private final String resourceId;
  private final String resourceKey;
  private final String resourceName;
  private final String unitLabel;
  private final String countingMode;
  private final String countedEntity;
  private final String dateProperty;
  private final String hqlRestriction;
  private final String strategyQualifier;
  private final UsageAggregation aggregation;
  private final Long includedQty;
  private final String enforcementMode;
  private final Long warningThreshold;
  private final ConsumptionSource source;
  private final String warningAlert;
  private final String limitAlert;

  private QuotaDefinition(Builder b) {
    this.resourceId = b.resourceId;
    this.resourceKey = b.resourceKey;
    this.resourceName = b.resourceName;
    this.unitLabel = b.unitLabel;
    this.countingMode = b.countingMode;
    this.countedEntity = b.countedEntity;
    this.dateProperty = b.dateProperty;
    this.hqlRestriction = b.hqlRestriction;
    this.strategyQualifier = b.strategyQualifier;
    this.aggregation = b.aggregation == null ? UsageAggregation.SUM : b.aggregation;
    this.includedQty = b.includedQty;
    this.enforcementMode = QuotaRules.normaliseMode(b.enforcementMode);
    this.warningThreshold = b.warningThreshold;
    this.source = b.source == null ? ConsumptionSource.AGGREGATE : b.source;
    this.warningAlert = b.warningAlert;
    this.limitAlert = b.limitAlert;
  }

  /**
   * Snapshots a quota row and its resource. Tolerates a missing resource or missing values, which
   * a mock or a half-filled row can carry; the stored schema requires them.
   *
   * @param quota the quota row
   * @return the detached definition
   */
  public static QuotaDefinition of(PlanQuota quota) {
    Builder builder = builder()
        .includedQty(quota.getIncludedQty())
        .enforcementMode(quota.getEnforcementMode())
        .warningThreshold(quota.getWarningThreshold())
        .source(ConsumptionSource.fromColumn(quota.getConsumptionSource()))
        .warningAlert(quota.getWarningAlert())
        .limitAlert(quota.getLimitAlert());
    BillingResource resource = quota.getBillingResource();
    if (resource != null) {
      builder.resourceId(resource.getId())
          .resourceKey(resource.getSearchKey())
          .resourceName(resource.getName())
          .unitLabel(resource.getUnitLabel())
          .countingMode(resource.getCountingMode())
          .countedEntity(resource.getCountedEntity())
          .dateProperty(resource.getDateProperty())
          .hqlRestriction(resource.getHQLRestriction())
          .strategyQualifier(resource.getStrategyQualifier())
          .aggregation(UsageAggregation.fromColumn(resource.getAggregation()));
    }
    return builder.build();
  }

  /**
   * A builder, for tests and fixtures.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  public String getResourceId() {
    return resourceId;
  }

  /** @return {@code ETGO_BILLING_RESOURCE.VALUE} */
  public String getResourceKey() {
    return resourceKey;
  }

  /** @return {@code ETGO_BILLING_RESOURCE.NAME}, the catalog display name */
  public String getResourceName() {
    return resourceName;
  }

  /** @return {@code ETGO_BILLING_RESOURCE.UNIT_LABEL}; may be null */
  public String getUnitLabel() {
    return unitLabel;
  }

  /** @return {@code D} (declarative HQL) or {@code S} (named strategy) */
  public String getCountingMode() {
    return countingMode;
  }

  public String getCountedEntity() {
    return countedEntity;
  }

  public String getDateProperty() {
    return dateProperty;
  }

  public String getHqlRestriction() {
    return hqlRestriction;
  }

  public String getStrategyQualifier() {
    return strategyQualifier;
  }

  public UsageAggregation getAggregation() {
    return aggregation;
  }

  /** @return the included quantity; null only on a row the schema would reject */
  public Long getIncludedQty() {
    return includedQty;
  }

  /** @return {@code off}, {@code warn} or {@code block}, normalised */
  public String getEnforcementMode() {
    return enforcementMode;
  }

  /** @return percentage of the included quantity, or null */
  public Long getWarningThreshold() {
    return warningThreshold;
  }

  public ConsumptionSource getSource() {
    return source;
  }

  /**
   * ETP-6060 — how the SPA shows the alert of this quota once the warning threshold is reached.
   *
   * @return {@code pinned}, {@code dismissible} or {@code hidden}; null keeps the SPA's default
   */
  public String getWarningAlert() {
    return warningAlert;
  }

  /**
   * ETP-6060 — how the SPA shows the alert of this quota once the included quantity is reached.
   * Presentation only: a {@code block} quota refuses new records whatever this says.
   *
   * @return {@code pinned}, {@code dismissible} or {@code hidden}; null keeps the SPA's default
   */
  public String getLimitAlert() {
    return limitAlert;
  }

  /** Builds a {@link QuotaDefinition}. */
  public static final class Builder {
    private String resourceId;
    private String resourceKey;
    private String resourceName;
    private String unitLabel;
    private String countingMode;
    private String countedEntity;
    private String dateProperty;
    private String hqlRestriction;
    private String strategyQualifier;
    private UsageAggregation aggregation;
    private Long includedQty;
    private String enforcementMode;
    private Long warningThreshold;
    private ConsumptionSource source;
    private String warningAlert;
    private String limitAlert;

    private Builder() {
    }

    /**
     * Sets the resource id.
     *
     * @param value the resource id
     * @return this builder
     */
    public Builder resourceId(String value) {
      this.resourceId = value;
      return this;
    }

    /**
     * Sets the resource key.
     *
     * @param value the resource key
     * @return this builder
     */
    public Builder resourceKey(String value) {
      this.resourceKey = value;
      return this;
    }

    /**
     * Sets the resource name.
     *
     * @param value the resource name
     * @return this builder
     */
    public Builder resourceName(String value) {
      this.resourceName = value;
      return this;
    }

    /**
     * Sets the unit label.
     *
     * @param value the unit label
     * @return this builder
     */
    public Builder unitLabel(String value) {
      this.unitLabel = value;
      return this;
    }

    /**
     * Sets the counting mode.
     *
     * @param value the counting mode
     * @return this builder
     */
    public Builder countingMode(String value) {
      this.countingMode = value;
      return this;
    }

    /**
     * Sets the counted entity.
     *
     * @param value the counted entity
     * @return this builder
     */
    public Builder countedEntity(String value) {
      this.countedEntity = value;
      return this;
    }

    /**
     * Sets the date property.
     *
     * @param value the date property
     * @return this builder
     */
    public Builder dateProperty(String value) {
      this.dateProperty = value;
      return this;
    }

    /**
     * Sets the hql restriction.
     *
     * @param value the hql restriction
     * @return this builder
     */
    public Builder hqlRestriction(String value) {
      this.hqlRestriction = value;
      return this;
    }

    /**
     * Sets the strategy qualifier.
     *
     * @param value the strategy qualifier
     * @return this builder
     */
    public Builder strategyQualifier(String value) {
      this.strategyQualifier = value;
      return this;
    }

    /**
     * Sets the aggregation.
     *
     * @param value the aggregation
     * @return this builder
     */
    public Builder aggregation(UsageAggregation value) {
      this.aggregation = value;
      return this;
    }

    /**
     * Sets the included qty.
     *
     * @param value the included qty
     * @return this builder
     */
    public Builder includedQty(Long value) {
      this.includedQty = value;
      return this;
    }

    /**
     * Sets the enforcement mode.
     *
     * @param value the enforcement mode
     * @return this builder
     */
    public Builder enforcementMode(String value) {
      this.enforcementMode = value;
      return this;
    }

    /**
     * Sets the warning threshold.
     *
     * @param value the warning threshold
     * @return this builder
     */
    public Builder warningThreshold(Long value) {
      this.warningThreshold = value;
      return this;
    }

    /**
     * Sets the source.
     *
     * @param value the source
     * @return this builder
     */
    public Builder source(ConsumptionSource value) {
      this.source = value;
      return this;
    }

    /**
     * Sets how the warning-threshold alert is shown.
     *
     * @param value {@code pinned}, {@code dismissible}, {@code hidden} or null
     * @return this builder
     */
    public Builder warningAlert(String value) {
      this.warningAlert = value;
      return this;
    }

    /**
     * Sets how the limit-reached alert is shown.
     *
     * @param value {@code pinned}, {@code dismissible}, {@code hidden} or null
     * @return this builder
     */
    public Builder limitAlert(String value) {
      this.limitAlert = value;
      return this;
    }

    /**
     * Builds the definition.
     *
     * @return the definition
     */
    public QuotaDefinition build() {
      return new QuotaDefinition(this);
    }
  }
}
