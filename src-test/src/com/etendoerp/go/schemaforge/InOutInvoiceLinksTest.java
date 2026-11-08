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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the invoice ↔ goods-movement link readers of {@link InOutInvoiceLinks}: which arms
 * each direction unions, and how many placeholders the caller must bind.
 *
 * <p>The sales side relates an invoice and a shipment only through the invoice-line ↔
 * shipment-line link (the column or {@code M_MatchSI}); the purchase side still adds the deduced
 * order-line arm. Asserted on structure (placeholders, the order-line join, the match table), never
 * on a copy of the SQL. The partial-receipt behaviour against a real database is
 * {@code InOutInvoiceLinksPartialReceiptIntegrationTest}.
 *
 * @covers com.etendoerp.go.schemaforge.InOutInvoiceLinks
 */
public class InOutInvoiceLinksTest {

  /** The deduced arm: an invoice line and a movement line joined on their order line. */
  private static final String ORDER_LINE_JOIN = "c_orderline_id = ";

  /**
   * Per direction: {@code matchTable, otherMatchTable, placeholders, hasOrderLineArm}. Sales
   * dropped the order-line arm (2 placeholders); purchase keeps it (3, the historical constants).
   */
  private static final Object[][] DIRECTIONS = {
      { InOutInvoiceLinks.MatchTable.SALES, "m_matchinv", 2, false },
      { InOutInvoiceLinks.MatchTable.PURCHASE, "m_matchsi", 3, true },
  };

  @Test
  public void linkReadersUnionTheOrderLineArmOnlyOnThePurchaseSide() {
    for (Object[] row : DIRECTIONS) {
      InOutInvoiceLinks.MatchTable matchTable = (InOutInvoiceLinks.MatchTable) row[0];
      String otherTable = (String) row[1];
      int placeholders = (Integer) row[2];
      boolean orderLineArm = (Boolean) row[3];
      String[] readers = { InOutInvoiceLinks.linkedInOutLineIdsSql(matchTable),
          InOutInvoiceLinks.linkedInvoiceIdsSql(matchTable) };

      for (String sql : readers) {
        String label = matchTable + ": " + sql;
        assertEquals(label, placeholders, countPlaceholders(sql));
        assertEquals(label, orderLineArm, sql.contains(ORDER_LINE_JOIN));
        assertEquals(label, orderLineArm ? 2 : 1, countOccurrences(sql, "UNION"));
        assertTrue(label, sql.contains("JOIN " + matchTable.tableName() + " mt")
            || sql.contains("FROM " + matchTable.tableName() + " mt"));
        assertFalse(label, sql.contains(otherTable));
      }
    }
  }

  /** The params helpers answer what the readers carry; purchase equals the historical constants. */
  @Test
  public void paramsHelpersMatchThePlaceholdersOfEachDirection() {
    for (Object[] row : DIRECTIONS) {
      InOutInvoiceLinks.MatchTable matchTable = (InOutInvoiceLinks.MatchTable) row[0];

      assertEquals(matchTable.toString(),
          countPlaceholders(InOutInvoiceLinks.linkedInOutLineIdsSql(matchTable)),
          InOutInvoiceLinks.linkedInOutLinesParams(matchTable));
      assertEquals(matchTable.toString(),
          countPlaceholders(InOutInvoiceLinks.linkedInvoiceIdsSql(matchTable)),
          InOutInvoiceLinks.linkedInvoicesParams(matchTable));
    }
    assertEquals(InOutInvoiceLinks.LINKED_INOUT_LINES_PARAMS,
        InOutInvoiceLinks.linkedInOutLinesParams(InOutInvoiceLinks.MatchTable.PURCHASE));
    assertEquals(InOutInvoiceLinks.LINKED_INVOICES_PARAMS,
        InOutInvoiceLinks.linkedInvoicesParams(InOutInvoiceLinks.MatchTable.PURCHASE));
  }

  private static int countPlaceholders(String sql) {
    return countOccurrences(sql, "?");
  }

  private static int countOccurrences(String text, String token) {
    int count = 0;
    int from = text.indexOf(token);
    while (from >= 0) {
      count++;
      from = text.indexOf(token, from + token.length());
    }
    return count;
  }
}
