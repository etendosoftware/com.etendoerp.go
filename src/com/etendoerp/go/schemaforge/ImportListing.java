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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The optional listing parameters every {@code available*} action of a line import shares
 * (ETP-6099): {@code limit}, {@code offset}, {@code includeCovered} and {@code detail}. They let a
 * window's import popup list everything it shows through the action (one call for the documents,
 * one per expanded document for the lines) instead of reading hundreds of company-wide documents
 * and filtering them in the browser. Absent, every action answers exactly as before: the same
 * limits, the same rows, the same keys.
 *
 * <ul>
 *   <li>{@code limit} (1 to {@value #MAX_LIMIT}): how many documents a page returns; the scan reads
 *       at least that many candidates.</li>
 *   <li>{@code offset} (0 or more): where in the candidate scan (newest first) the page starts. With
 *       {@code limit} or {@code offset} sent the page also answers {@code offset} and
 *       {@code nextOffset}: calling again with {@code offset = nextOffset} while {@code hasMore}
 *       is true examines every candidate, so nothing is left out however many there are.</li>
 *   <li>{@code includeCovered}: also the documents / lines with nothing left to import (pending 0),
 *       which the popups show greyed out.</li>
 *   <li>{@code detail}: the extra row keys the popup reads (per action); off by default so an agent
 *       that does not ask for them gets the same, shorter, answer.</li>
 * </ul>
 * Pure: no DAL, no entity named.
 */
final class ImportListing {

  static final String PARAM_LIMIT = "limit";
  static final String PARAM_OFFSET = "offset";
  static final String PARAM_INCLUDE_COVERED = "includeCovered";
  static final String PARAM_DETAIL = "detail";
  static final String PARAM_PENDING_BASIS = "pendingBasis";
  static final String PENDING_BASIS_SERVER = "server";
  static final String PENDING_BASIS_CLASSIC = "classic";
  static final String KEY_CREATION_DATE = "creationDate";
  /** The longest page a caller may ask for. */
  static final int MAX_LIMIT = 1000;

  private ImportListing() {
  }

  /** What one list call asked for. */
  static final class ListRequest {
    final int limit;
    final int offset;
    final int scan;
    final boolean includeCovered;
    final boolean detail;
    /** {@code true} when the caller sent {@code limit} or {@code offset}: the page reports paging. */
    final boolean paged;

    ListRequest(int limit, int offset, int scan, boolean includeCovered, boolean detail,
        boolean paged) {
      this.limit = limit;
      this.offset = offset;
      this.scan = scan;
      this.includeCovered = includeCovered;
      this.detail = detail;
      this.paged = paged;
    }

    /** The request of a caller that sent none of the parameters: the delegate's own limits. */
    boolean isDefault(int defaultScan) {
      return offset == 0 && scan == defaultScan;
    }
  }

  /**
   * Parses the listing parameters of a list action.
   *
   * @param defaultLimit the delegate's row limit, used when {@code limit} is absent
   * @param defaultScan  the delegate's scan limit; the scan reads {@code max(defaultScan, limit)}
   * @throws DocumentLineImport.ImportRefusal 400 {@code importInvalidParameter} on a value out of
   *     range or not a whole number / boolean
   */
  static ListRequest listRequest(JSONObject body, int defaultLimit, int defaultScan) {
    boolean limitSent = present(body, PARAM_LIMIT);
    boolean offsetSent = present(body, PARAM_OFFSET);
    int limit = limitSent ? wholeNumber(body, PARAM_LIMIT, 1, MAX_LIMIT) : defaultLimit;
    int offset = offsetSent ? wholeNumber(body, PARAM_OFFSET, 0, Integer.MAX_VALUE) : 0;
    return new ListRequest(limit, offset, Math.max(defaultScan, limit),
        flag(body, PARAM_INCLUDE_COVERED), flag(body, PARAM_DETAIL), limitSent || offsetSent);
  }

  /**
   * {@code pendingBasis} (ETP-6099 follow-up, product decision): {@code "server"} (the default) is
   * the server's pending rule, drafts deducted; {@code "classic"} is the rule the window's popup
   * computed in the browser before it listed through the actions, kept for the purchase popups so
   * that ETP-6099 changes their speed and not their numbers. Only the actions whose header offers it
   * accept {@code "classic"}; anywhere else it is a 400, never silently ignored.
   *
   * @param offered whether the action of this call, on this header, has a classic rule
   * @return {@code true} for {@code "classic"}
   * @throws DocumentLineImport.ImportRefusal 400 {@code importInvalidParameter} on any other value,
   *     or on {@code "classic"} where it is not offered
   */
  static boolean classicPending(JSONObject body, boolean offered) {
    if (!present(body, PARAM_PENDING_BASIS)) {
      return false;
    }
    Object value = body.opt(PARAM_PENDING_BASIS);
    String text = value instanceof String ? ((String) value).trim() : null;
    if (PENDING_BASIS_SERVER.equals(text)) {
      return false;
    }
    if (PENDING_BASIS_CLASSIC.equals(text)) {
      if (offered) {
        return true;
      }
      throw invalid(PARAM_PENDING_BASIS + " \"" + PENDING_BASIS_CLASSIC
          + "\" is not available on this action");
    }
    throw invalid(PARAM_PENDING_BASIS + " must be \"" + PENDING_BASIS_SERVER + "\" or \""
        + PENDING_BASIS_CLASSIC + "\"");
  }

  /** {@code includeCovered} of a lines action. */
  static boolean includeCovered(JSONObject body) {
    return flag(body, PARAM_INCLUDE_COVERED);
  }

  /** {@code detail} of a lines action. */
  static boolean detail(JSONObject body) {
    return flag(body, PARAM_DETAIL);
  }

  /** An optional boolean: absent or null is {@code false}, anything but a boolean a 400. */
  static boolean flag(JSONObject body, String key) {
    return parseBoolean(body, key, false, DocumentLineImport.ERROR_INVALID_PARAMETER);
  }

  /**
   * The one boolean parser of the import actions (also behind {@link OrderImportRequest}'s flags):
   * absent or JSON null is {@code whenAbsent}; a boolean, or the text {@code true} / {@code false}
   * in any case, is read; anything else is a 400 with {@code errorCode}.
   */
  static boolean parseBoolean(JSONObject body, String key, boolean whenAbsent, String errorCode) {
    if (!present(body, key)) {
      return whenAbsent;
    }
    Object value = body.opt(key);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    String text = String.valueOf(value).trim();
    if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
      return Boolean.parseBoolean(text);
    }
    throw new DocumentLineImport.ImportRefusal(HttpServletResponse.SC_BAD_REQUEST, errorCode,
        key + " must be true or false", null);
  }

  /** A whole number from {@code min} to {@code max}, or a 400 naming the key. */
  static int wholeNumber(JSONObject body, String key, int min, int max) {
    int value;
    try {
      value = new BigDecimal(String.valueOf(body.opt(key)).trim()).intValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      throw invalid(key + " must be a whole number from " + min
          + (max == Integer.MAX_VALUE ? " up" : " to " + max));
    }
    if (value < min || value > max) {
      throw invalid(key + " must be a whole number from " + min
          + (max == Integer.MAX_VALUE ? " up" : " to " + max));
    }
    return value;
  }

  private static boolean present(JSONObject body, String key) {
    return body != null && body.has(key) && !body.isNull(key);
  }

  private static DocumentLineImport.ImportRefusal invalid(String message) {
    return new DocumentLineImport.ImportRefusal(HttpServletResponse.SC_BAD_REQUEST,
        DocumentLineImport.ERROR_INVALID_PARAMETER, message, null);
  }

  // ---------------------------------------------------------------------------
  // Contract parameters
  // ---------------------------------------------------------------------------

  /**
   * The optional parameters of a list action, as its contract declares them.
   *
   * @param detailKeys what {@code detail} adds to a document row, or {@code null} when the action
   *     has no document detail (the parameter is then not declared)
   */
  static NeoActionContract.Param[] listParams(int defaultLimit, String detailKeys) {
    List<NeoActionContract.Param> params = new ArrayList<>(Arrays.asList(
        NeoActionContract.Param.optional(PARAM_LIMIT, NeoActionContract.TYPE_NUMBER,
            "How many documents to list at most, a whole number from 1 to " + MAX_LIMIT
                + "; default " + defaultLimit + ". The scan looks at no fewer candidates than "
                + "that."),
        NeoActionContract.Param.optional(PARAM_OFFSET, NeoActionContract.TYPE_NUMBER,
            "Default 0. Where in the candidate scan (newest first) this page starts. With limit or "
                + "offset sent the answer also carries offset and nextOffset: call again with "
                + "offset = nextOffset while hasMore is true to examine every candidate."),
        coveredParam("documents", "pendingLines 0")));
    if (detailKeys != null) {
      params.add(detailParam(detailKeys));
    }
    return params.toArray(new NeoActionContract.Param[0]);
  }

  /** {@code includeCovered} and {@code detail} of a lines action, after its own parameters. */
  static NeoActionContract.Param[] linesParams(String detailKeys,
      NeoActionContract.Param... own) {
    List<NeoActionContract.Param> params = new ArrayList<>(Arrays.asList(own));
    params.add(coveredParam("lines", "pendingQuantity 0"));
    if (detailKeys != null) {
      params.add(detailParam(detailKeys));
    }
    return params.toArray(new NeoActionContract.Param[0]);
  }

  private static NeoActionContract.Param coveredParam(String what, String shownAs) {
    return NeoActionContract.Param.optional(PARAM_INCLUDE_COVERED, NeoActionContract.TYPE_BOOLEAN,
        "Default false. true also lists the " + what + " with nothing left to import (already "
            + "imported, or held by draft documents) with " + shownAs + ", as the import popup "
            + "shows them greyed out; they cannot be imported.");
  }

  private static NeoActionContract.Param detailParam(String detailKeys) {
    return NeoActionContract.Param.optional(PARAM_DETAIL, NeoActionContract.TYPE_BOOLEAN,
        "Default false. true adds to every row: " + detailKeys + ".");
  }

  // ---------------------------------------------------------------------------
  // Rows
  // ---------------------------------------------------------------------------

  /**
   * The {@code creationDate} key every list action adds to a document row under {@code detail}
   * (ETP-6099): an ISO-8601 instant in UTC ({@code 2026-11-05T09:12:33Z}), or JSON null, so a popup
   * that always sorted by creation date can re-sort a list the server orders by document date.
   */
  static void putCreationDate(JSONObject row, java.util.Date creationDate) throws JSONException {
    putNullable(row, KEY_CREATION_DATE, creationDate == null ? null
        : creationDate.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
  }

  /** Puts {@code value}, or JSON null when it is {@code null}. */
  static void putNullable(JSONObject row, String key, Object value) throws JSONException {
    row.put(key, value == null ? JSONObject.NULL : value);
  }

  /** A % or quantity without trailing zeros nor exponent, sign kept; zero when {@code null}. */
  static BigDecimal trimmed(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }
}
