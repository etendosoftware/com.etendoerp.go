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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

/**
 * Stateless engine that evaluates active {@code ETGO_MATCH_RULE} records against a bank-statement
 * line's text and returns the best-matching rule (primary suggestion) plus any lower-priority
 * alternatives.
 *
 * <p>Rules are loaded from the database via raw SQL so no generated-entity compilation is needed.
 * The evaluation order is ascending {@code priority} (lower value = higher importance); the first
 * rule whose pattern matches wins. Ties in priority are broken by insertion order (DB fetch order).
 *
 * <p>Usage:
 * <pre>
 *   List&lt;MatchRuleEngine.Rule&gt; rules = MatchRuleEngine.loadRules(conn, accountId);
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

  /**
   * Loads active rules for the given account, ordered by ascending priority.
   * When {@code accountId} is blank, rules scoped to any account are returned (global).
   * When a specific {@code accountId} is provided, returns rules where
   * {@code fin_financial_account_id IS NULL} (global) OR equals the given account, so both
   * global and account-specific rules are evaluated.
   */
  private static final String LOAD_RULES_SQL =
      "SELECT mr.etgo_match_rule_id,"
          + "       mr.name,"
          + "       mr.priority,"
          + "       mr.textcondition,"
          + "       mr.textpattern,"
          + "       mr.c_glitem_id,"
          + "       mr.c_bpartner_id,"
          + "       mr.etgo_transaction_type_id,"
          + "       mr.c_project_id,"
          + "       mr.c_costcenter_id,"
          + "       mr.m_product_id,"
          + "       mr.matchcount"
          + "  FROM etgo_match_rule mr"
          + " WHERE mr.isactive = 'Y'"
          + "   AND (mr.fin_financial_account_id IS NULL OR mr.fin_financial_account_id = ?)"
          + " ORDER BY mr.priority ASC, mr.etgo_match_rule_id ASC"; // NOSONAR java:S2077 — built from constants only

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Loads active rules from the database.
   *
   * @param conn      the JDBC connection (from {@code OBDal.getInstance().getConnection()})
   * @param accountId the financial account id, or blank to load only global rules
   * @return ordered list of rules (ascending priority)
   * @throws Exception if the query fails
   */
  static List<Rule> loadRules(Connection conn, String accountId) throws Exception {
    List<Rule> rules = new ArrayList<>();
    String effectiveAccountId = StringUtils.isBlank(accountId) ? "" : accountId;
    try (PreparedStatement ps = conn.prepareStatement(LOAD_RULES_SQL)) {
      ps.setString(1, effectiveAccountId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rules.add(new Rule(
              rs.getString("etgo_match_rule_id"),
              StringUtils.trimToEmpty(rs.getString("name")),
              rs.getInt("priority"),
              StringUtils.trimToEmpty(rs.getString("textcondition")),
              StringUtils.trimToEmpty(rs.getString("textpattern")),
              rs.getString("c_glitem_id"),
              rs.getString("c_bpartner_id"),
              rs.getString("etgo_transaction_type_id"),
              rs.getString("c_project_id"),
              rs.getString("c_costcenter_id"),
              rs.getString("m_product_id"),
              rs.getLong("matchcount")));
        }
      }
    }
    return rules;
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
        String glItemId, String bpartnerId, String transactionTypeId,
        String projectId, String costCenterId, String productId, long matchCount) {
      this.id = id;
      this.name = name;
      this.priority = priority;
      this.textCondition = textCondition;
      this.textPattern = textPattern;
      this.glItemId = glItemId;
      this.bpartnerId = bpartnerId;
      this.transactionTypeId = transactionTypeId;
      this.projectId = projectId;
      this.costCenterId = costCenterId;
      this.productId = productId;
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
