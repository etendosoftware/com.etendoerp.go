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

import static com.etendoerp.go.schemaforge.NeoPrintableCommons.coalesce;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.orNull;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.value;

import javax.enterprise.context.Dependent;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The return material receipt's printable data (ETP-6013): {@code buildReceiptData}, ported on {@link MovementPrintableData}. Only this
 * document's key, entities and own fields live here.
 *
 * <p><b>Safe with D18.</b> A receipt opened under ETP-4408 may still carry the customer's own document
 * marked main (D18 in {@code docs/document-printables.md}). The server render never replaces a main
 * attachment that is not an Etendo-generated PDF ({@link NeoPrintableRenderer#isEtendoPreviewName}),
 * so such a receipt simply gets no server PDF; that is why the window could be declared without
 * D18's data check.</p>
 */
@Dependent
public class ReturnReceiptPrintableData extends MovementPrintableData {

  static final String DOCUMENT = "return-material-receipt";

  @Override
  public String document() {
    return DOCUMENT;
  }

  @Override
  String headerEntity() {
    return "returnMaterialReceipt";
  }

  @Override
  String lineEntity() {
    return "returnMaterialReceiptLine";
  }

  @Override
  void putDocumentFields(JSONObject header, JSONObject data) throws JSONException {
    data.put("sourceShipmentRef", orNull(value(header, "sourceShipmentDocNo")));
  }

  @Override
  void putQuantities(JSONObject line, JSONObject out) throws JSONException {
    out.put("returnedQty", coalesce(value(line, "movementQuantity"), 0));
  }
}
