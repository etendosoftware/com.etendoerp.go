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

import javax.enterprise.context.Dependent;

/**
 * The purchase order's printable data (ETP-6013): {@code buildOrderData('purchase-order', ...)}, ported by
 * {@link OrderPrintableData}. Only the document key lives here — the window's identity stays in its
 * own class, never in shared code. Its labels ({@code buildPurchaseOrderPdfLabels}) come from the published manifest.
 */
@Dependent
public class PurchaseOrderPrintableData extends OrderPrintableData {

  static final String DOCUMENT = "purchase-order";

  @Override
  public String document() {
    return DOCUMENT;
  }
}
