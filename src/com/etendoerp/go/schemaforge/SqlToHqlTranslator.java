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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;

/**
 * SQL → HQL translation helpers extracted from {@link SelectorQueryBuilder}.
 * Move here: translateSqlToHql, translateFromAliases, resolveFromEntity,
 * translateColumnReferences, translateAliasColumns, splitTopLevelAnd,
 * convertSqlToHql, replaceBareColumnToken, resolveColumnToHql,
 * unwrapSelectFromDual, simplifyConstantCaseExpressions. Also move the
 * SQL_ONLY_FUNCTIONS, SELECT_FROM_DUAL, FROM_WITH_ALIAS and LITERAL_CASE_WHEN
 * patterns if they are only used here.
 */
public final class SqlToHqlTranslator {
  private static final Logger log = LogManager.getLogger(SqlToHqlTranslator.class);

  @SuppressWarnings("java:S5852")
  private static final Pattern REDUNDANT_AND_ONE_EQUALS_ONE =
      Pattern.compile("(?i)\\s*+AND\\s++1=1\\b");

  // Matches @Param@ / @#Param@ placeholders (session-level variables in Etendo), e.g.
  // "@#AD_Client_ID@" or "@AD_Org_ID@". Masked out of convertSqlToHql's input before table/column
  // translation runs — see convertSqlToHql for why.
  private static final Pattern PARAM_PLACEHOLDER = Pattern.compile("@#?\\w+@");

  /**
   * Detects a nested {@code (SELECT ...)} subquery inside a SQL-style clause (e.g. {@code X IN
   * (SELECT ... FROM Fin_Finacc_Paymentmethod WHERE ...)}). This translator only rewrites
   * {@code TABLE.COLUMN} references — it has no notion of a nested {@code FROM} clause — so a
   * segment matching this pattern is dropped by {@link #dropNestedSubqueryClauses} instead of
   * being emitted as broken HQL with the subquery's raw SQL table/column names left untranslated.
   *
   * <p>Package-visible (not {@code private}) so {@link
   * com.etendoerp.go.schemaforge.SelectorValidationResolver}'s AD_Validation-clause guard shares
   * this exact detection instead of maintaining a second copy of the regex — both classes live in
   * {@code com.etendoerp.go.schemaforge}.
   */
  static final Pattern NESTED_SUBQUERY = Pattern.compile("\\(\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);

  private SqlToHqlTranslator() {
  }

  /**
   * Replaces every {@code (SELECT expr FROM DUAL)} occurrence with {@code (expr)}.
   *
   * <p>This is an Oracle-ism: HQL scalar subqueries work without a {@code FROM} clause.
   * Uses a tempered-greedy token to avoid consuming outer {@code EXISTS (SELECT …)}
   * clauses when multiple FROM DUAL subqueries appear inside the same EXISTS block.
   */
  static String unwrapSelectFromDual(String sql) {
    if (sql == null) return null;
    return SelectorQueryBuilder.SELECT_FROM_DUAL.matcher(sql).replaceAll("($1)");
  }

  /**
   * Simplifies constant {@code CASE WHEN} expressions that arise from unwrapping
   * {@code (SELECT expr FROM DUAL)} subqueries after parameter substitution.
   *
   * <p>Pattern: {@code LHS = (CASE WHEN 'LIT1' = 'LIT2' THEN 'Y' ELSE LHS END)}
   * <ul>
   *   <li>If {@code LIT1 == LIT2} (always-true): replaces with {@code LHS = 'Y'}</li>
   *   <li>If {@code LIT1 != LIT2} (always-false, ELSE = LHS = tautology): replaces with
   *       {@code 1=1} and then strips the redundant {@code AND 1=1} condition</li>
   * </ul>
   */
  static String simplifyConstantCaseExpressions(String hql) {
    if (hql == null || !hql.toUpperCase().contains("CASE WHEN")) {
      return hql;
    }
    Matcher m = SelectorQueryBuilder.LITERAL_CASE_WHEN.matcher(hql);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String lhs      = m.group(1);
      String lit1     = m.group(2);
      String lit2     = m.group(3);
      String thenVal  = m.group(4);
      String elseExpr = m.group(5);
      String replacement;
      if (lit1.equalsIgnoreCase(lit2)) {
        // Always-true: CASE evaluates to THEN literal → collapse to direct equality.
        replacement = lhs + " = '" + thenVal + "'";
        log.debug("Simplified always-true CASE for {}: {} = '{}'", lhs, lhs, thenVal);
      } else if (lhs.equalsIgnoreCase(elseExpr)) {
        // Always-false and ELSE is the same property → tautology (x = x) → drop clause.
        replacement = "1=1";
        log.debug("Simplified always-false CASE for {} (LHS=ELSE tautology → 1=1)", lhs);
      } else {
        // Always-false and ELSE is a different property → emit the real comparison.
        replacement = lhs + " = " + elseExpr;
        log.debug("Simplified always-false CASE for {}: real comparison {} = {}", lhs, lhs, elseExpr);
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(sb);
    return REDUNDANT_AND_ONE_EQUALS_ONE.matcher(sb.toString()).replaceAll("");
  }

  /**
   * Translates SQL table names and column names to their HQL equivalents in a validation clause.
   * AD validation rules are written in SQL and may use:
   * - SQL table names (e.g. {@code FIN_FinAcc_PaymentMethod}) instead of HQL entity names
   *   (e.g. {@code FinancialMgmtFinAccPaymentMethod})
   * - SQL column names (e.g. {@code Payin_Allow}) instead of HQL property names
   *   (e.g. {@code payinAllow})
   * - FK column names (e.g. {@code FIN_PaymentMethod_ID}) which need {@code .id} appended
   *   (e.g. {@code paymentMethod.id})
   *
   * {@code FROM DUAL} is removed — HQL scalar subqueries work without a FROM clause.
   *
   * @param clause the SQL validation clause to translate
   * @return the clause rewritten with HQL entity names and property paths
   */
  public static String translateSqlToHql(String clause) {
    clause = unwrapSelectFromDual(clause);
    Map<String, Entity> aliasEntityMap = new HashMap<>();
    String translatedFrom = translateFromAliases(clause, aliasEntityMap);
    return translateColumnReferences(translatedFrom, aliasEntityMap);
  }

  private static String translateFromAliases(String clause, Map<String, Entity> aliasEntityMap) {
    Matcher fromMatcher = SelectorQueryBuilder.FROM_WITH_ALIAS.matcher(clause);
    StringBuffer translated = new StringBuffer();
    while (fromMatcher.find()) {
      Entity entity = resolveFromEntity(fromMatcher.group(1));
      String alias = fromMatcher.group(2);
      if (entity != null) {
        aliasEntityMap.put(alias, entity);
      }
      if (entity != null && !entity.getName().equals(fromMatcher.group(1))) {
        log.debug("Translating SQL table name [{}] → HQL entity [{}]", fromMatcher.group(1), entity.getName());
        fromMatcher.appendReplacement(translated,
            Matcher.quoteReplacement("FROM " + entity.getName() + " " + alias));
      }
    }
    fromMatcher.appendTail(translated);
    return translated.toString();
  }

  private static Entity resolveFromEntity(String tableName) {
    try {
      Entity hqlEntity = ModelProvider.getInstance().getEntity(tableName);
      if (hqlEntity != null) {
        return hqlEntity;
      }
    } catch (Exception e) {
      // Not a valid HQL entity name — fall through to SQL table lookup.
    }
    return ModelProvider.getInstance().getEntityByTableName(tableName);
  }

  private static String translateColumnReferences(String clause, Map<String, Entity> aliasEntityMap) {
    String result = clause;
    for (Map.Entry<String, Entity> entry : aliasEntityMap.entrySet()) {
      result = translateAliasColumns(result, entry.getKey(), entry.getValue());
    }
    return result;
  }

  private static String translateAliasColumns(String clause, String alias, Entity entity) {
    Pattern colRef = Pattern.compile("\\b" + Pattern.quote(alias) + "\\.(\\w+)");
    Matcher colMatcher = colRef.matcher(clause);
    StringBuffer translated = new StringBuffer();
    while (colMatcher.find()) {
      Property prop = entity.getPropertyByColumnName(colMatcher.group(1), false);
      if (prop != null) {
        String hqlRef = prop.isPrimitive()
            ? alias + "." + prop.getName()
            : alias + "." + prop.getName() + ".id";
        log.debug("Translating column [{}] → HQL property [{}]", colMatcher.group(1), hqlRef);
        colMatcher.appendReplacement(translated, Matcher.quoteReplacement(hqlRef));
      }
    }
    colMatcher.appendTail(translated);
    return translated.toString();
  }

  /**
   * Split a SQL WHERE clause into top-level AND segments.
   * Respects parentheses nesting so that "A AND (B OR C AND D) AND E"
   * splits into ["A", "(B OR C AND D)", "E"], not into inner parts.
   */
  static List<String> splitTopLevelAnd(String code) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    String upper = code.toUpperCase();

    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0 && i + 3 < code.length()
          && upper.substring(i, i + 3).equals("AND")
          && (i == 0 || !Character.isLetterOrDigit(code.charAt(i - 1)))
          && !Character.isLetterOrDigit(code.charAt(i + 3))) {
        // Top-level AND (preceded/followed by non-alphanumeric character)
        parts.add(code.substring(start, i).trim());
        start = i + 3;
      }
    }
    // Add the last segment
    String last = code.substring(start).trim();
    if (!last.isEmpty()) {
      parts.add(last);
    }
    return parts;
  }

  /**
   * Drops every top-level AND-segment of {@code sqlClause} that contains a nested {@code (SELECT
   * ...)} subquery (see {@link #NESTED_SUBQUERY}), rejoining whatever segments survive.
   *
   * <p>This is the same strategy {@code SelectorValidationResolver#resolveValidationClause}
   * already applies per-clause to AD_Validation rules: rather than emitting HQL with the
   * subquery's raw SQL table/column names left untranslated (which Hibernate would reject at
   * query-execution time), the offending segment is treated as if it were never configured.
   *
   * @param sqlClause the raw SQL clause, possibly containing multiple top-level AND-segments
   * @return {@code sqlClause} unchanged when blank (nothing to guard); the segments that don't
   *     contain a nested subquery, rejoined with {@code " AND "}; or {@code null} when every
   *     segment was dropped
   */
  private static String dropNestedSubqueryClauses(String sqlClause) {
    if (sqlClause == null || sqlClause.trim().isEmpty()) {
      return sqlClause;
    }
    List<String> clauses = splitTopLevelAnd(sqlClause);
    List<String> safeClauses = new ArrayList<>();
    for (String clause : clauses) {
      if (NESTED_SUBQUERY.matcher(clause).find()) {
        log.debug("Dropping SQLWhereClause segment with nested subquery: {}", clause);
      } else {
        safeClauses.add(clause);
      }
    }
    if (safeClauses.isEmpty()) {
      return null;
    }
    return String.join(SelectorQueryBuilder.SQL_AND, safeClauses);
  }

  /**
   * Convert a SQL-style validation clause to HQL.
   * Replaces TABLE.COLUMN with e.dalProperty, handling FK columns (_ID -> .id).
   * Also converts bare column names (without table prefix) to e.property paths.
   *
   * Example: "C_DocType.DocBaseType IN ('POO')" -> "e.documentType IN ('POO')"
   * Example: "docsubtypeso like 'OB'" -> "e.sOSubType like 'OB'"
   *
   * <p>Public (not package-private) because {@code SelectorDescriptorResolver}
   * (package {@code com.etendoerp.go.schemaforge.selector.meta}) also uses it to translate
   * classic {@code AD_Ref_Table.SQLWhereClause} filters — e.g. {@code C_Tax.Parent_Tax_ID IS
   * NULL} — the same way AD validation rules are translated by
   * {@link com.etendoerp.go.schemaforge.SelectorValidationResolver#resolveValidationFilter}.
   *
   * <p>Any {@code @Param@} / {@code @#Param@} placeholder in {@code sqlClause} is masked out
   * before translation and restored verbatim afterward, so a param name that happens to also be
   * a real column name (e.g. {@code @#AD_Client_ID@} next to an {@code AD_Client_ID} FK column)
   * is never rewritten into something like {@code @#e.client.id@}. That would corrupt the
   * placeholder beyond what the downstream param substitution step can recognize, leaving broken
   * literal text in the final HQL. This matters for the {@code AD_Ref_Table.SQLWhereClause}
   * caller specifically: param substitution runs strictly *after* this method there (see
   * {@code SelectorValidationResolver#resolveObuiselParams}), unlike the AD validation-rule
   * caller, which already substitutes params *before* calling this method — so for that caller
   * the masking is a no-op.
   *
   * <p>Before any translation runs, every top-level AND-segment of {@code sqlClause} is checked
   * against {@link #NESTED_SUBQUERY} via {@link #dropNestedSubqueryClauses} and dropped if it
   * contains a nested {@code (SELECT ...)} — mirroring the guard {@code
   * SelectorValidationResolver#resolveValidationClause} already applies to AD_Validation clauses.
   * Without it, a classic {@code AD_Ref_Table.SQLWhereClause} such as the one on {@code
   * M_Warehouse_ID} ({@code M_Warehouse.AD_Client_ID=@#AD_Client_ID@ AND (select ad.isactive from
   * ad_org ad where ad.ad_org_id = M_Warehouse.AD_Org_ID) = 'Y'}) would have its subquery's raw
   * SQL table/column names ({@code ad_org}, {@code ad.isactive}) pass straight through
   * untranslated, and Hibernate would throw {@code QuerySyntaxException: ad_org is not mapped}
   * the first time a user actually opens that selector — not when this method resolves it.
   *
   * @param sqlClause SQL-style clause using TABLE.COLUMN or bare column references
   * @param targetEntityName HQL entity name the clause is being resolved against
   * @return the clause translated to HQL {@code e.property} paths; {@code null} when every
   *     top-level segment was dropped by the nested-subquery guard (i.e. no filter survives);
   *     returns {@code sqlClause} unchanged if {@code targetEntityName} does not resolve to a
   *     known entity or translation fails
   */
  public static String convertSqlToHql(String sqlClause, String targetEntityName) {
    try {
      Entity targetEntity = ModelProvider.getInstance().getEntity(targetEntityName);
      if (targetEntity == null) {
        return sqlClause;
      }

      String guardedClause = dropNestedSubqueryClauses(sqlClause);
      if (guardedClause == null) {
        return null;
      }

      Map<String, String> maskedParams = new HashMap<>();
      String maskedClause = maskParamPlaceholders(guardedClause, maskedParams);

      String tableName = targetEntity.getTableName();

      // Step 1: Replace TABLE.COLUMN patterns with e.property
      Pattern tableColPattern = Pattern.compile(
          Pattern.quote(tableName) + "\\.(\\w+)", Pattern.CASE_INSENSITIVE);
      Matcher m = tableColPattern.matcher(maskedClause);

      StringBuffer result = new StringBuffer();
      while (m.find()) {
        String dbColName = m.group(1);
        String replacement = resolveColumnToHql(targetEntity, dbColName);
        m.appendReplacement(result, Matcher.quoteReplacement(replacement));
      }
      m.appendTail(result);
      String afterTableRef = result.toString();

      // Step 2: Replace bare column names that are NOT already prefixed with "e."
      // Match word tokens that could be column names (not inside quotes, not SQL keywords)
      Pattern bareColPattern = Pattern.compile("(?<![.'\"])\\b(\\w+)\\b(?!\\s*\\()");
      m = bareColPattern.matcher(afterTableRef);
      result = new StringBuffer();
      java.util.Set<String> sqlKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
          "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN",
          "COALESCE", "CAST", "AS", "SELECT", "FROM", "WHERE", "ORDER", "BY",
          "ASC", "DESC", "TRUE", "FALSE", "ESCAPE", "e"));
      while (m.find()) {
        String token = m.group(1);
        // Skip SQL keywords, string literals, numbers, and already-resolved "e." references
        if (sqlKeywords.contains(token.toUpperCase())
            || token.matches("\\d+")
            || (m.start() > 0 && afterTableRef.charAt(m.start() - 1) == '.')) {
          continue;
        }
        replaceBareColumnToken(m, result, targetEntity, token);
      }
      m.appendTail(result);
      return unmaskParamPlaceholders(result.toString(), maskedParams);
    } catch (Exception e) {
      log.warn("Could not convert SQL to HQL for entity {}: {}", targetEntityName, e.getMessage());
      return sqlClause;
    }
  }

  /**
   * Replace every {@code @Param@} / {@code @#Param@} placeholder in {@code clause} with a unique
   * alphanumeric sentinel that neither translation pass in {@link #convertSqlToHql} can mistake
   * for a real column reference, recording the original text in {@code maskedParams} so it can be
   * restored by {@link #unmaskParamPlaceholders}.
   */
  private static String maskParamPlaceholders(String clause, Map<String, String> maskedParams) {
    Matcher m = PARAM_PLACEHOLDER.matcher(clause);
    StringBuffer sb = new StringBuffer();
    int index = 0;
    while (m.find()) {
      String sentinel = "sfParamMask" + (index++) + "Sentinel";
      maskedParams.put(sentinel, m.group());
      m.appendReplacement(sb, Matcher.quoteReplacement(sentinel));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * Restore the {@code @Param@} / {@code @#Param@} placeholders masked by
   * {@link #maskParamPlaceholders}.
   */
  private static String unmaskParamPlaceholders(String clause, Map<String, String> maskedParams) {
    String restored = clause;
    for (Map.Entry<String, String> entry : maskedParams.entrySet()) {
      restored = restored.replace(entry.getKey(), entry.getValue());
    }
    return restored;
  }

  /**
   * Attempt to replace a bare column token in the HQL result buffer.
   * Looks up the token as a DB column name and, if found, appends the HQL replacement.
   * If the token is not a known column or an exception is thrown, no replacement is appended.
   */
  private static void replaceBareColumnToken(Matcher m, StringBuffer result,
      Entity targetEntity, String token) {
    try {
      Property prop = targetEntity.getPropertyByColumnName(token);
      if (prop != null) {
        String replacement = resolveColumnToHql(targetEntity, token);
        m.appendReplacement(result, Matcher.quoteReplacement(replacement));
      }
    } catch (Exception ignored) {
      // Not a column name, leave as-is
    }
  }

  /**
   * Resolve a single DB column name to an HQL "e.property" path.
   */
  static String resolveColumnToHql(Entity targetEntity, String dbColName) {
    Property prop = targetEntity.getPropertyByColumnName(dbColName);
    if (prop != null) {
      if (!prop.isPrimitive() && prop.getTargetEntity() != null) {
        return "e." + prop.getName() + ".id";
      }
      return "e." + prop.getName();
    }
    return "e." + dbColName;
  }
}
