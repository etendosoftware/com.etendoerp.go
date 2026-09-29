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

import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.DATE_FORMATTER;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_ACCT_SCHEMA_ID;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_BPARTNER_ID;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_COST_CENTER_ID;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_DATE_FROM;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_DATE_TO;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_FROM_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_ORG_ID;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_PRODUCT_ID;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_PROJECT_ID;
import static com.etendoerp.go.schemaforge.AbstractSqlReportHandler.PARAM_TO_ACCOUNT_ID;

import java.time.LocalDate;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;

/**
 * The resolved request filters the three {@code fact_acct} period reports share — {@link
 * TrialBalanceReportHandler}, {@link JournalEntriesReportHandler} and {@link
 * GeneralLedgerReportHandler}: client, organization, accounting schema, the period, the four
 * accounting-dimension id lists and the account search-key range. Built once per request after
 * validation, then bound identically by every query of the report.
 *
 * <p>The SQL fragments are emitted with a caller-supplied indent and account column because the
 * three reports genuinely differ there (each report's SQL text is kept byte-for-byte what it was):
 * the trial balance filters the account range on {@code lev.value}, the general ledger on {@code
 * ev.value}, the journal entries on {@code fact_acct.acctvalue} — and the journal entries insert
 * their own {@code fact_acct_group_id} clause between the dimension clauses and the account range,
 * which is why the two halves are separate methods.
 */
final class LedgerFilters {

  final String clientId;
  final String orgId;
  final String acctSchemaId;
  final LocalDate dateFrom;
  final LocalDate dateTo;
  final List<String> bPartnerIds;
  final List<String> productIds;
  final List<String> projectIds;
  final List<String> costCenterIds;
  final String fromAccountId;
  final String toAccountId;

  private LedgerFilters(JSONObject body, String clientId, String orgId, String acctSchemaId,
      LocalDate dateFrom, LocalDate dateTo) {
    this.clientId = clientId;
    this.orgId = orgId;
    this.acctSchemaId = acctSchemaId;
    this.dateFrom = dateFrom;
    this.dateTo = dateTo;
    this.bPartnerIds = parseIds(body.optString(PARAM_BPARTNER_ID, ""));
    this.productIds = parseIds(body.optString(PARAM_PRODUCT_ID, ""));
    this.projectIds = parseIds(body.optString(PARAM_PROJECT_ID, ""));
    this.costCenterIds = parseIds(body.optString(PARAM_COST_CENTER_ID, ""));
    this.fromAccountId = body.optString(PARAM_FROM_ACCOUNT_ID, "");
    this.toAccountId = body.optString(PARAM_TO_ACCOUNT_ID, "");
  }

  /**
   * Reads the dimension id lists and the account range from {@code body}; the already-validated
   * scope values are passed in.
   */
  static LedgerFilters fromRequest(JSONObject body, String clientId, String orgId,
      String acctSchemaId, LocalDate dateFrom, LocalDate dateTo) {
    return new LedgerFilters(body, clientId, orgId, acctSchemaId, dateFrom, dateTo);
  }

  /** One id, or several separated by commas; blank / {@code "null"} means no filter. */
  static List<String> parseIds(String rawValue) {
    if (StringUtils.isBlank(rawValue) || "null".equalsIgnoreCase(rawValue)) {
      return List.of();
    }
    return java.util.Arrays.stream(rawValue.split(","))
        .map(String::trim)
        .filter(StringUtils::isNotBlank)
        .toList();
  }

  /** Appends the organization (exact match) and dimension WHERE clauses, each only when set. */
  void appendDimensionWhere(StringBuilder sql, String indent) {
    if (!orgId.isEmpty()) {
      sql.append(indent).append("AND fa.ad_org_id = :orgId ");
    }
    if (!bPartnerIds.isEmpty()) {
      sql.append(indent).append("AND fa.c_bpartner_id IN (:bPartnerIds) ");
    }
    if (!productIds.isEmpty()) {
      sql.append(indent).append("AND fa.m_product_id IN (:productIds) ");
    }
    if (!projectIds.isEmpty()) {
      sql.append(indent).append("AND fa.c_project_id IN (:projectIds) ");
    }
    if (!costCenterIds.isEmpty()) {
      sql.append(indent).append("AND fa.c_costcenter_id IN (:costCenterIds) ");
    }
  }

  /** Appends the inclusive account search-key range on {@code accountColumn}, each bound only when set. */
  void appendAccountRangeWhere(StringBuilder sql, String indent, String accountColumn) {
    if (!fromAccountId.isEmpty()) {
      sql.append(indent).append("AND ").append(accountColumn).append(" >= :fromAccountId ");
    }
    if (!toAccountId.isEmpty()) {
      sql.append(indent).append("AND ").append(accountColumn).append(" <= :toAccountId ");
    }
  }

  /** Binds what {@link #appendDimensionWhere} emitted. */
  void bindDimensions(NativeQuery<?> query) {
    if (!orgId.isEmpty()) {
      query.setParameter(PARAM_ORG_ID, orgId);
    }
    if (!bPartnerIds.isEmpty()) {
      query.setParameterList("bPartnerIds", bPartnerIds);
    }
    if (!productIds.isEmpty()) {
      query.setParameterList("productIds", productIds);
    }
    if (!projectIds.isEmpty()) {
      query.setParameterList("projectIds", projectIds);
    }
    if (!costCenterIds.isEmpty()) {
      query.setParameterList("costCenterIds", costCenterIds);
    }
  }

  /** Binds what {@link #appendAccountRangeWhere} emitted. */
  void bindAccountRange(NativeQuery<?> query) {
    if (!fromAccountId.isEmpty()) {
      query.setParameter(PARAM_FROM_ACCOUNT_ID, fromAccountId);
    }
    if (!toAccountId.isEmpty()) {
      query.setParameter(PARAM_TO_ACCOUNT_ID, toAccountId);
    }
  }

  /** The leading {@code meta} keys: {@code dateFrom, dateTo, orgId, acctSchemaId}, in that order. */
  void putScopeMeta(JSONObject meta) throws JSONException {
    meta.put(PARAM_DATE_FROM, dateFrom.format(DATE_FORMATTER));
    meta.put(PARAM_DATE_TO, dateTo.format(DATE_FORMATTER));
    meta.put(PARAM_ORG_ID, orgId);
    meta.put(PARAM_ACCT_SCHEMA_ID, acctSchemaId);
  }

  /**
   * The trailing {@code meta} keys: the account range then the four dimension id lists
   * (comma-joined), in that order.
   */
  void putFilterMeta(JSONObject meta) throws JSONException {
    meta.put(PARAM_FROM_ACCOUNT_ID, fromAccountId);
    meta.put(PARAM_TO_ACCOUNT_ID, toAccountId);
    meta.put(PARAM_BPARTNER_ID, String.join(",", bPartnerIds));
    meta.put(PARAM_PRODUCT_ID, String.join(",", productIds));
    meta.put(PARAM_PROJECT_ID, String.join(",", projectIds));
    meta.put(PARAM_COST_CENTER_ID, String.join(",", costCenterIds));
  }
}
