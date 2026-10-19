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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * The Modelo 390 incident rules (ETP-5716) — pure: every input is in {@link Facts}, loaded by
 * {@link Fiscal390IncidentsSupport}. The backend is the single source of truth: the page lists
 * what this class returns, and the {@code generate} / {@code submit} guards refuse while
 * {@link #evaluate} returns a {@link #SEVERITY_BLOCK} incident.
 *
 * <p>A rule is <b>blocking</b> when classic {@code org.openbravo.module.aeat390.es} rejects (or
 * crashes on) the same data when it generates the file, or when the AEAT rejects it; otherwise it
 * is a <b>warning</b>. The classic source of each rule is named on its code constant.
 *
 * <p>Each incident carries a {@code code} (the frontend translates it by code), its
 * {@code params} for the text, an English fallback {@code text} and an optional {@code action} — where the user fixes it: {@code {type: box, box}} ("Ir a la casilla"),
 * {@code {type: sources, box}} ("Ver facturas"), {@code {type: organization, section}} ("Editar
 * en Organización") or {@code {type: invoices, sales, purchase}} (the invoice lists).
 */
final class Fiscal390IncidentRules {

  static final String SEVERITY_BLOCK = FiscalDeclCrudHandler.SEVERITY_BLOCK;
  static final String SEVERITY_WARN = FiscalDeclCrudHandler.SEVERITY_WARN;

  // ── Blocking ─────────────────────────────────────────────────────────────
  /** Classic {@code AEAT390_NoOrgMainActivity} (checkData) / {@code .get(0)} of the TXT page 1. */
  static final String NO_MAIN_IAE = "NO_MAIN_IAE";
  /** Classic {@code AEAT390_Activity_Code_Mandatory} (AEAT390Report2022.getActivityCode). */
  static final String IAE_WITHOUT_CODE = "IAE_WITHOUT_CODE";
  /** Classic writes the main + 5 other activity codes into a 6-slot array: a 6th one crashes. */
  static final String TOO_MANY_IAE = "TOO_MANY_IAE";
  /** {@link Fiscal390BoxesCalculator#WARNING_UNSUPPORTED_RATE}: classic aborts on that rate. */
  static final String UNSUPPORTED_RATE = Fiscal390BoxesCalculator.WARNING_UNSUPPORTED_RATE;
  /** The boxes could not be computed at all (classic would fail the same way). */
  static final String COMPUTE_FAILED = "COMPUTE_FAILED";
  /** Classic {@code AEAT390_NoSuplementaryNumber}; the AEAT needs the 13-digit identifier. */
  static final String SUBSTITUTE_ID_INVALID = "SUBSTITUTE_ID_INVALID";
  /** Classic {@code AEAT390_99_Percentage_Individual}. */
  static final String TERRITORY_PERCENT_MAX = "TERRITORY_PERCENT_MAX";
  /** Classic {@code AEAT390_100_Percentage_Sum}. */
  static final String TERRITORY_PERCENT_SUM = "TERRITORY_PERCENT_SUM";
  /** Classic {@code AEAT390_NoOrgTaxId} (generateDatIdent). */
  static final String NO_ORG_NIF = "NO_ORG_NIF";
  /** Classic DB check {@code EM_AEAT390_ISPERSON_MAND_CH}: first name and first surname. */
  static final String PERSON_NAME_MISSING = "PERSON_NAME_MISSING";
  /** Classic DB check {@code EM_AEAT390_REPR_FIS_CH}: representative NIF and name together. */
  static final String PERSON_REPRESENTATIVE_INCOMPLETE = "PERSON_REPRESENTATIVE_INCOMPLETE";
  /** Classic {@code AEAT390_AmtPreviousLiqAnualYearNegative} / {@code AmtPreviousYearNegative}. */
  static final String COMPENSATION_NEGATIVE = "COMPENSATION_NEGATIVE";
  /** Classic {@code AEAT390_BothTerritoryAndLiqAnual}: [85] with a territory percentage. */
  static final String COMPENSATION_WITH_TERRITORY = "COMPENSATION_WITH_TERRITORY";
  /** Classic {@code AEAT390_BothAmtPrevious}: [85] together with [93]. */
  static final String COMPENSATION_BOTH = "COMPENSATION_BOTH";
  /** Classic {@code AEAT390_MonthlyReturn_Check}: DEVOLVER_xx without ISMONTHLYRETURN. */
  static final String REFUND_WITHOUT_MONTHLY_REGISTER = "REFUND_WITHOUT_MONTHLY_REGISTER";
  /** Classic {@code AEAT390_TotalLastPeriod_Check}: DEVOLVER_12 together with [97]. */
  static final String LAST_PERIOD_REFUND_CONFLICT = "LAST_PERIOD_REFUND_CONFLICT";

  // ── Warnings ─────────────────────────────────────────────────────────────
  /** Invoices of the exercise not posted: classic (and the boxes) leave them out. */
  static final String UNPOSTED_INVOICES = "UNPOSTED_INVOICES";
  /** Periods of the exercise with no Modelo 303 ({@code Fiscal390PreloadAggregator.W_MISSING}). */
  static final String MISSING_303 = Fiscal390PreloadAggregator.W_MISSING;
  /** The result differs from the 303s beyond the tolerance ({@code reconcile}). */
  static final String DIFF_303 = "DIFF_303";
  /** An inactive tax rate of the 390 configuration with posted operations in the exercise. */
  static final String INACTIVE_TAX_WITH_OPERATIONS = "INACTIVE_TAX_WITH_OPERATIONS";
  /** A legal entity with no active legal representative. */
  static final String NO_LEGAL_REPRESENTATIVE = "NO_LEGAL_REPRESENTATIVE";
  /** An identity text longer than the length classic cuts it to in the file. */
  static final String NAME_TRUNCATED = "NAME_TRUNCATED";
  /** AEAT instructions: [98] must be empty when [96] or [97] has an amount. */
  static final String BOX98_WITH_96_97 = "BOX98_WITH_96_97";
  /** {@link Fiscal390BoxesCalculator#WARNING_NO_BOX}: a figure the form has no box for. */
  static final String NO_BOX = Fiscal390BoxesCalculator.WARNING_NO_BOX;

  /** Classic writes at most this many non-main IAE activities. */
  static final int MAX_OTHER_ACTIVITIES = 5;
  /** Classic writes at most this many legal representatives. */
  static final int MAX_REPRESENTATIVES = 3;
  /** Length of the former declaration identifier ({@code SUPPLEMENTARYNO}). */
  static final int FORMER_STATEMENT_LENGTH = 13;
  static final BigDecimal TERRITORY_MAX = new BigDecimal("99");
  static final BigDecimal HUNDRED = new BigDecimal("100");

  /** Classic cut lengths ({@code new AEAT390String(text, N)} in AEAT390Report2014). */
  static final int LIMIT_SOCIAL_NAME = 37;
  static final int LIMIT_PERSON_NAME = 15;
  static final int LIMIT_PERSON_REPRESENTATIVE = 37;
  static final int LIMIT_LEGAL_REPRESENTATIVE = 36;

  static final String ACTION_BOX = "box";
  static final String ACTION_SOURCES = "sources";
  static final String ACTION_ORGANIZATION = "organization";
  static final String ACTION_INVOICES = "invoices";
  /** Organization window sections ({@code /organization#<section>}). */
  static final String SECTION_IDENTITY = "identity";
  static final String SECTION_IAE = "iae";
  static final String SECTION_390 = "modelo390";

  private static final String[] TERRITORY_KEYS =
      { "COMMON_TERRITORY", "ALAVA", "GUIPUZCOA", "VIZCAYA", "NAVARRA" };
  private static final int[] TERRITORY_BOXES = { 87, 88, 89, 90, 91 };
  private static final String KEY_COMPENSATION = Fiscal390DerivedBoxes.LIQANUAL_COMPENSATION;
  private static final String KEY_TERRITORY_COMPENSATION =
      Fiscal390DerivedBoxes.COMMON_TERRITORY_COMPENSATION;
  private static final String KEY_LAST_PERIOD = Fiscal390PreloadAggregator.TOTAL_LASTPERIOD;
  private static final String KEY_LAST_PERIOD_REFUND =
      Fiscal390PreloadAggregator.TOTAL_LASTPERIOD_DEVOLVER;
  private static final String KEY_MONTHLY_RETURN = "ISMONTHLYRETURN";
  private static final String KEY_SUPPLEMENTARY = "ISSUPPLEMENTARY";
  private static final String KEY_RECTIFICATION = "ISRECTIFICATION";
  private static final String KEY_SUPPLEMENTARY_NO = "SUPPLEMENTARYNO";
  private static final String YES = "Y";
  private static final String PARAM_BOX = "box";
  private static final String PARAM_FIELD = "field";
  private static final String PARAM_LIMIT = "limit";
  private static final String PARAM_SECTION = "section";

  private Fiscal390IncidentRules() {
  }

  /** One IAE activity of the organization. */
  record Activity(String label, boolean hasCode) {
  }

  /** One active legal representative (first {@link #MAX_REPRESENTATIVES} by line). */
  record Representative(String nif, String name) {
  }

  /** An inactive tax of the 390 configuration with posted invoices in the exercise. */
  record InactiveTax(String name, long invoices) {
  }

  /**
   * Everything the rules read. Fields left {@code null} mean "unknown" and skip their rules (e.g.
   * the 303 preload could not be loaded): a rule never fires on missing data.
   */
  static final class Facts {
    String orgNif;
    boolean person;
    String firstName;
    String surname1;
    String surname2;
    String personRepNif;
    String personRepName;
    /** Legal entity: {@code AD_Org.socialName}, or its name when empty. */
    String taxpayerName;
    List<Activity> mainActivities = Collections.emptyList();
    List<Activity> otherActivities = Collections.emptyList();
    List<Representative> representatives = Collections.emptyList();
    /** The AEAT390 inputs the file is generated from (classic search keys, raw strings). */
    Map<String, String> inputs = Collections.emptyMap();
    /** {@code manualData.identification.sustitutiva}. */
    boolean substituteFlag;
    /** {@code manualData.identification.formerStatement}. */
    String formerStatement;
    /** {@code warnings} of the boxes payload ({@code code, parameter, rate}). */
    List<Map<String, String>> calculatorWarnings = Collections.emptyList();
    /** Set when the boxes could not be computed. */
    String computeError;
    /** [86] (or [94] in territory mode) of the boxes payload. */
    BigDecimal annualResult;
    /** {@code true} when {@link #annualResult} is [94]. */
    boolean territoryMode;
    /** {@code missingPeriods} of the 303 preload; {@code null} when not loaded. */
    List<String> missing303Periods;
    /** {@code expectedResult} of the 303 preload; {@code null} when not loaded / no 303. */
    BigDecimal expected303Result;
    /** Unposted invoices of the exercise; {@code null} when not counted. */
    Integer unpostedSales;
    Integer unpostedPurchase;
    List<String> unpostedDocuments = Collections.emptyList();
    List<InactiveTax> inactiveTaxes = Collections.emptyList();
  }

  /** One incident. {@code action} is {@code null} when there is nowhere to send the user. */
  static final class Incident {
    final String code;
    final String severity;
    final String text;
    final Map<String, Object> params = new LinkedHashMap<>();
    Map<String, Object> action;

    Incident(String code, String severity, String text) {
      this.code = code;
      this.severity = severity;
      this.text = text;
    }

    boolean isBlocking() {
      return SEVERITY_BLOCK.equals(severity);
    }

    Incident param(String key, Object value) {
      params.put(key, value);
      return this;
    }

    Incident action(String type, String key, Object value) {
      action = new LinkedHashMap<>();
      action.put("type", type);
      if (key != null) {
        action.put(key, value);
      }
      return this;
    }
  }

  /** Every incident of the facts: the blocking ones first, then the warnings. */
  static List<Incident> evaluate(Facts f) {
    List<Incident> out = new ArrayList<>(blocking(f));
    out.addAll(warnings(f));
    return out;
  }

  /** The blocking incidents only — what the {@code generate} / {@code submit} guards need. */
  static List<Incident> blocking(Facts f) {
    List<Incident> out = new ArrayList<>();
    organizationIdentity(f, out);
    activities(f, out);
    if (f.computeError != null) {
      out.add(block(COMPUTE_FAILED, "The boxes could not be computed: " + f.computeError)
          .param("message", f.computeError));
    }
    for (Map<String, String> w : f.calculatorWarnings) {
      if (UNSUPPORTED_RATE.equals(w.get("code"))) {
        out.add(block(UNSUPPORTED_RATE, "Tax rate " + w.get("rate") + " % of "
            + w.get("parameter") + " is not supported by the Modelo 390")
            .param("parameter", StringUtils.defaultString(w.get("parameter")))
            .param("rate", StringUtils.defaultString(w.get("rate"))));
      }
    }
    substitute(f, out);
    territory(f, out);
    liquidations(f, out);
    return out;
  }

  /** The warnings only. */
  static List<Incident> warnings(Facts f) {
    List<Incident> out = new ArrayList<>();
    unposted(f, out);
    if (f.missing303Periods != null && !f.missing303Periods.isEmpty()) {
      String periods = String.join(", ", f.missing303Periods);
      out.add(warn(MISSING_303, "Modelo 303 declarations of the exercise are missing: " + periods)
          .param("periods", periods).action(ACTION_BOX, PARAM_BOX, 95));
    }
    reconciliation(f, out);
    for (InactiveTax tax : f.inactiveTaxes) {
      out.add(warn(INACTIVE_TAX_WITH_OPERATIONS, "The inactive tax " + tax.name() + " has "
          + tax.invoices() + " posted invoices in the exercise; they are left out of the 390")
          .param("tax", tax.name()).param("invoices", tax.invoices()));
    }
    if (!f.person && f.representatives.isEmpty()) {
      out.add(warn(NO_LEGAL_REPRESENTATIVE, "The organization has no legal representative")
          .action(ACTION_ORGANIZATION, PARAM_SECTION, SECTION_390));
    }
    truncations(f, out);
    if (hasAmount(f, KEY_LAST_PERIOD_REFUND)
        && (hasAmount(f, KEY_LAST_PERIOD) || anyMonthlyRefund(f))) {
      out.add(warn(BOX98_WITH_96_97, "[98] must be empty when [96] or [97] has an amount")
          .action(ACTION_BOX, PARAM_BOX, 98));
    }
    for (Map<String, String> w : f.calculatorWarnings) {
      if (NO_BOX.equals(w.get("code"))) {
        out.add(warn(NO_BOX, "A figure of " + w.get("parameter") + " has no box in the form")
            .param("parameter", StringUtils.defaultString(w.get("parameter")))
            .param("rate", StringUtils.defaultString(w.get("rate"))));
      }
    }
    return out;
  }

  // ── Blocking rules ───────────────────────────────────────────────────────

  private static void organizationIdentity(Facts f, List<Incident> out) {
    if (StringUtils.isBlank(f.orgNif)) {
      out.add(block(NO_ORG_NIF, "The organization has no NIF")
          .action(ACTION_ORGANIZATION, PARAM_SECTION, SECTION_IDENTITY));
    }
    if (!f.person) {
      return;
    }
    if (StringUtils.isBlank(f.firstName) || StringUtils.isBlank(f.surname1)) {
      out.add(block(PERSON_NAME_MISSING,
          "A natural person needs the first name and the first surname")
          .action(ACTION_ORGANIZATION, PARAM_SECTION, SECTION_390));
    }
    if (StringUtils.isBlank(f.personRepNif) != StringUtils.isBlank(f.personRepName)) {
      out.add(block(PERSON_REPRESENTATIVE_INCOMPLETE,
          "The representative needs both the NIF and the name")
          .action(ACTION_ORGANIZATION, PARAM_SECTION, SECTION_390));
    }
  }

  private static void activities(Facts f, List<Incident> out) {
    if (f.mainActivities.isEmpty()) {
      out.add(block(NO_MAIN_IAE, "The organization has no main IAE activity")
          .action(ACTION_ORGANIZATION, PARAM_SECTION, SECTION_IAE));
    }
    List<Activity> all = new ArrayList<>(f.mainActivities.isEmpty()
        ? Collections.emptyList() : f.mainActivities.subList(0, 1));
    all.addAll(f.otherActivities);
    for (Activity a : all) {
      if (!a.hasCode()) {
        out.add(block(IAE_WITHOUT_CODE, "The IAE activity " + a.label() + " has no activity code")
            .param("activity", StringUtils.defaultString(a.label()))
            .action(ACTION_ORGANIZATION, PARAM_SECTION, SECTION_IAE));
      }
    }
    if (f.otherActivities.size() > MAX_OTHER_ACTIVITIES) {
      out.add(block(TOO_MANY_IAE, "The Modelo 390 admits at most " + MAX_OTHER_ACTIVITIES
          + " IAE activities besides the main one; the organization has "
          + f.otherActivities.size())
          .param("count", f.otherActivities.size()).param("max", MAX_OTHER_ACTIVITIES)
          .action(ACTION_ORGANIZATION, PARAM_SECTION, SECTION_IAE));
    }
  }

  /**
   * A substitute declaration — {@code ISSUPPLEMENTARY}/{@code ISRECTIFICATION} = Y in the inputs
   * the file is built from, or the page's {@code identification.sustitutiva} — needs the former
   * declaration's 13-digit identifier.
   */
  private static void substitute(Facts f, List<Incident> out) {
    boolean substitute = f.substituteFlag || YES.equals(f.inputs.get(KEY_SUPPLEMENTARY))
        || YES.equals(f.inputs.get(KEY_RECTIFICATION));
    if (!substitute) {
      return;
    }
    String id = StringUtils.defaultIfBlank(f.inputs.get(KEY_SUPPLEMENTARY_NO), f.formerStatement);
    String trimmed = StringUtils.trimToEmpty(id);
    if (trimmed.length() != FORMER_STATEMENT_LENGTH || !StringUtils.isNumeric(trimmed)) {
      out.add(block(SUBSTITUTE_ID_INVALID,
          "A substitute declaration needs the 13-digit identifier of the former one")
          .param("length", FORMER_STATEMENT_LENGTH));
    }
  }

  private static void territory(Facts f, List<Incident> out) {
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < TERRITORY_KEYS.length; i++) {
      BigDecimal pct = amount(f, TERRITORY_KEYS[i]);
      sum = sum.add(pct);
      if (pct.compareTo(TERRITORY_MAX) > 0) {
        out.add(block(TERRITORY_PERCENT_MAX, "Box [" + TERRITORY_BOXES[i]
            + "] cannot exceed 99 %")
            .param(PARAM_BOX, TERRITORY_BOXES[i]).param("max", 99)
            .action(ACTION_BOX, PARAM_BOX, TERRITORY_BOXES[i]));
      }
    }
    if (sum.signum() > 0 && sum.compareTo(HUNDRED) != 0) {
      out.add(block(TERRITORY_PERCENT_SUM, "The territory percentages add up to "
          + sum.stripTrailingZeros().toPlainString() + " %, not 100 %")
          .param("sum", sum.stripTrailingZeros().toPlainString())
          .action(ACTION_BOX, PARAM_BOX, 87));
    }
  }

  private static void liquidations(Facts f, List<Incident> out) {
    BigDecimal compensation = amount(f, KEY_COMPENSATION);
    BigDecimal territoryCompensation = amount(f, KEY_TERRITORY_COMPENSATION);
    if (compensation.signum() < 0) {
      out.add(block(COMPENSATION_NEGATIVE, "Box [85] cannot be negative")
          .param(PARAM_BOX, 85).action(ACTION_BOX, PARAM_BOX, 85));
    }
    if (territoryCompensation.signum() < 0) {
      out.add(block(COMPENSATION_NEGATIVE, "Box [93] cannot be negative")
          .param(PARAM_BOX, 93).action(ACTION_BOX, PARAM_BOX, 93));
    }
    if (compensation.signum() > 0 && territoryMode(f)) {
      out.add(block(COMPENSATION_WITH_TERRITORY,
          "Box [85] cannot be used when filing to several administrations: use [93]")
          .action(ACTION_BOX, PARAM_BOX, 85));
    } else if (compensation.signum() > 0 && territoryCompensation.signum() > 0) {
      out.add(block(COMPENSATION_BOTH, "Boxes [85] and [93] cannot both have an amount")
          .action(ACTION_BOX, PARAM_BOX, 93));
    }
    if (!YES.equals(f.inputs.get(KEY_MONTHLY_RETURN)) && anyMonthlyRefund(f)) {
      out.add(block(REFUND_WITHOUT_MONTHLY_REGISTER,
          "Monthly refunds need the monthly refund register mark")
          .action(ACTION_BOX, PARAM_BOX, 96));
    }
    if (hasAmount(f, Fiscal390DerivedBoxes.DEVOLVER_PREFIX + "12")
        && hasAmount(f, KEY_LAST_PERIOD)) {
      out.add(block(LAST_PERIOD_REFUND_CONFLICT,
          "The December refund and box [97] cannot both have an amount")
          .action(ACTION_BOX, PARAM_BOX, 97));
    }
  }

  // ── Warning rules ────────────────────────────────────────────────────────

  private static void unposted(Facts f, List<Incident> out) {
    int sales = f.unpostedSales != null ? f.unpostedSales : 0;
    int purchase = f.unpostedPurchase != null ? f.unpostedPurchase : 0;
    if (sales + purchase == 0) {
      return;
    }
    Incident inc = warn(UNPOSTED_INVOICES, (sales + purchase)
        + " invoices of the exercise are not posted and are left out of the 390")
        .param("count", sales + purchase).param("sales", sales).param("purchase", purchase)
        .param("documents", String.join(", ", f.unpostedDocuments));
    inc.action = new LinkedHashMap<>();
    inc.action.put("type", ACTION_INVOICES);
    inc.action.put("sales", sales);
    inc.action.put("purchase", purchase);
    out.add(inc);
  }

  private static void reconciliation(Facts f, List<Incident> out) {
    if (f.annualResult == null || f.expected303Result == null) {
      return;
    }
    Map<String, Object> cuadre =
        Fiscal390PreloadAggregator.reconcile(f.annualResult, f.expected303Result);
    if (!"difference".equals(cuadre.get("status"))) {
      return;
    }
    BigDecimal diff = (BigDecimal) cuadre.get("difference");
    int box = f.territoryMode ? 94 : 86;
    out.add(warn(DIFF_303, "The result differs from the Modelo 303 declarations by "
        + diff.toPlainString())
        .param("difference", diff.toPlainString())
        .param("expected", Fiscal390PreloadAggregator.r2(f.expected303Result).toPlainString())
        .param("result", Fiscal390PreloadAggregator.r2(f.annualResult).toPlainString())
        .param("tolerance", Fiscal390PreloadAggregator.RECONCILIATION_TOLERANCE.toPlainString())
        .param(PARAM_BOX, box)
        .action(ACTION_BOX, PARAM_BOX, box));
  }

  private static void truncations(Facts f, List<Incident> out) {
    if (f.person) {
      truncation(out, "surname1", f.surname1, LIMIT_PERSON_NAME, null);
      truncation(out, "surname2", f.surname2, LIMIT_PERSON_NAME, null);
      truncation(out, "firstName", f.firstName, LIMIT_PERSON_NAME, null);
      // classic writes the representative only when it has a NIF
      if (StringUtils.isNotBlank(f.personRepNif)) {
        truncation(out, "personRepresentative", f.personRepName, LIMIT_PERSON_REPRESENTATIVE,
            null);
      }
    } else {
      truncation(out, "socialName", f.taxpayerName, LIMIT_SOCIAL_NAME, null);
      for (Representative r : f.representatives) {
        truncation(out, "legalRepresentative", r.name(), LIMIT_LEGAL_REPRESENTATIVE,
            StringUtils.defaultIfBlank(r.nif(), r.name()));
      }
    }
  }

  private static void truncation(List<Incident> out, String field, String text, int limit,
      String label) {
    if (fileText(text).length() <= limit) {
      return;
    }
    Incident inc = warn(NAME_TRUNCATED, "The " + field + " will be cut to " + limit
        + " characters in the file")
        .param(PARAM_FIELD, field).param(PARAM_LIMIT, limit)
        .action(ACTION_ORGANIZATION, PARAM_SECTION,
            "socialName".equals(field) ? SECTION_IDENTITY : SECTION_390);
    if (label != null) {
      inc.param("label", label);
    }
    out.add(inc);
  }

  /**
   * The text as classic writes it ({@code AEAT390String}: upper case, accented vowels folded,
   * every character outside {@code [A-Z0-9. ]} — Ñ included — dropped, then every run of blanks
   * collapsed to one space and both ends trimmed). Only its length matters, so it must match what
   * the file writes: a double space in the master data, or one left behind by a dropped character
   * ("A - B"), does not count.
   */
  static String fileText(String text) {
    if (text == null) {
      return "";
    }
    return text.toUpperCase(Locale.ROOT)
        .replaceAll("[\u00C1\u00C0\u00C4\u00C2]", "A")
        .replaceAll("[\u00C9\u00C8\u00CB\u00CA]", "E")
        .replaceAll("[\u00CD\u00CC\u00CF\u00CE]", "I")
        .replaceAll("[\u00D3\u00D2\u00D6\u00D4]", "O")
        .replaceAll("[\u00DA\u00D9\u00DC\u00DB]", "U")
        .replaceAll("[^a-zA-Z0-9. ]", "")
        .replaceAll("\\s+", " ")
        .trim();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static boolean territoryMode(Facts f) {
    for (String key : TERRITORY_KEYS) {
      if (amount(f, key).signum() > 0) {
        return true;
      }
    }
    return false;
  }

  private static boolean anyMonthlyRefund(Facts f) {
    for (int month = 1; month <= 12; month++) {
      if (hasAmount(f, Fiscal390DerivedBoxes.DEVOLVER_PREFIX + String.format("%02d", month))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasAmount(Facts f, String key) {
    return amount(f, key).signum() != 0;
  }

  /** The input as a number; blank or not a number reads as zero (COMPUTE_FAILED reports it). */
  static BigDecimal amount(Facts f, String key) {
    String raw = f.inputs.get(key);
    if (StringUtils.isBlank(raw)) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(raw.trim().replace(",", "."));
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static Incident block(String code, String text) {
    return new Incident(code, SEVERITY_BLOCK, text);
  }

  private static Incident warn(String code, String text) {
    return new Incident(code, SEVERITY_WARN, text);
  }
}
