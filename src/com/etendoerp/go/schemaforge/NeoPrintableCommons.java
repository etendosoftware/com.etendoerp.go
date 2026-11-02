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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Image;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * The shared pieces of the commercial printables' data builders, ported from
 * {@code tools/app-shell/src/windows/custom/shared/documentPdf.js} and
 * {@code tools/app-shell/src/lib/locationAddress.js} (ETP-6013).
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> Each method here is a
 * hand port of a named JavaScript function and must keep returning what that function returns for
 * the same NEO JSON, because the same Handlebars template renders both. JavaScript semantics are
 * kept on purpose where they matter: {@link #truthy} is {@code ||} (an empty string, {@code 0} and
 * {@code false} fall through), {@link #value} is {@code ??} (only absent/null falls through) and
 * {@link #number} is {@code Number()}. A change to {@code documentPdf.js}'s builders that is not
 * mirrored here prints a different server PDF for the same record, silently — the parity test
 * ({@code PrintableDataTest}) pins the data keys the browser builds.</p>
 */
public final class NeoPrintableCommons {

  private static final Logger log = LogManager.getLogger(NeoPrintableCommons.class);

  /** Pixel width of the Verifactu QR, {@code VERIFACTU_QR_PX} in {@code report-html-helpers.js}. */
  static final int VERIFACTU_QR_PX = 400;

  private NeoPrintableCommons() {
  }

  // ── JavaScript value semantics ──────────────────────────────────────────────

  /** {@code obj[key]}, with JSON null read as {@code null}. */
  static Object value(JSONObject obj, String key) {
    if (obj == null || !obj.has(key) || obj.isNull(key)) {
      return null;
    }
    return obj.opt(key);
  }

  /** Whether a value is truthy in JavaScript. */
  static boolean isTruthy(Object v) {
    if (v == null || JSONObject.NULL.equals(v)) {
      return false;
    }
    if (v instanceof String) {
      return !((String) v).isEmpty();
    }
    if (v instanceof Boolean) {
      return (Boolean) v;
    }
    if (v instanceof Number) {
      double d = ((Number) v).doubleValue();
      return d != 0 && !Double.isNaN(d);
    }
    return true;
  }

  /** {@code a || b || ...}: the first truthy value, else the last one (as JS returns it). */
  static Object truthy(Object... values) {
    Object last = null;
    for (Object v : values) {
      if (isTruthy(v)) {
        return v;
      }
      last = v;
    }
    return last;
  }

  /** {@code a ?? b ?? ...}: the first non-null value. */
  static Object coalesce(Object... values) {
    for (Object v : values) {
      if (v != null && !JSONObject.NULL.equals(v)) {
        return v;
      }
    }
    return null;
  }

  /** {@code Number(v)}: {@code null} is 0, a numeric string is parsed, anything else is NaN. */
  static double number(Object v) {
    if (v == null || JSONObject.NULL.equals(v)) {
      return 0;
    }
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    if (v instanceof Boolean) {
      return ((Boolean) v) ? 1 : 0;
    }
    String s = String.valueOf(v).trim();
    if (s.isEmpty()) {
      return 0;
    }
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /** A truthy value as a JSON value, a falsy one as JSON null (the builders' {@code x || null}). */
  static Object orNull(Object v) {
    return isTruthy(v) ? v : JSONObject.NULL;
  }

  /** {@code String(v)} of a value already known to be truthy, or {@code ''}. */
  static String text(Object v) {
    return v == null || JSONObject.NULL.equals(v) ? "" : String.valueOf(v);
  }

  /** A double as JSON: whole values without a fraction, like JavaScript prints them. */
  static Object json(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return JSONObject.NULL;
    }
    if (d == Math.rint(d) && Math.abs(d) < 1e15) {
      return (long) d;
    }
    return d;
  }

  // ── documentPdf.js ──────────────────────────────────────────────────────────

  /**
   * {@code sortDocumentLines}: a copy of the lines, ascending by {@code Number(lineNo) || 0}.
   * Stable, as {@code Array.prototype.sort} is.
   */
  static List<JSONObject> sortDocumentLines(JSONArray linesRaw) {
    List<JSONObject> lines = new ArrayList<>();
    for (int i = 0; i < linesRaw.length(); i++) {
      JSONObject line = linesRaw.optJSONObject(i);
      if (line != null) {
        lines.add(line);
      }
    }
    lines.sort((a, b) -> Double.compare(lineNoOf(a), lineNoOf(b)));
    return lines;
  }

  private static double lineNoOf(JSONObject line) {
    double n = number(value(line, "lineNo"));
    return Double.isNaN(n) ? 0 : n;
  }

  /** {@code resolveProductCode}: SKU, else the product's value, else {@code '—'} (ETP-4941). */
  static Object resolveProductCode(JSONObject line) {
    return truthy(value(line, "productCode"), value(line, "product$_value"), "—");
  }

  /** {@code resolveDocumentCurrencyCode}: the header's currency identifier, or null (D13/D14). */
  static Object resolveDocumentCurrencyCode(JSONObject header) {
    return orNull(value(header, "currency$_identifier"));
  }

  /**
   * {@code computeDiscountBreakdown}: gross (by {@code grossLine}), product net, the signed
   * per-product discount and the total-discount amount — in that order.
   */
  static double[] computeDiscountBreakdown(List<JSONObject> linesRaw, double etgoTotalDiscount,
      java.util.function.ToDoubleFunction<JSONObject> grossLine) {
    double grossAmount = 0;
    double productNetAmount = 0;
    for (JSONObject l : linesRaw) {
      grossAmount += grossLine.applyAsDouble(l);
      productNetAmount += number(coalesce(value(l, "lineNetAmount"), 0));
    }
    double discountPerProduct = grossAmount - productNetAmount;
    double totalDiscountAmt = etgoTotalDiscount > 0
        ? productNetAmount * etgoTotalDiscount / 100 : 0;
    return new double[] { grossAmount, productNetAmount, discountPerProduct, totalDiscountAmt };
  }

  /**
   * {@code fetchDocumentAssets} + {@code buildCompanyFields}: the issuer block and the customer's
   * address lines.
   *
   * @param session           {@code GET /session}'s body ({@link NeoSessionService#resolveSession})
   * @param header            the document header
   * @param bpAddressFallback the summary used when the header has no
   *                          {@code partnerAddress$_identifier} (orders, invoices pass
   *                          {@code header.bpAddress}); may be null
   * @return the company fields, {@code hasCustomerAddress} and {@code customerAddressLines}
   */
  static JSONObject companyFields(JSONObject session, JSONObject header, Object bpAddressFallback)
      throws JSONException {
    Object logoId = value(session, "yourCompanyDocumentImageId");
    String companyLogoDataUrl = isTruthy(logoId) ? imageDataUrl(text(logoId)) : null;
    Object partnerAddress = value(header, "partnerAddress");
    JSONObject partnerLocation = isTruthy(partnerAddress)
        ? NeoPrintableReads.record("contacts", "locationAddress", text(partnerAddress)) : null;

    JSONObject org = session == null ? null : session.optJSONObject("organization");
    List<String> addressLines = buildLocationAddressLines(partnerLocation,
        truthy(value(header, "partnerAddress$_identifier"), bpAddressFallback, null));
    JSONObject out = new JSONObject();
    out.put("companyName", truthy(value(org, "name"), value(header, "organization$_identifier"),
        value(header, "organization"), "Empresa"));
    out.put("companyAddress1", orNull(value(org, "address1")));
    out.put("companyAddress2", orNull(value(org, "address2")));
    out.put("companyCityLine", orNull(value(org, "cityLine")));
    out.put("companyTaxId", orNull(value(org, "taxId")));
    out.put("companyLogoDataUrl", companyLogoDataUrl != null ? companyLogoDataUrl : JSONObject.NULL);
    out.put("hasCustomerAddress", !addressLines.isEmpty());
    out.put("customerAddressLines", new JSONArray(addressLines));
    return out;
  }

  // ── lib/locationAddress.js ──────────────────────────────────────────────────

  /** {@code pickFirst}: the first non-blank value, trimmed when it is a string. */
  static String pickFirst(Object... values) {
    for (Object v : values) {
      if (v instanceof String) {
        String trimmed = ((String) v).trim();
        if (!trimmed.isEmpty()) {
          return trimmed;
        }
        continue;
      }
      if (v != null && !JSONObject.NULL.equals(v)) {
        return String.valueOf(v);
      }
    }
    return "";
  }

  /** {@code buildLocationAddressLines}. */
  static List<String> buildLocationAddressLines(JSONObject location, Object fallbackSummary) {
    String addressLine1 = pickFirst(value(location, "address"), value(location, "addressLine1"));
    String addressLine2 = pickFirst(value(location, "address2"), value(location, "addressLine2"));
    String postalCode = pickFirst(value(location, "postalCode"));
    String city = pickFirst(value(location, "city"), value(location, "cityName"));
    String region = pickFirst(value(location, "region$_identifier"),
        value(location, "regionLabel"), value(location, "region"));
    String country = pickFirst(value(location, "country$_identifier"),
        value(location, "countryLabel"), value(location, "country"));

    String localityLine = joinNonEmpty(" ", postalCode, city);
    String regionCountryLine = joinNonEmpty(", ", region, country);
    List<String> lines = new ArrayList<>();
    for (String line : new String[] { addressLine1, addressLine2, localityLine,
        regionCountryLine }) {
      if (!line.isEmpty()) {
        lines.add(line);
      }
    }
    if (!lines.isEmpty()) {
      return lines;
    }
    String fallbackLine = pickFirst(fallbackSummary, value(location, "name"),
        value(location, "_identifier"));
    if (!fallbackLine.isEmpty()) {
      lines.add(fallbackLine);
    }
    return lines;
  }

  private static String joinNonEmpty(String separator, String... parts) {
    StringBuilder out = new StringBuilder();
    for (String part : parts) {
      if (!part.isEmpty()) {
        if (out.length() > 0) {
          out.append(separator);
        }
        out.append(part);
      }
    }
    return out.toString();
  }

  // ── assets ──────────────────────────────────────────────────────────────────

  /**
   * {@code fetchImageDataUrl}: the logo the session names ({@code CompanyLogoResolver}, D24) as a
   * data URL, or {@code null}.
   */
  static String imageDataUrl(String imageId) {
    OBContext.setAdminMode(true);
    try {
      Image image = OBDal.getInstance().get(Image.class, imageId);
      byte[] bytes = image == null ? null : image.getBindaryData();
      if (bytes == null || bytes.length == 0) {
        return null;
      }
      String mime = StringUtils.defaultIfBlank(image.getMimetype(), "application/octet-stream");
      return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    } catch (RuntimeException e) {
      // The browser's fetchImageDataUrl swallows a failed logo too: the PDF prints without it.
      log.warn("Printable logo unavailable: {}", e.getClass().getSimpleName());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * {@code computeDocumentQrDataUrl({qr_mode:'verifactu', verifactu_qr_url})}: the AEAT URL encoded
   * verbatim (D1 — not even trimmed), level M, as a PNG data URL of {@value #VERIFACTU_QR_PX}px laid
   * out the way node {@code qrcode} lays it out with {@code {width: 400, margin: 1}}: the symbol plus a
   * one-module quiet zone stretched over the whole width (a fractional module size, each pixel taking
   * the module under it), so the printed symbol is the browser's size. The spec's quiet zone is CSS
   * padding in the template (D5).
   *
   * @param url the stored {@code EM_Etvfac_Qr_Url}; blank yields {@code ''} (no QR block, D2)
   * @return the data URL, or {@code ''}
   * @throws WriterException when the text cannot be encoded
   * @throws IOException     when the PNG cannot be written
   */
  static String verifactuQrDataUrl(String url) throws WriterException, IOException {
    if (StringUtils.isBlank(url)) {
      return "";
    }
    Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
    hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
    hints.put(EncodeHintType.MARGIN, 1);
    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
    // Width/height 0: one pixel per module, the one-module margin included.
    BitMatrix modules = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints);
    int size = modules.getWidth();
    BufferedImage png = new BufferedImage(VERIFACTU_QR_PX, VERIFACTU_QR_PX,
        BufferedImage.TYPE_BYTE_BINARY);
    for (int x = 0; x < VERIFACTU_QR_PX; x++) {
      int mx = x * size / VERIFACTU_QR_PX;
      for (int y = 0; y < VERIFACTU_QR_PX; y++) {
        int my = y * size / VERIFACTU_QR_PX;
        png.setRGB(x, y, modules.get(mx, my) ? 0x000000 : 0xFFFFFF);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(png, "png", out);
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
  }

}
