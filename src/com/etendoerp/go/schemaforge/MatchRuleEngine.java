/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.MatchRule;

/**
 * Stateless engine that evaluates active {@code ETGO_MATCH_RULE} records against a bank-statement
 * line's text and returns the best-matching rule (primary suggestion) plus any lower-priority
 * alternatives.
 *
 * <p>Rules are loaded through the DAL ({@link #loadRules(String)}), which scopes them to the current
 * tenant. The evaluation order is ascending {@code priority} (lower value = higher importance); the
 * first rule whose pattern matches wins. Ties in priority are broken by rule id.
 *
 * <p>Usage:
 * <pre>
 *   List&lt;MatchRuleEngine.Rule&gt; rules = MatchRuleEngine.loadRules(accountId);
 *   MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate(description, reference, partner, rules);
 *   if (result.isMatched()) { ... }
 * </pre>
 *
 * <p>Rules whose {@code textCondition = 'R'} (Regex) are evaluated under a
 * {@value #REGEX_TIMEOUT_MS} ms cap identical to the guard in {@code MatchRuleHandler}, so
 * catastrophic patterns stored in the DB cannot hang the engine at query time.
 *
 * <p>Per the functional document §6.2, the caller is responsible for skipping lines that have an
 * associated invoice (i.e. already matched by the standard algorithm); this class evaluates
 * whatever lines it receives.
 */
final class MatchRuleEngine {

  private static final Logger log = LogManager.getLogger(MatchRuleEngine.class);

  /** Text condition constants (mirror of the AD list reference values). */
  static final String COND_CONTAINS = "C";
  static final String COND_STARTS = "S";
  static final String COND_REGEX = "R";

  /** Cap for regex evaluation at runtime. Mirrors {@code MatchRuleHandler.REGEX_TIMEOUT_MS}. */
  private static final long REGEX_TIMEOUT_MS = 200L;

  private MatchRuleEngine() {
  }

  // ---------------------------------------------------------------------------
  // SQL
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Loads the active rules applicable to {@code accountId}, ordered by ascending priority.
   *
   * <p>A rule with no financial account is "global" — it applies to every account <b>of its own
   * tenant</b>. When {@code accountId} is blank only those global rules are returned.
   *
   * <p><b>Tenant scoping (ETP-4950).</b> This runs through the DAL on purpose. Until this change it
   * was a raw JDBC {@code SELECT} whose {@code WHERE} filtered on {@code isactive} and the financial
   * account but <b>not</b> on {@code ad_client_id} / {@code ad_org_id} — so a global rule of ANY
   * tenant was loaded for EVERY account of EVERY tenant, and Automatch happily matched statement
   * lines against a rule the user could not even see (the window's list goes through the generic NEO
   * CRUD, which is DAL-backed and therefore was correctly isolated all along). {@link OBCriteria}
   * adds {@code client.id in (readableClients)} and {@code organization.id in (readableOrganizations)}
   * itself, so the filter cannot be forgotten by a caller, and it is applied even under
   * {@code OBContext.setAdminMode(true)} — which this whole reconciliation path runs in — because the
   * admin-mode guard in {@code OBCriteria} only skips the entity-access check, not these predicates.
   *
   * <p>Reading a catalog of this module through the DAL is also what every other entity here does
   * ({@code SFSpec}, {@code SFEntity}, {@code SFField}, {@code ETGOTransactionType}, …); the raw-JDBC
   * queries in this package are the multi-join reporting ones over Core tables.
   *
   * @param accountId the financial account id, or blank to load only global rules
   * @return ordered list of rules (ascending priority, id as tie-breaker)
   */
  static List<Rule> loadRules(String accountId) {
    OBCriteria<MatchRule> criteria = OBDal.getInstance().createCriteria(MatchRule.class);
    criteria.add(Restrictions.eq(MatchRule.PROPERTY_ACTIVE, true));
    if (StringUtils.isBlank(accountId)) {
      criteria.add(Restrictions.isNull(MatchRule.PROPERTY_FINANCIALACCOUNT));
    } else {
      criteria.add(Restrictions.or(
          Restrictions.isNull(MatchRule.PROPERTY_FINANCIALACCOUNT),
          Restrictions.eq(MatchRule.PROPERTY_FINANCIALACCOUNT + ".id", accountId)));
    }
    criteria.addOrderBy(MatchRule.PROPERTY_PRIORITY, true);
    criteria.addOrderBy(MatchRule.PROPERTY_ID, true);

    List<Rule> rules = new ArrayList<>();
    for (MatchRule row : criteria.list()) {
      rules.add(toRule(row));
    }
    return rules;
  }

  /** Maps a persisted rule onto the immutable value object the engine evaluates. */
  private static Rule toRule(MatchRule row) {
    RuleOptions opts = new RuleOptions(
        idOf(row.getAccountingConcept()),
        idOf(row.getBusinessPartner()),
        idOf(row.getTransactionType()),
        idOf(row.getProject()),
        idOf(row.getCostCenter()),
        idOf(row.getProduct()));
    return new Rule(
        row.getId(),
        StringUtils.trimToEmpty(row.getName()),
        row.getPriority() == null ? 0 : row.getPriority().intValue(),
        StringUtils.trimToEmpty(row.getTextCondition()),
        StringUtils.trimToEmpty(row.getTextPattern()),
        opts,
        row.getMatchCount() == null ? 0L : row.getMatchCount());
  }

  /** Id of an optional FK, or {@code null} when it is not set. */
  private static String idOf(BaseOBObject reference) {
    return reference == null ? null : (String) reference.getId();
  }

  /**
   * Evaluates the ordered list of rules against the bank-statement line text fields.
   * The first matching rule becomes the primary suggestion; all subsequent matches are
   * returned as alternatives.
   *
   * @param description  the unified description (from {@code BankStatementsSupport.descriptionExpr()})
   * @param reference    the reference number of the line (may be blank)
   * @param partnerName  the counter-party name on the line (may be blank)
   * @param rules        the pre-loaded, priority-ordered rules to evaluate
   * @return a {@link MatchResult}; {@link MatchResult#isMatched()} is {@code false} when no rule matches
   */
  static MatchResult evaluate(String description, String reference, String partnerName,
      List<Rule> rules) {
    String combined = buildSearchText(description, reference, partnerName);
    Rule primary = null;
    List<Rule> alternatives = new ArrayList<>();
    for (Rule rule : rules) {
      if (matches(rule, combined)) {
        if (primary == null) {
          primary = rule;
        } else {
          alternatives.add(rule);
        }
      }
    }
    return new MatchResult(primary, Collections.unmodifiableList(alternatives));
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Builds the concatenated text used for pattern matching. */
  static String buildSearchText(String description, String reference, String partnerName) {
    StringBuilder sb = new StringBuilder();
    if (StringUtils.isNotBlank(description)) {
      sb.append(description);
    }
    if (StringUtils.isNotBlank(reference)) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(reference);
    }
    if (StringUtils.isNotBlank(partnerName)) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(partnerName);
    }
    return sb.toString().toLowerCase();
  }

  /** Returns {@code true} when the rule's pattern matches the combined search text. */
  static boolean matches(Rule rule, String lowerText) {
    if (StringUtils.isBlank(lowerText) || StringUtils.isBlank(rule.textPattern)) {
      return false;
    }
    String pattern = rule.textPattern.toLowerCase();
    switch (rule.textCondition) {
      case COND_CONTAINS:
        return lowerText.contains(pattern);
      case COND_STARTS:
        return lowerText.startsWith(pattern);
      case COND_REGEX:
        return matchesRegex(rule.textPattern, lowerText);
      default:
        log.warn("Unknown textCondition '{}' on rule {}", rule.textCondition, rule.id);
        return false;
    }
  }

  /**
   * Evaluates a regex pattern against text under a {@value #REGEX_TIMEOUT_MS} ms cap.
   * Returns {@code false} on timeout, compile error, or interruption — never throws.
   */
  static boolean matchesRegex(String pattern, String text) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Boolean> future = executor.submit((Callable<Boolean>) () -> {
      Pattern compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
      return compiled.matcher(text).find();
    });
    try {
      return Boolean.TRUE.equals(future.get(REGEX_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn("Regex evaluation timeout for pattern '{}' — treating as no match", pattern);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (!(cause instanceof PatternSyntaxException)) {
        log.warn("Regex evaluation error for pattern '{}': {}", pattern, cause.getMessage());
      }
      return false;
    } finally {
      executor.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------
  // Value objects
  // ---------------------------------------------------------------------------

  /**
   * An active matching rule loaded from {@code ETGO_MATCH_RULE}.
   * All optional FK fields (glItemId, bpartnerId, etc.) may be {@code null}.
   */
  /**
   * Optional FK references attached to a rule (business partner, transaction type, dimensions).
   * Grouped into a value object so {@link Rule}'s constructor stays within the 7-parameter limit.
   */
  /**
   * Optional FK references attached to a rule (GL item, business partner, dimensions).
   * Grouped so {@link Rule}'s constructor stays within the 7-parameter limit.
   */
  static final class RuleOptions {
    final String glItemId;
    final String bpartnerId;
    final String transactionTypeId;
    final String projectId;
    final String costCenterId;
    final String productId;

    RuleOptions(String glItemId, String bpartnerId, String transactionTypeId,
        String projectId, String costCenterId, String productId) {
      this.glItemId = glItemId;
      this.bpartnerId = bpartnerId;
      this.transactionTypeId = transactionTypeId;
      this.projectId = projectId;
      this.costCenterId = costCenterId;
      this.productId = productId;
    }
  }

  static final class Rule {
    final String id;
    final String name;
    final int priority;
    final String textCondition;
    final String textPattern;
    final String glItemId;
    final String bpartnerId;
    final String transactionTypeId;
    final String projectId;
    final String costCenterId;
    final String productId;
    final long matchCount;

    Rule(String id, String name, int priority, String textCondition, String textPattern,
        RuleOptions options, long matchCount) {
      this.id = id;
      this.name = name;
      this.priority = priority;
      this.textCondition = textCondition;
      this.textPattern = textPattern;
      this.glItemId = options != null ? options.glItemId : null;
      this.bpartnerId = options != null ? options.bpartnerId : null;
      this.transactionTypeId = options != null ? options.transactionTypeId : null;
      this.projectId = options != null ? options.projectId : null;
      this.costCenterId = options != null ? options.costCenterId : null;
      this.productId = options != null ? options.productId : null;
      this.matchCount = matchCount;
    }
  }

  /** Result of evaluating a single bank-statement line against the rule set. */
  static final class MatchResult {
    /** The highest-priority matching rule, or {@code null} if none matched. */
    final Rule primary;
    /** Lower-priority rules that also matched (never includes {@code primary}). */
    final List<Rule> alternatives;

    MatchResult(Rule primary, List<Rule> alternatives) {
      this.primary = primary;
      this.alternatives = alternatives;
    }

    boolean isMatched() {
      return primary != null;
    }
  }
}
