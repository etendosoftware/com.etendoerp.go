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
package com.etendoerp.go.schemaforge.util;

import java.math.BigDecimal;
import java.util.Optional;

import org.openbravo.base.model.Property;

/**
 * Single definition of "these two values are the same one" for the diagnostics that compare what
 * a caller sent with what a callout proposed or the server stored (ETP-5852, ETP-5680).
 *
 * <p>A diagnostic that fires on a difference of spelling — {@code 20} vs {@code "20.00"},
 * {@code false} vs {@code "N"} — is noise the caller has to learn to ignore, and then it ignores
 * the real ones too. Equal as text, as numbers, or as the same Yes/No flag.</p>
 */
public final class NeoValueEquivalence {

  private NeoValueEquivalence() {
  }

  /** Equal as text, or equal as numbers ({@code 20} vs {@code "20.00"}). */
  public static boolean sameTextOrNumber(Object a, Object b) {
    String x = String.valueOf(a);
    String y = String.valueOf(b);
    if (x.equals(y)) {
      return true;
    }
    try {
      return new BigDecimal(x.trim()).compareTo(new BigDecimal(y.trim())) == 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * Equal as Yes/No flags: {@code true}/{@code "true"}/{@code "Y"} are one value and
   * {@code false}/{@code "false"}/{@code "N"} the other. Both sides must be recognisable tokens;
   * anything else is not equal here.
   */
  public static boolean sameBoolean(Object a, Object b) {
    Optional<Boolean> x = canonical(a);
    return x.isPresent() && x.equals(canonical(b));
  }

  /**
   * Text/number equality, or Yes/No equality. {@code same(null, null)} is {@code true}: both
   * render as "null", i.e. both sides are equally absent.
   */
  public static boolean same(Object a, Object b) {
    return sameTextOrNumber(a, b) || sameBoolean(a, b);
  }

  /**
   * Like {@link #same} but decided by the property's type when it is known: a property that is
   * not boolean never gets the Yes/No leniency, so a text field holding {@code "Y"} is not equal
   * to {@code "true"}. With no property ({@code null}) the value-based test stands in.
   *
   * @param prop the DAL property of the field, or {@code null} when it cannot be resolved
   */
  public static boolean same(Object a, Object b, Property prop) {
    if (sameTextOrNumber(a, b)) {
      return true;
    }
    return (prop == null || NeoBooleanFormat.isBooleanProperty(prop)) && sameBoolean(a, b);
  }

  private static Optional<Boolean> canonical(Object value) {
    if (value instanceof Boolean) {
      return Optional.of((Boolean) value);
    }
    return value == null ? Optional.empty() : NeoBooleanFormat.toCanonical(String.valueOf(value));
  }
}
