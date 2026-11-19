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

package com.etendoerp.go.schemaforge.handlers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.schemaforge.handlers.ConversionRateRules.Range;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * ETP-6365: every read the {@code conversion-rates} customization makes — the organization
 * currency, the automatic (downloaded) currencies, the ranges of a pair, the state of a range and
 * the currency catalogue. Instance methods so unit tests can stub them on a spy.
 *
 * <p><b>The {@code com.smf.currency.conversionrate} tables are optional here.</b> The automatic
 * currencies are the ones with an {@code SMFCR_RULE} row, and the last download comes from
 * {@code SMFCR_SYNC_LOG}; both are read only when the module's entities are in the model, so a
 * build without the module answers "no automatic currency" instead of failing. The synced flag of a
 * row ({@code EM_SMFCR_Is_Synced}) is read through the DAL property for the same reason.</p>
 */
class ConversionRateStore {

  private static final Logger log = LogManager.getLogger(ConversionRateStore.class);

  /** DAL property of {@code C_Conversion_Rate.EM_SMFCR_Is_Synced} (com.smf.currency.conversionrate). */
  static final String PROP_SYNCED = "sMFCRSynced";
  static final String TABLE_RULE = "SMFCR_Rule";
  static final String TABLE_SYNC_LOG = "SMFCR_Sync_Log";
  static final String STATUS_CURRENT = "CURRENT";
  static final String STATUS_HISTORIC = "HISTORIC";
  static final String SYSTEM_ID = "0";

  private static final String COL_ID = "c_currency_id";
  private static final String CURRENCY_COLUMNS = "c.c_currency_id, c.iso_code,"
      + " COALESCE(t.description, c.description) AS name,"
      + " COALESCE(t.cursymbol, c.cursymbol) AS symbol";
  private static final String CURRENCY_TRL_JOIN = " LEFT JOIN c_currency_trl t"
      + " ON t.c_currency_id = c.c_currency_id AND t.ad_language = ?";

  /** A currency as the actions publish it. */
  record CurrencyInfo(String id, String isoCode, String name, String symbol) {
  }

  /** The current range of one currency against the organization currency. */
  record CurrentRate(CurrencyInfo currency, BigDecimal rate, Date validFrom, Date rowUpdated) {
  }

  // --- context -------------------------------------------------------------------------------

  String currentClientId() {
    return OBContext.getOBContext().getCurrentClient().getId();
  }

  /**
   * The currency of the request's organization, falling back to the client's when the
   * organization has none (e.g. the {@code *} organization).
   *
   * @return the currency id, or {@code null} when neither has one
   */
  String orgCurrencyId() {
    OBContext ctx = OBContext.getOBContext();
    String orgId = ctx.getCurrentOrganization() != null ? ctx.getCurrentOrganization().getId()
        : SYSTEM_ID;
    String currencyId = OBCurrencyUtils.getOrgCurrency(orgId);
    if (StringUtils.isBlank(currencyId) && ctx.getCurrentClient() != null
        && ctx.getCurrentClient().getCurrency() != null) {
      currencyId = ctx.getCurrentClient().getCurrency().getId();
    }
    return StringUtils.trimToNull(currencyId);
  }

  // --- currencies ----------------------------------------------------------------------------

  Currency currency(String id) {
    return id == null ? null : OBDal.getInstance().get(Currency.class, id);
  }

  /** The currency with this ISO code, or {@code null}. */
  Currency currencyByIso(String isoCode) {
    OBQuery<Currency> query = OBDal.getInstance().createQuery(Currency.class,
        "as c where upper(c.iSOCode) = :iso");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setNamedParameter("iso", isoCode.toUpperCase());
    query.setMaxResult(1);
    List<Currency> found = query.list();
    return found.isEmpty() ? null : found.get(0);
  }

  String isoOf(String currencyId) {
    Currency currency = currency(currencyId);
    return currency != null ? currency.getISOCode() : currencyId;
  }

  /**
   * The currencies whose rate against {@code orgCurrencyId} is downloaded automatically: the other
   * end of every active {@code SMFCR_RULE} of the client (or of System) that has the organization
   * currency at either end.
   */
  Set<String> autoCurrencyIds(String clientId, String orgCurrencyId) {
    Set<String> ids = new HashSet<>();
    if (!hasTable(TABLE_RULE)) {
      return ids;
    }
    String sql = "SELECT CASE WHEN r.c_currency_id_from = ? THEN r.c_currency_id_to"
        + " ELSE r.c_currency_id_from END FROM smfcr_rule r"
        + " WHERE r.isactive = 'Y' AND r.ad_client_id IN (?, '0')"
        + " AND (r.c_currency_id_from = ? OR r.c_currency_id_to = ?)";
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, orgCurrencyId);
      ps.setString(2, clientId);
      ps.setString(3, orgCurrencyId);
      ps.setString(4, orgCurrencyId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not read the automatic currencies", e);
    }
    return ids;
  }

  /**
   * The currencies that already have an active conversion rate of the client with
   * {@code orgCurrencyId}, in either direction, on any date.
   */
  Set<String> currenciesWithRates(String clientId, String orgCurrencyId) {
    Set<String> ids = new HashSet<>();
    String sql = "SELECT DISTINCT CASE WHEN cr.c_currency_id = ? THEN cr.c_currency_id_to"
        + " ELSE cr.c_currency_id END FROM c_conversion_rate cr"
        + " WHERE cr.ad_client_id = ? AND cr.isactive = 'Y'"
        + " AND (cr.c_currency_id = ? OR cr.c_currency_id_to = ?)";
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, orgCurrencyId);
      ps.setString(2, clientId);
      ps.setString(3, orgCurrencyId);
      ps.setString(4, orgCurrencyId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not read the currencies with rates", e);
    }
    return ids;
  }

  /**
   * Every active currency of the system or of the client except {@code excludedId}, by ISO code,
   * named in the request language.
   */
  List<CurrencyInfo> activeCurrencies(String clientId, String excludedId) {
    String sql = "SELECT " + CURRENCY_COLUMNS + " FROM c_currency c" + CURRENCY_TRL_JOIN
        + " WHERE c.isactive = 'Y' AND c.ad_client_id IN ('0', ?) AND c.c_currency_id <> ?"
        + " ORDER BY c.iso_code";
    List<CurrencyInfo> currencies = new ArrayList<>();
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, language());
      ps.setString(2, clientId);
      ps.setString(3, excludedId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          currencies.add(currencyInfo(rs));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not read the currencies", e);
    }
    return currencies;
  }

  /**
   * One entry per currency with a range of the client against {@code orgCurrencyId} that contains
   * the current moment, in either direction; the latest start wins, and the forward row (from the
   * organization currency) when both directions start at once. The rate is always expressed as
   * "1 organization currency = rate X".
   */
  List<CurrentRate> currentRates(String clientId, String orgCurrencyId) {
    String sql = "SELECT " + CURRENCY_COLUMNS + ", cr.c_currency_id AS from_id, cr.multiplyrate,"
        + " cr.dividerate, cr.validfrom, cr.updated"
        + " FROM c_conversion_rate cr"
        + " JOIN c_currency c ON c.c_currency_id = CASE WHEN cr.c_currency_id = ?"
        + " THEN cr.c_currency_id_to ELSE cr.c_currency_id END" + CURRENCY_TRL_JOIN
        + " WHERE cr.ad_client_id = ? AND cr.isactive = 'Y' AND c.isactive = 'Y'"
        + " AND (cr.c_currency_id = ? OR cr.c_currency_id_to = ?)"
        + " AND cr.validfrom <= now()"
        + " AND COALESCE(cr.validto, TO_DATE('31-12-9999', 'DD-MM-YYYY')) >= now()"
        + " ORDER BY c.iso_code, cr.validfrom DESC,"
        + " CASE WHEN cr.c_currency_id = ? THEN 0 ELSE 1 END";
    Map<String, CurrentRate> byCurrency = new LinkedHashMap<>();
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, orgCurrencyId);
      ps.setString(2, language());
      ps.setString(3, clientId);
      ps.setString(4, orgCurrencyId);
      ps.setString(5, orgCurrencyId);
      ps.setString(6, orgCurrencyId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          CurrencyInfo info = currencyInfo(rs);
          boolean forward = orgCurrencyId.equals(rs.getString("from_id"));
          BigDecimal rate = forward ? rs.getBigDecimal("multiplyrate")
              : rs.getBigDecimal("dividerate");
          byCurrency.putIfAbsent(info.id(), new CurrentRate(info, rate,
              rs.getTimestamp("validfrom"), rs.getTimestamp("updated")));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not read the current rates", e);
    }
    return new ArrayList<>(byCurrency.values());
  }

  /**
   * The latest {@code SUCCESS} run of the rate downloader for the client, or {@code null} when
   * there is none or the module is not installed. The log is per run, not per currency pair: a
   * run that failed any pair is {@code PARTIAL}/{@code FAILED} and is not counted.
   */
  Date latestSuccessfulDownload(String clientId) {
    if (!hasTable(TABLE_SYNC_LOG)) {
      return null;
    }
    String sql = "SELECT MAX(l.sync_date) FROM smfcr_sync_log l"
        + " WHERE l.ad_client_id = ? AND l.status = 'SUCCESS'";
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getTimestamp(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not read the rate download log", e);
    }
  }

  // --- ranges --------------------------------------------------------------------------------

  ConversionRate rate(String id) {
    return id == null ? null : OBDal.getInstance().get(ConversionRate.class, id);
  }

  /** The row when it belongs to the current client, else {@code null} (unknown or foreign). */
  ConversionRate ownRate(String id) {
    ConversionRate row = rate(id);
    return row != null && row.getClient().getId().equals(currentClientId()) ? row : null;
  }

  /**
   * Every active range of the client between {@code fromId} and {@code toId}, both directions
   * folded into one {@link Range} per {@code (ValidFrom, ValidTo)}, oriented {@code fromId → toId}.
   */
  List<Range> rangesOfPair(String clientId, String fromId, String toId) {
    OBQuery<ConversionRate> query = OBDal.getInstance().createQuery(ConversionRate.class,
        "as cr where cr.client.id = :client"
            + " and ((cr.currency.id = :a and cr.toCurrency.id = :b)"
            + " or (cr.currency.id = :b and cr.toCurrency.id = :a))"
            + " order by cr.validFromDate, cr.id");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setNamedParameter("client", clientId);
    query.setNamedParameter("a", fromId);
    query.setNamedParameter("b", toId);
    return group(query.list(), fromId);
  }

  /** Folds the rows of one pair into ranges, keyed by start and end. */
  List<Range> group(List<ConversionRate> rows, String fromId) {
    Map<String, Range> ranges = new LinkedHashMap<>();
    for (ConversionRate row : rows) {
      LocalDateTime from = toLocal(row.getValidFromDate());
      LocalDateTime to = toLocal(row.getValidToDate());
      String key = from + "|" + to;
      Range known = ranges.get(key);
      boolean forward = fromId.equals(row.getCurrency().getId());
      String forwardId = forward ? row.getId() : idOrNull(known, true);
      String inverseId = forward ? idOrNull(known, false) : row.getId();
      boolean synced = isSynced(row) || (known != null && known.synced());
      ranges.put(key, new Range(forwardId, inverseId, from, to, synced));
    }
    return new ArrayList<>(ranges.values());
  }

  private static String idOrNull(Range range, boolean forward) {
    if (range == null) {
      return null;
    }
    return forward ? range.forwardId() : range.inverseId();
  }

  boolean isSynced(ConversionRate row) {
    Entity entity = row.getEntity();
    return entity.hasProperty(PROP_SYNCED) && Boolean.TRUE.equals(row.get(PROP_SYNCED));
  }

  /** {@code ETGO_CONVRATE_STATUS}: CURRENT or HISTORIC. */
  String statusOf(String rateId) {
    String status = callFunction("SELECT etgo_convrate_status(?)", rateId);
    return status != null ? status : STATUS_HISTORIC;
  }

  /** The server's current wall-clock time; a seam for unit tests. */
  LocalDateTime now() {
    return LocalDateTime.now();
  }

  private String callFunction(String sql, String rateId) {
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, rateId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not read the state of conversion rate " + rateId, e);
    }
  }

  // --- helpers -------------------------------------------------------------------------------

  static LocalDateTime toLocal(Date date) {
    return date == null ? null : new Timestamp(date.getTime()).toLocalDateTime();
  }

  static Date toDate(LocalDateTime dateTime) {
    return dateTime == null ? null : Timestamp.valueOf(dateTime);
  }

  /** Whether the entity of {@code tableName} is in the runtime model (its module is installed). */
  boolean hasTable(String tableName) {
    try {
      return ModelProvider.getInstance().getEntityByTableName(tableName) != null;
    } catch (RuntimeException e) {
      log.debug("Table {} is not in the model: {}", tableName, e.getMessage());
      return false;
    }
  }

  Connection connection() {
    return OBDal.getInstance().getConnection(true);
  }

  private static CurrencyInfo currencyInfo(ResultSet rs) throws SQLException {
    return new CurrencyInfo(rs.getString(COL_ID), StringUtils.trim(rs.getString("iso_code")),
        rs.getString("name"), rs.getString("symbol"));
  }

  private static String language() {
    String code = NeoLanguage.requestCode();
    if (code == null) {
      code = NeoLanguage.currentCode();
    }
    return code != null ? code : "en_US";
  }
}
