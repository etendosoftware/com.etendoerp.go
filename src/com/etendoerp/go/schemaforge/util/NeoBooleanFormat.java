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

import java.util.Optional;

import org.openbravo.base.model.Property;

/**
 * Single definition of the boolean format NEO speaks over JSON (ETP-4793).
 *
 * <p>NEO's JSON contract is a real JSON <b>boolean</b> in both directions. Etendo's storage
 * layer, by contrast, is {@code char(1) 'Y'/'N'}, and the legacy machinery that feeds
 * {@code /defaults} — AD_Column default expressions, callout responses, combo option values —
 * hands those raw {@code "Y"}/{@code "N"} strings straight through. A response that mixes both
 * shapes is a broken contract for any consumer that trusts the declared type: in JavaScript the
 * string {@code "N"} is <b>truthy</b>, so an agent reading {@code {"printDiscount": "N"}} sees
 * "discount printing is on".
 *
 * <p>This was observed as a per-producer inconsistency, not a per-spec one: on
 * {@code sales-invoice/header} {@code printDiscount} came back as {@code true} while
 * {@code etvfacSentToVerifac} came back as {@code "N"}, and on {@code purchase-invoice/header}
 * the two were the other way round. Which shape a field gets depends on which producer last
 * wrote it, and callouts differ per window.
 *
 * <h2>Two deliberately different parses</h2>
 *
 * <p>{@link #toCanonical(String)} is <b>strict</b>: it recognises the four shapes Etendo
 * actually produces and returns {@code null} for anything else, so the caller can leave an
 * unrecognised value verbatim and log it. It backs the read path, where silently turning an
 * unknown string into {@code false} would invent a value the ERP never stated.
 *
 * <p>{@link #toLenientBoolean(String)} preserves the pre-existing <b>write</b>-path semantics:
 * anything that is not recognised as true becomes {@code false}. That leniency predates this
 * change and is kept as-is on purpose — tightening it would reject payloads that agents send
 * today. What this class does fix on the write path is that the two coercers disagreed:
 * {@code McpToolRouterSupport} accepted {@code "y"} (case-insensitive) while
 * {@code NeoTypeCoercionHelper} did not, so the same payload coerced differently depending on
 * whether it arrived over MCP or REST.
 *
 * @see NeoDateFormat the same single-definition treatment for date values (IMP-16)
 */
public final class NeoBooleanFormat {

  private NeoBooleanFormat() {
  }

  /**
   * Tells whether a DAL property is boolean-valued, and therefore whether its JSON
   * representation should be a JSON boolean.
   *
   * <p>Uses {@code isAssignableFrom} rather than reference identity. {@code Boolean} is
   * {@code final} so the two cannot diverge today, but identity was the predicate on one of the
   * two write paths and {@code isAssignableFrom} on the other — a gratuitous difference between
   * two copies of the same decision, which is exactly what this class exists to remove.
   *
   * @param prop the DAL property, may be {@code null}
   * @return {@code true} when {@code prop} is a primitive property whose Java type is
   *         {@code Boolean}
   */
  public static boolean isBooleanProperty(Property prop) {
    if (prop == null || !prop.isPrimitive()) {
      return false;
    }
    Class<?> type = prop.getPrimitiveObjectType();
    return type != null && Boolean.class.isAssignableFrom(type);
  }

  /**
   * Strict parse of the shapes Etendo produces for a boolean, for the <b>read</b> path.
   *
   * <p>Recognises {@code "Y"}/{@code "N"} (the storage encoding) and
   * {@code "true"}/{@code "false"} (already-JSON-ish values that some producers emit as text),
   * both case-insensitively and ignoring surrounding whitespace.
   *
   * @param value the raw string, may be {@code null}
   * @return {@link Optional#of} {@link Boolean#TRUE} or {@link Boolean#FALSE} for a recognised
   *         shape, or {@link Optional#empty()} when the value is not a boolean this method knows
   *         how to read — the caller must then leave it untouched rather than guess
   */
  public static Optional<Boolean> toCanonical(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String v = value.trim();
    if ("Y".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v)) {
      return Optional.of(Boolean.TRUE);
    }
    if ("N".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
      return Optional.of(Boolean.FALSE);
    }
    return Optional.empty();
  }

  /**
   * Lenient parse for the <b>write</b> path: recognised-true yields {@code true}, everything
   * else yields {@code false}.
   *
   * <p>This is the historical behaviour of both write coercers, unified here so they agree on
   * case sensitivity. Do not use it on the read path — see {@link #toCanonical(String)}.
   *
   * @param value the raw string, may be {@code null}
   * @return {@code true} only for {@code "Y"} / {@code "true"} (case-insensitive)
   */
  public static boolean toLenientBoolean(String value) {
    return toCanonical(value).orElse(Boolean.FALSE);
  }
}
