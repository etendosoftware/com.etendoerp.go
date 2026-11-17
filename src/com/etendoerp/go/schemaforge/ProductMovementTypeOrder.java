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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.apache.commons.lang3.StringUtils;

/**
 * The order of the Product "Movements" list when the user sorts by the visible <em>Type</em> label
 * (the label is translated by the client, so its alphabetical order is the client's to compute).
 *
 * <p>The client sends the order of the type buckets in the active language
 * ({@value #PARAM_TYPE_ORDER}, comma-separated bucket ids) next to {@code _sortBy=[-]etgoDocKind}.
 * This class turns the two into an HQL order-by that ranks every row by its bucket, then keeps the
 * client's own tie-breakers. Everything that reaches the HQL is either a constant of this class,
 * a rank (an integer computed here) or a property name from an allow-list: request text is only
 * ever <em>looked up</em> in an allow-list, never concatenated.</p>
 *
 * <p><b>The bucket rule lives here ({@link #bucketCase}) and in the client
 * ({@code resolveMovementBucket}, {@code productMovements.js}); both are covered by the same table
 * of cases, which must stay identical.</b> A row's bucket is the kind of its source document
 * ({@code etgoDocKind}), corrected by the sign of the quantity where a return or an inventory
 * direction changes what the user reads; a row with no kind falls back to its movement type code.</p>
 */
final class ProductMovementTypeOrder {

  /** Query parameter carrying the bucket ids in display order. */
  static final String PARAM_TYPE_ORDER = "typeLabelOrder";

  /** The sort field this order answers to. */
  static final String SORT_FIELD = "etgoDocKind";

  // Bucket ids: part of the REST contract (typeLabelOrder), values must not change.
  private static final String VENDOR_RECEIPT = "vendor-receipt";
  private static final String VENDOR_RETURN = "vendor-return";
  private static final String CUSTOMER_SHIPMENT = "customer-shipment";
  private static final String CUSTOMER_RETURN = "customer-return";
  private static final String TRANSFER = "transfer";
  private static final String INTERNAL_CONSUMPTION = "internal-consumption";
  private static final String INVENTORY_IN = "inventory-in";
  private static final String INVENTORY_OUT = "inventory-out";

  /** Bucket ids, one per distinct visible label. */
  static final List<String> BUCKETS = List.of(VENDOR_RECEIPT, VENDOR_RETURN, CUSTOMER_SHIPMENT,
      CUSTOMER_RETURN, TRANSFER, INTERNAL_CONSUMPTION, INVENTORY_IN, INVENTORY_OUT);

  /** Rows no bucket claims (production, work orders): they have no label and always sort last. */
  static final String UNLABELED = "unlabeled";

  private static final String ALIAS = "e.";
  private static final String KIND = ALIAS + SORT_FIELD;
  private static final String QTY = ALIAS + "movementQuantity";
  private static final String MOVEMENT_TYPE = ALIAS + "movementType";

  /** Tie-breakers the client may send after the sort field, as DAL property -> HQL path. */
  private static final Map<String, String> TIE_BREAKERS = Map.of(
      "movementDate", ALIAS + "movementDate",
      "transactionProcessDate", ALIAS + "transactionProcessDate",
      "id", ALIAS + "id");

  private ProductMovementTypeOrder() {
  }

  /**
   * The HQL expression that maps a row to its bucket.
   *
   * @param literal renders a bucket id (or {@link #UNLABELED}) as the HQL literal to return: a quoted
   *     id for the expression itself, a rank for the order-by
   * @return a {@code case ... end} expression over alias {@code e}
   */
  static String bucketCase(UnaryOperator<String> literal) {
    return "case"
        + kindWhen("goods-receipt", signed(QTY + " < 0", VENDOR_RETURN, VENDOR_RECEIPT, literal))
        + kindWhen("return-to-vendor-shipment", literal.apply(VENDOR_RETURN))
        + kindWhen("goods-shipment", signed(QTY + " > 0", CUSTOMER_RETURN, CUSTOMER_SHIPMENT, literal))
        + kindWhen("return-material-receipt", literal.apply(CUSTOMER_RETURN))
        + kindWhen("goods-movements", literal.apply(TRANSFER))
        + kindWhen(INTERNAL_CONSUMPTION, literal.apply(INTERNAL_CONSUMPTION))
        + kindWhen("physical-inventory", signed(QTY + " < 0", INVENTORY_OUT, INVENTORY_IN, literal))
        + codeWhen("V+", VENDOR_RECEIPT, literal) + codeWhen("V-", VENDOR_RETURN, literal)
        + codeWhen("C-", CUSTOMER_SHIPMENT, literal) + codeWhen("C+", CUSTOMER_RETURN, literal)
        + codeWhen("I+", INVENTORY_IN, literal) + codeWhen("I-", INVENTORY_OUT, literal)
        + codeWhen("M+", TRANSFER, literal) + codeWhen("M-", TRANSFER, literal)
        + codeWhen("D+", INTERNAL_CONSUMPTION, literal) + codeWhen("D-", INTERNAL_CONSUMPTION, literal)
        + " else " + literal.apply(UNLABELED) + " end";
  }

  private static String kindWhen(String kind, String then) {
    return " when " + KIND + " = '" + kind + "' then " + then;
  }

  private static String codeWhen(String code, String bucket, UnaryOperator<String> literal) {
    return " when " + MOVEMENT_TYPE + " = '" + code + "' then " + literal.apply(bucket);
  }

  private static String signed(String condition, String whenTrue, String whenFalse,
      UnaryOperator<String> literal) {
    return "(case when " + condition + " then " + literal.apply(whenTrue) + " else "
        + literal.apply(whenFalse) + " end)";
  }

  /**
   * The bucket ids of the client's order that this class knows, in order, without repeats. Anything
   * else (an unknown id, a blank) is ignored.
   *
   * @param raw the {@value #PARAM_TYPE_ORDER} value
   * @return the valid ids, possibly empty
   */
  static List<String> parseOrder(String raw) {
    Set<String> ids = new LinkedHashSet<>();
    if (raw != null) {
      for (String part : raw.split(",")) {
        String id = part.trim();
        if (BUCKETS.contains(id)) {
          ids.add(id);
        }
      }
    }
    return new ArrayList<>(ids);
  }

  /**
   * The rank of every bucket: the listed ones by position (reversed when descending), the buckets
   * the client did not list after them, the unlabeled rows last in either direction.
   */
  private static UnaryOperator<String> rankOf(List<String> order, boolean descending) {
    int listed = order.size();
    return id -> {
      int index = order.indexOf(id);
      if (index >= 0) {
        return String.valueOf(descending ? listed - 1 - index : index);
      }
      return String.valueOf(UNLABELED.equals(id) ? listed + 1 : listed);
    };
  }

  /**
   * The HQL order-by for a request, or {@code null} when it is not a type-label sort the client
   * described well (then the default order applies).
   *
   * @param sortBy the request {@code _sortBy}: the sort field first, then the tie-breakers
   * @param typeOrder the request {@value #PARAM_TYPE_ORDER}
   * @return the order-by expressions, ending in the record id so the order is total, or {@code null}
   */
  static String buildOrderBy(String sortBy, String typeOrder) {
    if (StringUtils.isBlank(sortBy)) {
      return null;
    }
    String[] elements = sortBy.split(",");
    String first = elements[0].trim();
    boolean descending = first.startsWith("-");
    if (!SORT_FIELD.equals(descending ? first.substring(1) : first)) {
      return null;
    }
    List<String> order = parseOrder(typeOrder);
    if (order.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    parts.add(bucketCase(rankOf(order, descending)) + " asc");
    boolean hasId = false;
    for (int i = 1; i < elements.length; i++) {
      String element = elements[i].trim();
      boolean elementDesc = element.startsWith("-");
      String property = elementDesc ? element.substring(1) : element;
      String path = TIE_BREAKERS.get(property);
      if (path == null) {
        return null;
      }
      hasId |= "id".equals(property);
      parts.add(path + (elementDesc ? " desc" : " asc"));
    }
    if (!hasId) {
      parts.add(ALIAS + "id asc");
    }
    return String.join(", ", parts);
  }
}
