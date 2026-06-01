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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;

/**
 * In-memory safety store used until a persistent DAL-backed store is configured.
 */
public class InMemoryEmailSafetyStore implements EmailSafetyStore {

  private static final String KILL_SWITCH_GLOBAL = killSwitchKey(EmailThrottleRule.SCOPE_GLOBAL,
      "global");

  private final LongSupplier clock;
  private final Map<String, EmailAuditRecord> sentByIdempotencyKey = new HashMap<>();
  private final Map<String, WindowCounter> throttleCounters = new HashMap<>();
  private final Set<String> killSwitches = new HashSet<>();
  private final List<EmailAuditRecord> auditRecords = new ArrayList<>();

  /**
   * Creates a store using the system clock.
   */
  public InMemoryEmailSafetyStore() {
    this(System::currentTimeMillis);
  }

  /**
   * Creates a store using an explicit clock.
   *
   * @param clock epoch-millisecond clock
   */
  public InMemoryEmailSafetyStore(LongSupplier clock) {
    this.clock = Objects.requireNonNull(clock, "Email safety store clock cannot be null");
  }

  /**
   * Disables all transactional email sends.
   */
  public synchronized void disableGlobal() {
    killSwitches.add(KILL_SWITCH_GLOBAL);
  }

  /**
   * Disables transactional email sends for a tenant/client.
   *
   * @param tenantId tenant or client id
   */
  public synchronized void disableTenant(String tenantId) {
    addKillSwitch(EmailThrottleRule.SCOPE_TENANT, tenantId);
  }

  /**
   * Disables transactional email sends for a provider template.
   *
   * @param template provider template
   */
  public synchronized void disableTemplate(String template) {
    addKillSwitch(EmailThrottleRule.SCOPE_TEMPLATE, template);
  }

  /**
   * Returns the audit records captured by this store.
   *
   * @return audit record snapshot
   */
  public synchronized List<EmailAuditRecord> getAuditRecords() {
    return new ArrayList<>(auditRecords);
  }

  @Override
  public synchronized EmailKillSwitchResult checkKillSwitch(EmailSendContext context) {
    if (killSwitches.contains(KILL_SWITCH_GLOBAL)) {
      return EmailKillSwitchResult.suppressed(EmailThrottleRule.SCOPE_GLOBAL, "global",
          "Transactional email is disabled globally");
    }
    String tenantId = context.getTenantId();
    if (isKillSwitchEnabled(EmailThrottleRule.SCOPE_TENANT, tenantId)) {
      return EmailKillSwitchResult.suppressed(EmailThrottleRule.SCOPE_TENANT, tenantId,
          "Transactional email is disabled for this tenant");
    }
    String template = context.getTemplate();
    if (isKillSwitchEnabled(EmailThrottleRule.SCOPE_TEMPLATE, template)) {
      return EmailKillSwitchResult.suppressed(EmailThrottleRule.SCOPE_TEMPLATE, template,
          "Transactional email is disabled for this template");
    }
    return EmailKillSwitchResult.allowed();
  }

  @Override
  public synchronized Optional<EmailAuditRecord> findSentByIdempotencyKey(EmailSendContext context,
      String idempotencyKey) {
    return findSentAudit(context.getContractName(), context.getTenantId(), idempotencyKey);
  }

  @Override
  public synchronized Optional<EmailAuditRecord> findSentAudit(String contractName,
      String tenantId, String idempotencyKey) {
    String normalizedKey = StringUtils.trimToNull(idempotencyKey);
    if (normalizedKey == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(sentByIdempotencyKey.get(
        idempotencyIndexKey(contractName, tenantId, normalizedKey)));
  }

  @Override
  public synchronized EmailThrottleResult checkAndIncrement(EmailSendContext context,
      List<EmailThrottleRule> rules) {
    if (rules == null || rules.isEmpty()) {
      return EmailThrottleResult.allowed();
    }
    long now = clock.getAsLong();
    for (EmailThrottleRule rule : rules) {
      String key = StringUtils.trimToNull(rule.resolveKey(context));
      if (key == null) {
        continue;
      }
      WindowCounter counter = counterFor(rule, key, now);
      if (counter.count >= rule.getMaxAttempts()) {
        long retryAt = counter.windowStartMillis + rule.getWindowSeconds() * 1000L;
        int retryAfterSeconds = (int) Math.max(1, (retryAt - now + 999L) / 1000L);
        return EmailThrottleResult.throttled(rule.getScope(), key, retryAfterSeconds);
      }
    }
    for (EmailThrottleRule rule : rules) {
      String key = StringUtils.trimToNull(rule.resolveKey(context));
      if (key != null) {
        counterFor(rule, key, now).count++;
      }
    }
    return EmailThrottleResult.allowed();
  }

  @Override
  public synchronized void recordAudit(EmailAuditRecord auditRecord) {
    Objects.requireNonNull(auditRecord, "Email audit record cannot be null");
    auditRecords.add(auditRecord);
    if (TransactionalEmailService.STATUS_SENT.equals(auditRecord.getStatus())
        && auditRecord.getIdempotencyKey() != null) {
      sentByIdempotencyKey.putIfAbsent(idempotencyIndexKey(auditRecord), auditRecord);
    }
  }

  private synchronized void addKillSwitch(String scope, String key) {
    String normalizedKey = StringUtils.trimToNull(key);
    if (normalizedKey != null) {
      killSwitches.add(killSwitchKey(scope, normalizedKey));
    }
  }

  private synchronized boolean isKillSwitchEnabled(String scope, String key) {
    String normalizedKey = StringUtils.trimToNull(key);
    return normalizedKey != null && killSwitches.contains(killSwitchKey(scope, normalizedKey));
  }

  private WindowCounter counterFor(EmailThrottleRule rule, String key, long now) {
    String counterKey = rule.getScope() + ":" + rule.getMaxAttempts() + ":"
        + rule.getWindowSeconds() + ":" + key;
    WindowCounter counter = throttleCounters.get(counterKey);
    long windowMillis = rule.getWindowSeconds() * 1000L;
    if (counter == null || now - counter.windowStartMillis >= windowMillis) {
      counter = new WindowCounter(now);
      throttleCounters.put(counterKey, counter);
    }
    return counter;
  }

  private static String killSwitchKey(String scope, String key) {
    return scope + ":" + key;
  }

  private static String idempotencyIndexKey(EmailAuditRecord auditRecord) {
    return idempotencyIndexKey(auditRecord.getContractName(), auditRecord.getTenantId(),
        auditRecord.getIdempotencyKey());
  }

  private static String idempotencyIndexKey(String contractName, String tenantId,
      String idempotencyKey) {
    return StringUtils.defaultString(contractName) + "|" + StringUtils.defaultString(tenantId)
        + "|" + idempotencyKey;
  }

  private static final class WindowCounter {
    private final long windowStartMillis;
    private int count;

    private WindowCounter(long windowStartMillis) {
      this.windowStartMillis = windowStartMillis;
    }
  }
}
