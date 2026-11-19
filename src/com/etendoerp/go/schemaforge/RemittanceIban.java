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

import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;

/**
 * ETP-6247 — the IBAN of a remittance: its own COPY of the account's IBAN
 * ({@code REM_REMITTANCE.IBAN}, DAL {@value #PROPERTY}), which its SEPA file always carries. The
 * trigger {@code REM_REMITTANCE_TRG} copies the account's IBAN on insert when the remittance has
 * none and, while it is not processed, when its account or type changes; a remittance created with
 * an IBAN keeps it. The IBAN typed on a remittance is never written to the account.
 *
 * <p>Valid means: normalized (upper case, no separators), 15 to 34 characters, the ISO 13616
 * mod-97 check ({@link FinancialAccountCountrySupport#isChecksumValid}). The account's own IBAN is
 * additionally pair-checked against its country ({@link RemittanceHeaderHandler#hasValidIban}),
 * which is the rule the account form enforces.
 */
final class RemittanceIban {

  /** DAL property of {@code REM_REMITTANCE.IBAN} (AD column name {@code IBAN}). */
  static final String PROPERTY = "iBAN";
  static final int MAX_LENGTH = 34;

  private RemittanceIban() {
  }

  /**
   * Upper-cases and strips every non-alphanumeric character.
   *
   * @param raw the IBAN as typed, may be {@code null}
   * @return the normalized IBAN, empty for {@code null}
   */
  static String normalize(String raw) {
    return FinancialAccountCountrySupport.normalizeIban(raw);
  }

  /**
   * Whether {@code raw} is a valid IBAN once normalized.
   *
   * @param raw the IBAN as typed or stored, may be {@code null}
   * @return {@code true} for a well-formed IBAN with correct check digits
   */
  static boolean isValid(String raw) {
    String iban = normalize(raw);
    return iban.length() >= FinancialAccountCountrySupport.IBAN_MIN_LENGTH
        && iban.length() <= MAX_LENGTH && FinancialAccountCountrySupport.isChecksumValid(iban);
  }

  /**
   * The remittance's own copy, normalized, valid or not.
   *
   * @param remittance the remittance, may be {@code null}
   * @return the copy, or {@code null} when none
   */
  static String stored(Remittance remittance) {
    Object value = remittance == null ? null : remittance.get(PROPERTY);
    String iban = value == null ? "" : normalize(value.toString());
    return iban.isEmpty() ? null : iban;
  }

  /**
   * The IBAN the remittance would use: its own copy, else its account's, normalized, valid or
   * not. A present copy is never replaced by the account's.
   *
   * @param remittance the remittance
   * @return the IBAN, or {@code null} when none
   */
  static String effective(Remittance remittance) {
    String own = stored(remittance);
    if (own != null) {
      return own;
    }
    FIN_FinancialAccount account = remittance.getFinancialAccount();
    String fromAccount = account != null ? normalize(account.getIBAN()) : "";
    return fromAccount.isEmpty() ? null : fromAccount;
  }

  /**
   * The IBAN its SEPA file carries: {@link #effective} when valid, else {@code null}. A
   * remittance without a copy gets the account's valid IBAN as its copy, in the caller's
   * transaction, so later edits of the account do not change what a regeneration writes.
   *
   * @param remittance the remittance being generated
   * @return the valid IBAN, or {@code null}
   */
  static String forFile(Remittance remittance) {
    String iban = effective(remittance);
    if (iban == null || !isValid(iban)) {
      return null;
    }
    if (stored(remittance) == null) {
      remittance.set(PROPERTY, iban);
    }
    return iban;
  }

  /**
   * Runs {@code generation} with {@code iban} standing in for the account's IBAN WHEN the account
   * has none. Classic's generator reads the account's IBAN and refuses a blank one, while the file
   * carries the remittance's; the stand-in is set in the session only: pending changes are flushed
   * first, flushing is switched off meanwhile and the original value is restored afterwards, so
   * nothing about the account reaches the database.
   *
   * @param account    the remittance's account, may be {@code null}
   * @param iban       the remittance's valid IBAN
   * @param generation the generator run
   * @param <T>        its result
   * @return what {@code generation} returned
   */
  static <T> T whileAccountHas(FIN_FinancialAccount account, String iban, Supplier<T> generation) {
    if (account == null || iban == null || StringUtils.isNotBlank(account.getIBAN())) {
      return generation.get();
    }
    OBDal dal = OBDal.getInstance();
    dal.flush();
    Session session = dal.getSession();
    FlushMode previous = session.getHibernateFlushMode();
    String original = account.getIBAN();
    session.setHibernateFlushMode(FlushMode.MANUAL);
    try {
      account.setIBAN(iban);
      return generation.get();
    } finally {
      account.setIBAN(original);
      session.setHibernateFlushMode(previous);
    }
  }
}
