/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-5725 (reopened QA review, Sonar java:S1448) — the ETP-5438 "AEAT fixed-width record slot"
 * validation pair (negative-not-allowed boxes, oversized identification fields), extracted out of
 * {@link FiscalDeclCrudHandler} to keep that class under the SonarQube method-count threshold
 * (see {@link FiscalDeclCorrectiveConsistencySupport}'s own javadoc for the full extraction
 * rationale — this class follows the identical pattern).
 */
class FiscalDeclManualDataValidationSupport {

  // ETP-5393 Bug C — boxes the classic AEAT303Report engine hard-rejects when negative:
  // box 111 "Rectificación – Importe" (AEAT303Report2024.java:276-278,
  // @AEAT303_Negative_Not_Allowed_For_111@) and box 77 "IVA a la importación liquidado por la
  // Aduana pendiente de ingreso" (AEAT303Report2015.java:149-162,
  // @AEAT303_Negative_IVA_IMPORT_ADUANA@). The GO previsualización's manualOverrides had no
  // equivalent server-side check at all — unlike setManualDataIfPresent's tolerant handling of a
  // malformed manualData blob, a negative value here is a real business-rule violation the caller
  // must be told about, not silently swallowed.
  //
  // ETP-5438 (AEAT spec audit) — boxes 70, 78, 109 and 110 are ALSO declared "Num" (numérico sin
  // signo / unsigned) in the official Modelo 303 "Diseño de registro" (DR303e26v101 v1.01, the
  // spec bundled with this ticket), exactly like 111 and 77 — the same class of bug ETP-5393 Bug C
  // fixed for those two, just not caught at the time because the audit that found it (casilla-by-
  // casilla cross-check of every editable box's AEAT type against this guard's coverage) hadn't
  // been done yet. Added to the SAME set/mechanism rather than a parallel one, per the AEAT type
  // legend confirmed in the spec's own "Nota" footer on every page: "1. Los campos deben ser A
  // (Alfabético) An (Alfanumérico), Num (Numérico sin signo) o N (Numérico con signo)."
  private static final java.util.Set<String> NEGATIVE_NOT_ALLOWED_BOX_KEYS =
      java.util.Set.of("111", "77", "70", "78", "109", "110");

  private final FiscalDeclCrudHandler owner;

  FiscalDeclManualDataValidationSupport(FiscalDeclCrudHandler owner) {
    this.owner = owner;
  }

  /**
   * Rejects a PUT whose {@code manualData.manualOverrides} sets box 111 or box 77 to a negative
   * value — see {@link #NEGATIVE_NOT_ALLOWED_BOX_KEYS}. A missing/malformed {@code manualData} or
   * {@code manualOverrides} is not this method's concern (left to {@code
   * FiscalDeclCrudHandler#setManualDataIfPresent}'s existing tolerant handling); this only fires
   * when one of the watched boxes is present and parses to a negative number.
   *
   * @return {@code true} if the PUT was rejected (a 400 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  boolean rejectNegativeManualBoxes(JSONObject body, String id, HttpServletResponse response)
      throws IOException {
    if (!body.has(FiscalDeclCrudHandler.MANUAL_DATA_KEY) || body.isNull(FiscalDeclCrudHandler.MANUAL_DATA_KEY)) {
      return false;
    }
    JSONObject manualData = body.optJSONObject(FiscalDeclCrudHandler.MANUAL_DATA_KEY);
    JSONObject overrides = manualData != null ? manualData.optJSONObject("manualOverrides") : null;
    if (overrides == null) {
      return false;
    }
    for (String boxKey : NEGATIVE_NOT_ALLOWED_BOX_KEYS) {
      if (!overrides.has(boxKey) || overrides.isNull(boxKey)) {
        continue;
      }
      double value = overrides.optDouble(boxKey, 0d);
      if (!Double.isNaN(value) && value < 0) {
        owner.servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Box " + boxKey + " does not accept negative values: " + id);
        return true;
      }
    }
    return false;
  }

  // ETP-5438 (AEAT spec audit) — max lengths for the alphanumeric ("An") identification fields,
  // read straight off the official Modelo 303 "Diseño de registro" (DR303e26v101 v1.01): the
  // DID page for the 6 bank_* fields (SWIFT-BIC 11, IBAN 34 — "Nota 6: Para el IBAN español
  // deberá empezar por ES y únicamente se usan las primeras 24 posiciones", Bank name 70, Bank
  // address 35, City 30, Country code 2) and page 3 for nro_justificante (13, "Número
  // justificante identificativo de la autoliquidación anterior"). Same rationale as
  // NEGATIVE_NOT_ALLOWED_BOX_KEYS above: the classic AEAT engine's fixed-width record slots make
  // an oversized value a real business-rule violation, not a cosmetic nit — better to reject it
  // here, where the user can still fix it, than have it silently truncated (or rejected outright)
  // at file-generation time.
  private static final java.util.Map<String, Integer> IDENTIFICATION_MAX_LENGTHS = buildIdentificationMaxLengths();

  private static java.util.Map<String, Integer> buildIdentificationMaxLengths() {
    java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
    m.put("bank_iban", 34);
    m.put("bank_swift_bic", 11);
    m.put("bank_nombre", 70);
    m.put("bank_direccion", 35);
    m.put("bank_ciudad", 30);
    m.put("bank_pais", 2);
    m.put("nro_justificante", 13);
    return java.util.Collections.unmodifiableMap(m);
  }

  // ETP-5438 (AEAT spec audit) — bank_sepa ("Devolución - Marca SEPA") is also declared a
  // single-digit Num field on the DID page restricted to a 4-value enum (spec's own "Nota 2:
  // Devolución marca SEPA" table: 0 Vacía, 1 Cuenta España, 2 Unión Europea SEPA, 3 Resto
  // Países) in the same audit that found the gaps above. That fix (a `rejectInvalidBankSepa`
  // guard here, paired with a `type: 'select'` conversion in fm303Layouts.js) was reverted from
  // this ticket — it is being handled under a separate ticket instead. Do not re-add it here.

  /**
   * Rejects a PUT whose {@code manualData.identification} carries a value longer than its AEAT
   * fixed-width slot (ETP-5438) — see {@link #IDENTIFICATION_MAX_LENGTHS}. A missing/malformed
   * {@code manualData}/{@code identification}, or a field simply absent from the payload, is not
   * this method's concern (same tolerant precedent as {@link #rejectNegativeManualBoxes}).
   *
   * @return {@code true} if the PUT was rejected (a 400 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  boolean rejectOversizedIdentificationFields(JSONObject body, String id,
      HttpServletResponse response) throws IOException {
    JSONObject identification = owner.extractIdentification(body);
    if (identification == null) {
      return false;
    }
    for (java.util.Map.Entry<String, Integer> entry : IDENTIFICATION_MAX_LENGTHS.entrySet()) {
      String key = entry.getKey();
      if (!identification.has(key) || identification.isNull(key)) {
        continue;
      }
      String value = identification.optString(key, "");
      if (value.length() > entry.getValue()) {
        owner.servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Field " + key + " exceeds its max length of " + entry.getValue() + " characters: " + id);
        return true;
      }
    }
    return false;
  }
}
