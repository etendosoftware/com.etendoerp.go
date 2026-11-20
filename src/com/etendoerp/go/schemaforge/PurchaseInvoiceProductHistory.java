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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.HistoryProduct;

/**
 * The products a vendor's completed purchase invoice lines with a given description were booked to
 * (ETP-6364): the first rule of the OCR product match. One query serves the {@code productHistory}
 * action of the purchase-invoice lines and the OCR review ({@link PurchaseInvoiceOcrLookups}).
 *
 * <p>Lines count when their invoice is a purchase ({@code IsSOTrx = 'N'}) of the vendor, completed
 * or closed, of the session client, and their product is active. The line description and the term
 * are compared after {@link #normalize}: lower-case, whitespace runs collapsed and trimmed, accents
 * folded with the very lists of the similarity search ({@code SimSearch.ACCENTED/UNACCENTED}),
 * n-tilde kept. Products come most frequent first, then the most recently invoiced, at most
 * {@link #MAX_PRODUCTS}.</p>
 */
final class PurchaseInvoiceProductHistory {

  /** Most products returned per description. */
  static final int MAX_PRODUCTS = 3;
  /** Accented letters folded, paired by position with {@link #UNACCENTED}; same as SimSearch. */
  static final String ACCENTED = "áéíóúàèìòùäëïöüâêîôûçÁÉÍÓÚÀÈÌÒÙÄËÏÖÜÂÊÎÔÛÇ";
  /** What each {@link #ACCENTED} letter folds to. */
  static final String UNACCENTED = "aeiouaeiouaeiouaeioucAEIOUAEIOUAEIOUAEIOUC";

  private static final String NORMALIZED_DESCRIPTION = "lower(translate(btrim(regexp_replace("
      + "il.description, '\\s+', ' ', 'g')), :accented, :unaccented))";

  static final String SQL = "SELECT " + NORMALIZED_DESCRIPTION + " AS term,"
      + " p.m_product_id, p.name, count(*) AS line_count, max(i.dateinvoiced) AS last_date"
      + " FROM c_invoiceline il"
      + " JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
      + " JOIN m_product p ON p.m_product_id = il.m_product_id"
      + " WHERE i.issotrx = 'N' AND i.c_bpartner_id = :vendorId"
      + " AND i.docstatus IN ('CO', 'CL') AND i.ad_client_id = :clientId AND p.isactive = 'Y'"
      + " AND " + NORMALIZED_DESCRIPTION + " IN (:terms)"
      + " GROUP BY 1, p.m_product_id, p.name"
      + " ORDER BY 1, line_count DESC, last_date DESC, p.m_product_id";

  private PurchaseInvoiceProductHistory() {
  }

  /**
   * The term a description is compared by, as the query normalizes the stored descriptions.
   *
   * @param description the text, may be {@code null}
   * @return the normalized text, empty when blank
   */
  static String normalize(String description) {
    if (description == null) {
      return "";
    }
    return StringUtils.replaceChars(StringUtils.normalizeSpace(description), ACCENTED, UNACCENTED)
        .toLowerCase(Locale.ROOT);
  }

  /**
   * The history of each description for the vendor, in one query. The vendor's ownership is the
   * caller's to check; the client is the session's.
   *
   * @param vendorId     the vendor, {@code null} or blank for none (no query)
   * @param descriptions one per line; a blank one never matches (no query when all are)
   * @return one list per description, same order, most frequent first, empty when nothing
   */
  static List<List<HistoryProduct>> find(String vendorId, List<String> descriptions) {
    List<String> terms = new ArrayList<>(descriptions.size());
    Set<String> distinct = new LinkedHashSet<>();
    for (String description : descriptions) {
      String term = normalize(description);
      terms.add(term);
      if (!term.isEmpty()) {
        distinct.add(term);
      }
    }
    Map<String, List<HistoryProduct>> byTerm = StringUtils.isBlank(vendorId) || distinct.isEmpty()
        ? Collections.emptyMap() : query(vendorId, distinct);
    List<List<HistoryProduct>> out = new ArrayList<>(terms.size());
    for (String term : terms) {
      out.add(byTerm.getOrDefault(term, List.of()));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<HistoryProduct>> query(String vendorId, Set<String> terms) {
    List<Object[]> rows = OBDal.getInstance().getSession().createNativeQuery(SQL)
        .setParameter("accented", ACCENTED)
        .setParameter("unaccented", UNACCENTED)
        .setParameter("vendorId", vendorId)
        .setParameter("clientId", OBContext.getOBContext().getCurrentClient().getId())
        .setParameterList("terms", terms)
        .list();
    Map<String, List<HistoryProduct>> byTerm = new HashMap<>();
    for (Object[] row : rows) {
      List<HistoryProduct> products = byTerm.computeIfAbsent((String) row[0],
          k -> new ArrayList<>());
      if (products.size() < MAX_PRODUCTS) {
        products.add(new HistoryProduct((String) row[1], (String) row[2],
            ((Number) row[3]).intValue()));
      }
    }
    return byTerm;
  }
}
