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

import static com.etendoerp.go.schemaforge.NeoPrintableCommons.isTruthy;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.orNull;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.text;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.truthy;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.value;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The movement printables' data (goods shipment, both returns) — the Java port of
 * {@code buildShipmentData} ({@code goods-shipment/useShipmentPdf.js}), {@code buildReturnToVendorData}
 * ({@code return-to-vendor-shipment/useReturnToVendorPdf.js}) and {@code buildReceiptData}
 * ({@code return-material-receipt/useReturnReceiptPdf.js}) (ETP-6013).
 *
 * <p>The three share everything but their reads, one header field and the quantity columns: the issuer
 * block comes from the header's {@code issuerOrg} (an enrichment of the READ customization, which
 * {@link NeoPrintableReads} runs like a REST GET) as {@code buildReturnDocCommonFields} reads it, the
 * logo from the session, the lines are re-numbered {@code 1..n} after sorting and their code falls back
 * to the position, as the browser does. The concrete builders name their document, entities and the
 * per-document fields.</p>
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> A key added to, renamed
 * in or recomputed by one of the three JS builders MUST be mirrored here by hand; the parity fixtures
 * shared with {@code printableSources.vitest.js} pin them ({@code PrintableDataTest}).</p>
 */
public abstract class MovementPrintableData implements NeoPrintableDataBuilder {

  /** The page the browser reads the lines with. */
  static final Map<String, String> LINE_PAGE = Map.of("_startRow", "0", "_endRow", "200");

  /** @return the header entity of the spec (e.g. {@code goodsShipment}) */
  abstract String headerEntity();

  /** @return the line entity of the spec (e.g. {@code goodsShipmentLine}) */
  abstract String lineEntity();

  /** The document-specific header fields. */
  abstract void putDocumentFields(JSONObject header, JSONObject data) throws JSONException;

  /** The document-specific quantity columns of one line. */
  abstract void putQuantities(JSONObject line, JSONObject out) throws JSONException;

  @Override
  public JSONObject build(String recordId) throws PrintableDataException {
    JSONObject header = NeoPrintableReads.readRecord(document(), headerEntity(), recordId);
    if (header == null) {
      throw new PrintableDataException("The document could not be read");
    }
    JSONArray linesRaw = NeoPrintableReads.children(document(), lineEntity(), recordId, LINE_PAGE);
    NeoResponse sessionResponse = NeoSessionService.resolveSession();
    JSONObject session = sessionResponse.getHttpStatus() < 400 ? sessionResponse.getBody() : null;
    Object partnerAddress = value(header, "partnerAddress");
    JSONObject partnerLocation = isTruthy(partnerAddress)
        ? NeoPrintableReads.readRecord("contacts", "locationAddress", text(partnerAddress)) : null;
    Object logoId = value(session, "yourCompanyDocumentImageId");
    String logo = isTruthy(logoId) ? NeoPrintableCommons.imageDataUrl(text(logoId)) : null;
    try {
      return build(header, linesRaw, partnerLocation, logo);
    } catch (JSONException e) {
      throw new PrintableDataException("The document data could not be built", e);
    }
  }

  /**
   * The pure half: the JS builder's result from its reads.
   *
   * @param partnerLocation {@code contacts/locationAddress/<header.partnerAddress>}, or null
   * @param logoDataUrl     the session's logo as a data URL, or null
   * @return the data
   */
  JSONObject build(JSONObject header, JSONArray linesRaw, JSONObject partnerLocation,
      String logoDataUrl) throws JSONException {
    JSONObject org = header.optJSONObject("issuerOrg");
    JSONObject data = new JSONObject();
    // buildReturnDocCommonFields (the shipment builder spells out the same fields)
    data.put("companyName", truthy(value(org, "name"), value(header, "organization$_identifier"),
        "Empresa"));
    data.put("companyAddress1", orNull(value(org, "address1")));
    data.put("companyAddress2", orNull(value(org, "address2")));
    data.put("companyCityLine", orNull(value(org, "cityLine")));
    data.put("companyTaxId", orNull(value(org, "taxId")));
    data.put("companyLogoDataUrl", logoDataUrl != null ? logoDataUrl : JSONObject.NULL);
    data.put("documentNo", truthy(value(header, "documentNo"), ""));
    data.put("movementDate", truthy(value(header, "movementDate"), ""));
    data.put("customerName", truthy(value(header, "businessPartner$_identifier"), "—"));
    data.put("customerAddressLines", new JSONArray(NeoPrintableCommons.buildLocationAddressLines(
        partnerLocation, truthy(value(header, "partnerAddress$_identifier"), null))));
    data.put("warehouse", orNull(value(header, "warehouse$_identifier")));
    data.put("notes", orNull(value(header, "description")));
    putDocumentFields(header, data);

    List<JSONObject> sorted = NeoPrintableCommons.sortDocumentLines(linesRaw);
    JSONArray lines = new JSONArray();
    for (int idx = 0; idx < sorted.size(); idx++) {
      JSONObject l = sorted.get(idx);
      JSONObject line = new JSONObject();
      line.put("lineNo", idx + 1);
      line.put("productCode", truthy(value(l, "productCode"), value(l, "product$_value"),
          String.valueOf(idx + 1)));
      line.put("productName", truthy(value(l, "product$_identifier"), value(l, "description"), "—"));
      putQuantities(l, line);
      lines.put(line);
    }
    data.put("lines", lines);
    return data;
  }
}
