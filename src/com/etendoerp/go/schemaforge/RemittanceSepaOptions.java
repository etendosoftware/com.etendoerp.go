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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.sepa.iso20022.util.SEPAUtil;

/**
 * ETP-6247 — the two SEPA direct debit options a COLLECTION remittance carries on its own,
 * {@code REM_REMITTANCE.DD_SCHEME} ({@value #SCHEME_CORE} | {@value #SCHEME_B2B}, DAL
 * {@value #PROPERTY_SCHEME}) and {@code REM_REMITTANCE.ISFINANCED} (DAL {@value #PROPERTY_FINANCED}).
 *
 * <p>Classic keeps them as parameters of the remittance TYPE ({@code CORE_B2B} and
 * {@code SEPA_DD_Financed_58}, read by {@code CoreDirectDebit}); here they are per remittance and
 * the generator output is rewritten by structure ({@link RemittanceCreditorIdRewriter#applyOptions}).
 * Known cost: Classic's own «Create File» button ignores the columns and writes what the type says.
 * They only apply to a collection: on a payment they are not offered and a value is refused.
 */
final class RemittanceSepaOptions {

  static final String PROPERTY_SCHEME = "directDebitScheme";
  static final String PROPERTY_FINANCED = "financed";
  static final String SCHEME_CORE = "CORE";
  static final String SCHEME_B2B = "B2B";

  /**
   * The prefix of the message identifier of a financed remittance when the remittance type does not
   * carry its own {@code SEPA_DD_Financed_58} value. PROVISIONAL: the real text is still to be
   * decided; this is the only place to change it.
   */
  static final String FINANCED_PREFIX = "FSDD";
  /** Classic's remittance type parameter whose value prefixes the message identifier. */
  static final String TYPE_PARAM_FINANCED = "SEPA_DD_Financed_58";
  static final int MSG_ID_MAX_LENGTH = 35;

  private RemittanceSepaOptions() {
  }

  /**
   * The scheme as typed, normalized.
   *
   * @param raw the value, may be {@code null}
   * @return {@value #SCHEME_CORE}, {@value #SCHEME_B2B}, or {@code null} when it is neither
   */
  static String normalizeScheme(String raw) {
    String value = StringUtils.trimToEmpty(raw).toUpperCase(Locale.ROOT);
    return SCHEME_CORE.equals(value) || SCHEME_B2B.equals(value) ? value : null;
  }

  /**
   * The remittance's scheme; a collection without a stored one is {@value #SCHEME_CORE}.
   *
   * @param remittance the remittance
   * @return {@value #SCHEME_CORE} or {@value #SCHEME_B2B}
   */
  static String scheme(Remittance remittance) {
    Object value = remittance.get(PROPERTY_SCHEME);
    String scheme = value != null ? normalizeScheme(value.toString()) : null;
    return scheme != null ? scheme : SCHEME_CORE;
  }

  /**
   * Whether the remittance is financed.
   *
   * @param remittance the remittance
   * @return {@code true} when flagged
   */
  static boolean financed(Remittance remittance) {
    Object value = remittance.get(PROPERTY_FINANCED);
    return Boolean.TRUE.equals(value) || "Y".equals(value);
  }

  /**
   * The prefix of the message identifier of a financed remittance: the type's own
   * {@code SEPA_DD_Financed_58} value when it has one (the generator already writes it), else
   * {@value #FINANCED_PREFIX}.
   *
   * @param remittance the remittance
   * @return the prefix
   */
  static String financedPrefix(Remittance remittance) {
    String own = remittance.getRemittanceType() != null
        ? StringUtils.trimToNull(SEPAUtil.getStringPropertyValue(remittance, TYPE_PARAM_FINANCED))
        : null;
    return own != null ? own : FINANCED_PREFIX;
  }
}
