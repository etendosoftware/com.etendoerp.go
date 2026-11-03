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
 * The return to vendor shipment's printable data (ETP-6013): {@code buildReturnToVendorData}, ported on {@link MovementPrintableData}. Only this
 * document's key, entities and own fields live here.
 */
@Dependent
public class ReturnToVendorPrintableData extends MovementPrintableData {

  static final String DOCUMENT = "return-to-vendor-shipment";

  @Override
  public String document() {
    return DOCUMENT;
  }

  @Override
  String headerEntity() {
    return "returnToVendorShipment";
  }

  @Override
  String lineEntity() {
    return "returnToVendorShipmentLine";
  }

  @Override
  void putDocumentFields(JSONObject header, JSONObject data) throws JSONException {
    data.put("sourceReceiptRef", orNull(value(header, "sourceReceiptDocNo")));
  }

  @Override
  void putQuantities(JSONObject line, JSONObject out) throws JSONException {
    out.put("returnedQty", coalesce(value(line, "movementQuantity"), 0));
    out.put("originalQty", coalesce(value(line, "orderQuantity"), 0));
  }
}
