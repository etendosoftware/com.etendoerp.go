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

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

/**
 * Resolves the tax a document line should default to when the only hints are the vendor address,
 * the date, an optional extracted percentage and an optional product (ETP-5585).
 *
 * <p><b>How.</b> The core function {@code c_gettax} is asked once per active tax category the
 * organization may use (its 11th argument, {@code p_taxcat_id}, forces the category — the Java
 * {@code Tax.get} does not expose it, so the SQL is called directly). Each answer is a real
 * {@code c_tax} row chosen by the same location / region / BP-category / cash-VAT rules the form
 * applies. The rest is selection: {@link #rank} picks among those answers and is pure, so it can
 * be tested without a database.</p>
 *
 * <p><b>Structure only.</b> Nothing here names a spec, a table's business meaning or a document
 * type: it branches only on generic columns (rate, summary flag, the category's default flag and
 * owner client). The document direction is a parameter ({@code isSOTrx}).</p>
 *
 * <p><b>Total.</b> Missing data, an organization without a location or a failing query yield an
 * empty result, never an exception.</p>
 */
final class DefaultTaxResolver {

  private static final Logger log = LogManager.getLogger(DefaultTaxResolver.class);

  /** The tax of the product's own tax category. */
  static final String SOURCE_PRODUCT_CATEGORY = "PRODUCT_CATEGORY";
  /** The tax of the client's default tax category. */
  static final String SOURCE_DEFAULT_CATEGORY = "DEFAULT_CATEGORY";
  /** A tax of some other category whose rate equals the requested one. */
  static final String SOURCE_RATE_MATCH = "RATE_MATCH";
  /** A rate was requested and no category answers with it. */
  static final String SOURCE_NO_MATCH = "NO_MATCH";
  /** No rate was requested and neither the product nor the default category answered. */
  static final String SOURCE_NO_CONTEXT = "NO_CONTEXT";

  /** Maximum number of alternative matches returned next to the chosen one. */
  static final int MAX_CANDIDATES = 5;

  /** Two rates closer than this (percentage points) are the same rate. */
  private static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.005");

  private static final String SYSTEM_CLIENT = "0";
  private static final String GETTAX_PRODUCT = "?";
  private static final String GETTAX_NO_PRODUCT = "NULL";
  private static final String GETTAX_NO_TAXCAT = "CAST(NULL AS varchar)";
  private static final String GETTAX_TAXCAT_OF_ROW = "tc.c_taxcategory_id";

  /** Columns of {@code c_tax} that {@code c_gettax} orders by or filters on. */
  private static final List<String> TIE_COLUMNS = List.of("c_bp_taxcategory_id", "sopotype",
      "iscashvat", "c_country_id", "c_region_id", "to_country_id", "to_region_id",
      "ad_client_id", "ad_org_id", "isdefault", "iswithholdingtax");

  private static final String SELECT_COLUMNS = "SELECT t.c_tax_id, t.name AS tax_name, t.rate,"
      + " t.issummary, tc.c_taxcategory_id, tc.name AS cat_name, tc.isdefault,"
      + " tc.ad_client_id AS cat_client, t.validfrom,"
      + " (SELECT COUNT(*) FROM m_product p WHERE p.c_taxcategory_id = tc.c_taxcategory_id"
      + " AND p.ad_client_id = ? AND p.isactive = 'Y') AS prod_count"
      + " FROM c_tax t JOIN c_taxcategory tc ON tc.c_taxcategory_id = t.c_taxcategory_id";

  private DefaultTaxResolver() {
  }

  /**
   * One answer of {@code c_gettax}: a tax and the category it was asked for.
   *
   * @param taxId           {@code C_Tax_ID}
   * @param taxName         the tax's DAL identifier ({@code C_Tax.Name})
   * @param rate            the tax rate, in percent
   * @param summary         {@code C_Tax.IsSummary}
   * @param categoryId      {@code C_TaxCategory_ID}
   * @param categoryName    {@code C_TaxCategory.Name}
   * @param categoryDefault {@code C_TaxCategory.IsDefault}
   * @param categoryClient  {@code C_TaxCategory.AD_Client_ID}
   * @param productCount    active products of the client in that category
   * @param validFrom       {@code C_Tax.ValidFrom}, or {@code null} when unknown; the last
   *                        tie-break inside one category prefers the most recent, as
   *                        {@code c_gettax}'s own {@code ORDER BY ... ValidFrom DESC} does
   */
  record Determination(String taxId, String taxName, BigDecimal rate, boolean summary,
      String categoryId, String categoryName, boolean categoryDefault, String categoryClient,
      long productCount, Timestamp validFrom) {

    /** A determination whose {@code ValidFrom} is unknown. */
    Determination(String taxId, String taxName, BigDecimal rate, boolean summary,
        String categoryId, String categoryName, boolean categoryDefault, String categoryClient,
        long productCount) {
      this(taxId, taxName, rate, summary, categoryId, categoryName, categoryDefault,
          categoryClient, productCount, null);
    }
  }

  /**
   * A chosen tax and why.
   *
   * @param chosen     the tax to default to, {@code null} when none
   * @param source     one of the {@code SOURCE_*} constants
   * @param candidates other matches of the same rate, in rank order (never more than
   *                   {@link #MAX_CANDIDATES}); sources are not tracked for them
   */
  record Result(Determination chosen, String source, List<Determination> candidates) {

    static Result none(String source) {
      return new Result(null, source, Collections.emptyList());
    }
  }

  /**
   * Resolves the default tax.
   *
   * @param orgId             organization of the session
   * @param clientId          client of the session
   * @param partnerLocationId the vendor's {@code C_BPartner_Location_ID}
   * @param date              the document date; today when {@code null}
   * @param rate              the extracted percentage, or {@code null}
   * @param productId         the product, or blank
   * @param isSOTrx           {@code true} for a sales document, {@code false} for a purchase
   * @return the result; never {@code null}
   */
  @SuppressWarnings("java:S107") // one request, seven independent inputs of the contract
  static Result resolve(String orgId, String clientId, String partnerLocationId, Date date,
      BigDecimal rate, String productId, boolean isSOTrx) {
    if (StringUtils.isAnyBlank(orgId, clientId, partnerLocationId)) {
      return Result.none(SOURCE_NO_CONTEXT);
    }
    Timestamp when = new Timestamp(date != null ? date.getTime() : System.currentTimeMillis());
    List<Determination> candidates = fetchDeterminations(orgId, clientId, partnerLocationId, when,
        isSOTrx);
    Determination product = StringUtils.isBlank(productId) ? null
        : fetchProductDetermination(orgId, clientId, partnerLocationId, when, productId, isSOTrx);
    if (rate == null) {
      return rank(candidates, product, null);
    }
    List<Determination> pool = new ArrayList<>(candidates);
    List<String> answered = new ArrayList<>();
    candidates.forEach(d -> answered.add(d.taxId()));
    if (product != null) {
      answered.add(product.taxId());
    }
    pool.addAll(fetchTieSiblings(clientId, answered, when));
    return rank(pool, product, rate);
  }

  /**
   * One determination per active tax category the organization may use, in one round trip.
   * Categories for which {@code c_gettax} finds no tax are absent.
   *
   * @return the determinations, empty on any failure
   */
  static List<Determination> fetchDeterminations(String orgId, String clientId,
      String partnerLocationId, Timestamp when, boolean isSOTrx) {
    String sql = SELECT_COLUMNS + " AND t.c_tax_id = " + getTaxCall(GETTAX_NO_PRODUCT,
        GETTAX_TAXCAT_OF_ROW)
        + " WHERE tc.isactive = 'Y' AND tc.ad_client_id IN (?, '0')"
        + " AND (ad_isorgincluded(?, tc.ad_org_id, tc.ad_client_id) <> -1"
        + " OR (tc.ad_client_id = '0' AND tc.ad_org_id = '0'))";
    return query(sql, ps -> {
      int i = 1;
      ps.setString(i++, clientId);
      i = bindGetTax(ps, i, null, when, orgId, partnerLocationId, isSOTrx);
      ps.setString(i++, clientId);
      ps.setString(i, orgId);
    });
  }

  /**
   * What {@code c_gettax} answers for the product itself (its own tax category), the same call the
   * product callout of a document line makes.
   *
   * @return the determination, or {@code null} when there is none or the query fails
   */
  static Determination fetchProductDetermination(String orgId, String clientId,
      String partnerLocationId, Timestamp when, String productId, boolean isSOTrx) {
    String sql = SELECT_COLUMNS + " WHERE t.c_tax_id = " + getTaxCall(GETTAX_PRODUCT,
        GETTAX_NO_TAXCAT);
    List<Determination> rows = query(sql, ps -> {
      int i = 1;
      ps.setString(i++, clientId);
      bindGetTax(ps, i, productId, when, orgId, partnerLocationId, isSOTrx);
    });
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * The taxes {@code c_gettax} could equally have answered: active, non-child taxes of the same
   * category that tie with an answered tax on every column its ordering looks at (BP tax
   * category, purchase/sales type, cash VAT, countries, regions, owner client and organization,
   * default and withholding flags). {@code c_gettax} returns a single row, so a tie is broken
   * arbitrarily and the sibling — say the plain 21% next to a 21+5.2% surcharge tax — would never
   * be offered. {@code ValidFrom} is not a tie column (an older plain tax would otherwise be
   * hidden by a newer unrelated one) but validity is kept: the sibling must be valid on
   * {@code when}, the rule {@code c_gettax} applies. Only used when a rate is requested.
   *
   * @param answeredTaxIds the {@code C_Tax_ID}s {@code c_gettax} answered
   * @param when           the document date
   * @return the siblings, empty on any failure or when there is nothing to tie with
   */
  static List<Determination> fetchTieSiblings(String clientId, List<String> answeredTaxIds,
      Timestamp when) {
    if (answeredTaxIds == null || answeredTaxIds.isEmpty()) {
      return Collections.emptyList();
    }
    String sql = SELECT_COLUMNS
        + " JOIN c_tax g ON g.c_taxcategory_id = t.c_taxcategory_id AND g.c_tax_id = ANY(?)"
        + " AND g.c_tax_id <> t.c_tax_id"
        + " WHERE t.isactive = 'Y' AND t.parent_tax_id IS NULL AND t.validfrom <= ?"
        + TIE_COLUMNS.stream().map(c -> " AND t." + c + " IS NOT DISTINCT FROM g." + c)
            .collect(Collectors.joining());
    return query(sql, ps -> {
      ps.setString(1, clientId);
      ps.setArray(2, ps.getConnection().createArrayOf("text", answeredTaxIds.toArray()));
      ps.setTimestamp(3, when);
    });
  }

  /**
   * Picks the default tax among the determinations. Pure: no database.
   *
   * <p>Without {@code rate}: the product's determination ({@link #SOURCE_PRODUCT_CATEGORY}), else
   * the determination of the default category — the client's own one before the System one
   * ({@link #SOURCE_DEFAULT_CATEGORY}), else {@link #SOURCE_NO_CONTEXT}.</p>
   *
   * <p>With {@code rate}: only determinations whose rate equals it (within 0.005) compete, ranked
   * by (1) the product's category, (2) the default category (the client's own before the System
   * one), (3) non-summary before summary, (4) the category with more active client products,
   * (5) category name, (6) the most recent {@code ValidFrom}, (7) tax name, then tax id for a
   * stable order. The first is the answer — its source is {@link #SOURCE_PRODUCT_CATEGORY} or
   * {@link #SOURCE_DEFAULT_CATEGORY} when it won on tier (1) or (2), {@link #SOURCE_RATE_MATCH}
   * otherwise; the others (at most {@link #MAX_CANDIDATES}, one per tax) are the candidates. None
   * is {@link #SOURCE_NO_MATCH}.</p>
   *
   * @param determinations one answer per tax category; may be {@code null}
   * @param product        the product's own answer, or {@code null}
   * @param rate           the extracted percentage, or {@code null}
   * @return the result; never {@code null}
   */
  static Result rank(List<Determination> determinations, Determination product, BigDecimal rate) {
    List<Determination> all = determinations != null ? determinations : Collections.emptyList();
    if (rate == null) {
      return rankWithoutRate(all, product);
    }
    return rankWithRate(all, product, rate);
  }

  private static Result rankWithoutRate(List<Determination> all, Determination product) {
    if (product != null) {
      return new Result(product, SOURCE_PRODUCT_CATEGORY, Collections.emptyList());
    }
    Determination best = all.stream().filter(Determination::categoryDefault)
        .min(Comparator.comparing((Determination d) -> SYSTEM_CLIENT.equals(d.categoryClient()))
            .thenComparing(DefaultTaxResolver::byStableOrder))
        .orElse(null);
    return best != null ? new Result(best, SOURCE_DEFAULT_CATEGORY, Collections.emptyList())
        : Result.none(SOURCE_NO_CONTEXT);
  }

  private static Result rankWithRate(List<Determination> all, Determination product,
      BigDecimal rate) {
    List<Determination> pool = new ArrayList<>(all);
    if (product != null) {
      pool.add(product);
    }
    pool.removeIf(d -> d.rate() == null || d.rate().subtract(rate).abs()
        .compareTo(RATE_TOLERANCE) >= 0);
    pool.sort(Comparator.<Determination, Integer>comparing(d -> tier(d, product))
        .thenComparing(d -> tier(d, product) == 1 && SYSTEM_CLIENT.equals(d.categoryClient()))
        .thenComparing(Determination::summary)
        .thenComparing(Comparator.comparingLong(Determination::productCount).reversed())
        .thenComparing(DefaultTaxResolver::byStableOrder));
    List<Determination> distinct = distinctByTax(pool);
    if (distinct.isEmpty()) {
      return Result.none(SOURCE_NO_MATCH);
    }
    Determination first = distinct.get(0);
    String source = sourceOfTier(tier(first, product));
    List<Determination> others = distinct.subList(1, Math.min(distinct.size(), MAX_CANDIDATES + 1));
    return new Result(first, source, List.copyOf(others));
  }

  /** 0 = the product's own answer or its category, 1 = a default category, 2 = anything else. */
  private static int tier(Determination d, Determination product) {
    if (product != null && (product.taxId().equals(d.taxId())
        || StringUtils.equals(product.categoryId(), d.categoryId()))) {
      return 0;
    }
    return d.categoryDefault() ? 1 : 2;
  }

  private static String sourceOfTier(int tier) {
    if (tier == 0) {
      return SOURCE_PRODUCT_CATEGORY;
    }
    return tier == 1 ? SOURCE_DEFAULT_CATEGORY : SOURCE_RATE_MATCH;
  }

  private static int byStableOrder(Determination a, Determination b) {
    int byCategory = StringUtils.compare(a.categoryName(), b.categoryName());
    if (byCategory != 0) {
      return byCategory;
    }
    int byValidity = Comparator.nullsLast(Comparator.<Timestamp>reverseOrder())
        .compare(a.validFrom(), b.validFrom());
    if (byValidity != 0) {
      return byValidity;
    }
    int byTax = StringUtils.compare(a.taxName(), b.taxName());
    return byTax != 0 ? byTax : StringUtils.compare(a.taxId(), b.taxId());
  }

  private static List<Determination> distinctByTax(List<Determination> sorted) {
    Set<String> seen = new LinkedHashSet<>();
    List<Determination> out = new ArrayList<>();
    for (Determination d : sorted) {
      if (seen.add(d.taxId())) {
        out.add(d);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // SQL
  // ---------------------------------------------------------------------------

  /**
   * The {@code c_gettax} call with every one of its 11 arguments explicit, in the order of
   * {@code C_GETTAX.xml}: product, ship date, org, warehouse, bill-to location, ship-to location,
   * project, isSOTrx, GL item, forced cash VAT, tax category. Placeholders, in order:
   * product (only when {@code productExpr} is {@code ?}), date, org, location (bill), location
   * (ship), isSOTrx.
   */
  private static String getTaxCall(String productExpr, String taxCategoryExpr) {
    return "c_gettax(CAST(" + productExpr + " AS varchar), CAST(? AS timestamp),"
        + " CAST(? AS varchar), CAST(NULL AS varchar), CAST(? AS varchar), CAST(? AS varchar),"
        + " CAST(NULL AS varchar), CAST(? AS bpchar), CAST(NULL AS varchar),"
        + " CAST(NULL AS bpchar), " + taxCategoryExpr + ")";
  }

  private static int bindGetTax(PreparedStatement ps, int start, String productId, Timestamp when,
      String orgId, String locationId, boolean isSOTrx) throws SQLException {
    int i = start;
    if (productId != null) {
      ps.setString(i++, productId);
    }
    ps.setTimestamp(i++, when);
    ps.setString(i++, orgId);
    ps.setString(i++, locationId);
    ps.setString(i++, locationId);
    ps.setString(i++, isSOTrx ? "Y" : "N");
    return i;
  }

  /** Binds the placeholders of one statement. */
  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement ps) throws SQLException;
  }

  /**
   * Runs a determination query inside a savepoint: a failure inside {@code c_gettax} aborts the
   * PostgreSQL transaction, which is the request's own, so it is rolled back to the savepoint and
   * the caller sees an empty answer instead of a poisoned connection.
   */
  // placeholders are bound via PreparedStatement — no injection risk
  @SuppressWarnings("java:S2077")
  private static List<Determination> query(String sql, Binder binder) {
    Connection conn = OBDal.getInstance().getConnection();
    Savepoint savepoint = null;
    try {
      savepoint = conn.getAutoCommit() ? null : conn.setSavepoint();
      List<Determination> out = new ArrayList<>();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        binder.bind(ps);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            out.add(new Determination(rs.getString("c_tax_id"), rs.getString("tax_name"),
                rs.getBigDecimal("rate"), "Y".equals(rs.getString("issummary")),
                rs.getString("c_taxcategory_id"), rs.getString("cat_name"),
                "Y".equals(rs.getString("isdefault")), rs.getString("cat_client"),
                rs.getLong("prod_count"), rs.getTimestamp("validfrom")));
          }
        }
      }
      releaseQuietly(conn, savepoint);
      return out;
    } catch (SQLException | RuntimeException e) {
      log.warn("[ETP-5585] default tax determination failed: {}", e.getMessage());
      rollbackQuietly(conn, savepoint);
      return Collections.emptyList();
    }
  }

  private static void releaseQuietly(Connection conn, Savepoint savepoint) {
    if (savepoint == null) {
      return;
    }
    try {
      conn.releaseSavepoint(savepoint);
    } catch (SQLException e) {
      log.debug("[ETP-5585] could not release the savepoint: {}", e.getMessage());
    }
  }

  private static void rollbackQuietly(Connection conn, Savepoint savepoint) {
    if (savepoint == null) {
      return;
    }
    try {
      conn.rollback(savepoint);
    } catch (SQLException e) {
      log.warn("[ETP-5585] could not roll back to the savepoint: {}", e.getMessage());
    }
  }
}
