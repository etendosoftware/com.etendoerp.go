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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess.classic;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.supportaccess.SupportAccessException;
import com.etendoerp.go.supportaccess.SupportAccessService;
import com.etendoerp.go.supportaccess.SupportTenantBusyException;

/**
 * ETP-5351 — what the Classic support processes say to the operator: the {@code AD_Message}
 * search key and parameters for every {@link SupportAccessException} code, plus the HTML and the
 * client action that hand the GO link over.
 *
 * <p>Classic renders process messages as HTML, so every value that does not come from our own
 * messages (operator names, the URL) is escaped here.</p>
 */
public final class SupportAccessMessages {

  /** Reason missing or shorter than {@link SupportAccessService#MIN_REASON_LENGTH}. */
  public static final String MSG_REASON_REQUIRED = "ETGO_SupportReasonRequired";
  /** Duration not positive or above {@code ETGO_SupportSessionMaxMinutes}. */
  public static final String MSG_DURATION_INVALID = "ETGO_SupportDurationInvalid";
  /** The company cannot be entered (inactive, System, template or unclaimed pool tenant). */
  public static final String MSG_TARGET_NOT_ELIGIBLE = "ETGO_SupportTargetNotEligible";
  /** The user is not logged in with the support role. */
  public static final String MSG_OPERATOR_NOT_ALLOWED = "ETGO_SupportOperatorNotAllowed";
  /** The company has no active client-admin role. */
  public static final String MSG_NO_ADMIN_ROLE = "ETGO_SupportNoAdminRole";
  /** The technical support account is missing (the seed did not run). */
  public static final String MSG_ACCOUNT_MISSING = "ETGO_SupportAccountMissing";
  /** No open support access to close. */
  public static final String MSG_ACCESS_NOT_OPEN = "ETGO_SupportAccessNotOpen";
  /** Another operator holds the company: operator, until HH:mm, minutes left. */
  public static final String MSG_TENANT_BUSY = "ETGO_SupportTenantBusy";
  /** Another operator holds the company, expiry unknown: operator. */
  public static final String MSG_TENANT_BUSY_NO_EXPIRY = "ETGO_SupportTenantBusyNoExpiry";
  /** Label used when the operator holding the company has no name. */
  public static final String MSG_UNKNOWN_OPERATOR = "ETGO_SupportUnknownOperator";
  /** No public GO app URL is configured, so the access link cannot be built. */
  public static final String MSG_APP_URL_MISSING = "ETGO_SupportAppUrlMissing";
  /** Access granted: minutes, link. */
  public static final String MSG_ACCESS_ISSUED = "ETGO_SupportAccessIssued";
  /** Text of the fallback link: pass lifetime in seconds. */
  public static final String MSG_ACCESS_LINK = "ETGO_SupportAccessLink";
  /** The open support access was closed. */
  public static final String MSG_ACCESS_REVOKED = "ETGO_SupportAccessRevoked";
  /** Unexpected failure; details only in the log. */
  public static final String MSG_ACCESS_FAILED = "ETGO_SupportAccessFailed";

  /** Name of the core client action that runs a script ({@code ob-utilities-action-def.js}). */
  public static final String CLIENT_ACTION_CUSTOM = "custom";
  /** Parameter of {@link #CLIENT_ACTION_CUSTOM} holding the script. */
  public static final String CLIENT_ACTION_FUNCTION = "func";

  private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm",
      Locale.ROOT);
  /** An absolute http(s) URL without characters that could break out of an attribute. */
  private static final Pattern SAFE_HTTP_URL = Pattern.compile("^https?://[^\\s\"'<>\\\\`]+$",
      Pattern.CASE_INSENSITIVE);
  private static final long SECONDS_PER_MINUTE = 60L;

  private SupportAccessMessages() {
  }

  /**
   * An {@code AD_Message} search key with its {@code %n} parameters, already HTML-safe.
   */
  public static final class Text {
    private final String key;
    private final String[] params;

    Text(String key, String... params) {
      this.key = key;
      this.params = params;
    }

    /** @return the {@code AD_Message.Value} */
    public String getKey() {
      return key;
    }

    /** @return the {@code %0}, {@code %1}… values, in order */
    public String[] getParams() {
      return params.clone();
    }
  }

  /**
   * The message for a refused support action.
   *
   * @param error      the refusal
   * @param maxMinutes the current {@code ETGO_SupportSessionMaxMinutes}, for the duration message
   * @param now        the current instant, for the minutes left of a busy company
   * @param zone       the zone the busy-until time is shown in
   * @param labels     resolves an {@code AD_Message} key to its text (for the unknown operator)
   * @return the message key and its parameters
   */
  public static Text forError(SupportAccessException error, int maxMinutes, Instant now,
      ZoneId zone, UnaryOperator<String> labels) {
    if (error instanceof SupportTenantBusyException) {
      return busy((SupportTenantBusyException) error, now, zone, labels);
    }
    String code = error.getCode() == null ? "" : error.getCode();
    switch (code) {
      case SupportAccessException.CODE_REASON_REQUIRED:
        return new Text(MSG_REASON_REQUIRED,
            String.valueOf(SupportAccessService.MIN_REASON_LENGTH));
      case SupportAccessException.CODE_DURATION_INVALID:
        return new Text(MSG_DURATION_INVALID, String.valueOf(maxMinutes));
      case SupportAccessException.CODE_TARGET_NOT_ELIGIBLE:
        return new Text(MSG_TARGET_NOT_ELIGIBLE);
      case SupportAccessException.CODE_OPERATOR_NOT_ALLOWED:
        return new Text(MSG_OPERATOR_NOT_ALLOWED);
      case SupportAccessException.CODE_NO_ADMIN_ROLE:
        return new Text(MSG_NO_ADMIN_ROLE);
      case SupportAccessException.CODE_SUPPORT_ACCOUNT_MISSING:
        return new Text(MSG_ACCOUNT_MISSING);
      case SupportAccessException.CODE_ACCESS_NOT_OPEN:
        return new Text(MSG_ACCESS_NOT_OPEN);
      default:
        return new Text(MSG_ACCESS_FAILED);
    }
  }

  /**
   * Whether the operator can fix the refusal in the parameter popup (so it stays open).
   *
   * @param code a {@link SupportAccessException} code
   * @return {@code true} for a missing reason or an invalid duration
   */
  public static boolean isFixableInPopup(String code) {
    return SupportAccessException.CODE_REASON_REQUIRED.equals(code)
        || SupportAccessException.CODE_DURATION_INVALID.equals(code);
  }

  private static Text busy(SupportTenantBusyException busy, Instant now, ZoneId zone,
      UnaryOperator<String> labels) {
    String operator = StringUtils.isBlank(busy.getOperatorName())
        ? labels.apply(MSG_UNKNOWN_OPERATOR)
        : busy.getOperatorName();
    String safeOperator = escapeHtml(operator);
    Instant until = busy.getBusyUntil();
    if (until == null) {
      return new Text(MSG_TENANT_BUSY_NO_EXPIRY, safeOperator);
    }
    long secondsLeft = Math.max(0L, Duration.between(now, until).getSeconds());
    long minutesLeft = (secondsLeft + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE;
    return new Text(MSG_TENANT_BUSY, safeOperator, HOUR_MINUTE.format(until.atZone(zone)),
        String.valueOf(minutesLeft));
  }

  /**
   * Whether a URL can be handed to the browser: absolute {@code http}/{@code https}, no quotes,
   * spaces, angle brackets, backslashes nor backticks. Rejects {@code javascript:} and friends.
   *
   * @param url the candidate URL, may be null
   * @return {@code true} when it is safe to open and to put in an {@code href}
   */
  public static boolean isSafeHttpUrl(String url) {
    return url != null && SAFE_HTTP_URL.matcher(url).matches();
  }

  /**
   * An anchor that opens the URL in a new tab, without giving it a handle on Classic.
   *
   * @param url   a URL accepted by {@link #isSafeHttpUrl(String)}
   * @param label the visible text
   * @return the HTML anchor
   */
  public static String link(String url, String label) {
    return "<a href=\"" + escapeHtml(url) + "\" target=\"_blank\" rel=\"noopener noreferrer\">"
        + escapeHtml(label) + "</a>";
  }

  /**
   * The script the core {@value #CLIENT_ACTION_CUSTOM} client action runs to open the URL in a
   * new tab. {@code noopener} keeps the new tab from reaching back into Classic.
   *
   * @param url a URL accepted by {@link #isSafeHttpUrl(String)}
   * @return the script
   */
  public static String openInNewTabScript(String url) {
    return "window.open(" + JSONObject.quote(url) + ", '_blank', 'noopener,noreferrer');";
  }

  /**
   * Escapes the five HTML-significant characters.
   *
   * @param value the raw text, may be null
   * @return the escaped text, empty for null
   */
  public static String escapeHtml(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder escaped = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      switch (c) {
        case '&':
          escaped.append("&amp;");
          break;
        case '<':
          escaped.append("&lt;");
          break;
        case '>':
          escaped.append("&gt;");
          break;
        case '"':
          escaped.append("&quot;");
          break;
        case '\'':
          escaped.append("&#39;");
          break;
        default:
          escaped.append(c);
      }
    }
    return escaped.toString();
  }
}
