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

import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;

/**
 * ETP-6151. The import-settings rule of a PSD2-connected Financial Account: the first day whose
 * bank movements are imported and how the imported movements are grouped into statements. One
 * place for {@code link}, {@code createAndLink}, {@code import-settings} and the generic
 * {@code PUT}/{@code PATCH} of the account, so all four answer with the same messages.
 *
 * <p>Split out of {@link FinancialAccountBankConnectionSupport}, which sits on Sonar's per-class
 * member limit (java:S1448).
 */
final class BankImportSettingsRules {

  /** Body key of the first day whose bank movements are imported ({@code yyyy-MM-dd}). */
  static final String KEY_IMPORT_FROM_DATE = "importFromDate";
  /** Body key of the last day whose bank movements are imported ({@code yyyy-MM-dd}). */
  static final String KEY_IMPORT_TO_DATE = "importToDate";
  /** Body key of the statement grouping, one of {@link #STATEMENT_GROUPINGS}. */
  static final String KEY_STATEMENT_GROUPING = "statementGrouping";

  /**
   * The statement groupings the PSD2 module understands ({@code EM_PSD2_STATEMENT_FREQUENCY}): one
   * statement per day, per week, per month, or one per synchronization. Same codes as
   * {@code BankIntegrationConstants.ONEBD/ONEBW/ONEBM/ONEBE}, read from there so the two cannot
   * drift.
   */
  static final List<String> STATEMENT_GROUPINGS = List.of(BankIntegrationConstants.ONEBD,
      BankIntegrationConstants.ONEBW, BankIntegrationConstants.ONEBM, BankIntegrationConstants.ONEBE);

  // ETP-6151. Fixed ENGLISH literals, like MSG_IMPORT_RANGE_INVALID in the handler: the SPA maps
  // them to localized labels by exact text (lib/backendErrors.js), so the wording is a wire contract.
  static final String MSG_IMPORT_FROM_DATE_REQUIRED = "The import from date is required";
  static final String MSG_IMPORT_FROM_DATE_INVALID =
      "The import from date is not a valid date (expected yyyy-MM-dd)";
  static final String MSG_IMPORT_TO_DATE_INVALID =
      "The import to date is not a valid date (expected yyyy-MM-dd)";
  static final String MSG_STATEMENT_GROUPING_REQUIRED = "The statement grouping is required";
  static final String MSG_STATEMENT_GROUPING_INVALID =
      "The statement grouping must be one of 1BD, 1BW, 1BM, 1BE";

  private BankImportSettingsRules() {
  }

  /** The import settings a {@code link} / {@code createAndLink} request must carry (ETP-6151). */
  static final class ImportSettings {
    private final Date importFrom;
    private final String statementGrouping;

    /**
     * @param importFrom        the validated import-from day, never {@code null}
     * @param statementGrouping the validated grouping code, one of
     *                          {@link BankImportSettingsRules#STATEMENT_GROUPINGS}
     */
    ImportSettings(Date importFrom, String statementGrouping) {
      this.importFrom = importFrom;
      this.statementGrouping = statementGrouping;
    }

    /**
     * @return the import-from day, stored as UTC midnight (see
     *     {@link FinancialAccountBankConnectionSupport#parseDate})
     */
    Date getImportFrom() {
      return importFrom;
    }

    /** @return the statement grouping code */
    String getStatementGrouping() {
      return statementGrouping;
    }

    /**
     * Writes both settings on the account, without saving or flushing: the caller does so in the
     * same transaction as the link, so no synchronization — not even the scheduled one — can run
     * on the newly linked account without its import-from date.
     *
     * @param finAcc the financial account being linked
     */
    void applyTo(FIN_FinancialAccount finAcc) {
      finAcc.setPSD2ImportFromDate(importFrom);
      finAcc.setPSD2StatementFrequency(statementGrouping);
    }
  }

  /**
   * ETP-6151. Reads and validates the two import settings a {@code link} or {@code createAndLink}
   * body must carry: {@code importFromDate} ({@code yyyy-MM-dd}) and {@code statementGrouping}
   * (one of {@link #STATEMENT_GROUPINGS}). Both are required.
   *
   * @param body the request body
   * @return the validated settings
   * @throws OBException with one of the {@code MSG_IMPORT_FROM_DATE_*} /
   *     {@code MSG_STATEMENT_GROUPING_*} texts when a value is missing or invalid (the handler maps
   *     it to a 400)
   */
  static ImportSettings requireImportSettings(JSONObject body) {
    Date importFrom = requireImportFromDate(parseImportDate(
        FinancialAccountBankConnectionSupport.bodyString(body, KEY_IMPORT_FROM_DATE),
        MSG_IMPORT_FROM_DATE_INVALID));
    String grouping = requireStatementGrouping(
        FinancialAccountBankConnectionSupport.bodyString(body, KEY_STATEMENT_GROUPING));
    return new ImportSettings(importFrom, grouping);
  }

  /**
   * ETP-6151. The generic save path's share of the import-settings rule: a {@code PUT}/{@code PATCH}
   * of the {@code financial-account} entity (REST single, batch and MCP alike, all through
   * {@code FinancialAccountHandler}) that touches the PSD2 import keys is held to the same rule
   * and the same messages as {@code link}, {@code createAndLink} and {@code import-settings}.
   *
   * <ul>
   *   <li>{@code pSD2StatementFrequency} with a value must be one of {@link #STATEMENT_GROUPINGS}
   *       ({@link #MSG_STATEMENT_GROUPING_INVALID} otherwise, on every account); a valid value is
   *       written back trimmed. Sending it {@code null} or blank is
   *       {@link #MSG_STATEMENT_GROUPING_REQUIRED} only on a bank-connected account that has a
   *       stored value (it would clear a real value).</li>
   *   <li>{@code pSD2ImportFromDate} sent {@code null} or blank is
   *       {@link #MSG_IMPORT_FROM_DATE_REQUIRED} only on a bank-connected account that has a stored
   *       date: a connected account never loses the day its movements are imported from.</li>
   * </ul>
   *
   * <p>Echoing a null/blank over an already null/blank stored value passes, and so does clearing
   * either on an account with no bank connection. The account is loaded at most once, and only
   * when one of the two is sent null or blank.
   *
   * <p>A body that touches neither key is never checked, so an existing connection whose stored
   * values are null keeps accepting every unrelated edit. The date's format is left to the generic
   * CRUD, which parses it.
   *
   * @param body          the update body, possibly {@code null}
   * @param storedAccount loads the account being updated; called at most once, and only when the
   *                      body sends the import-from date or the grouping null/blank
   * @throws OBException with one of the messages above (the caller maps it to a 400)
   * @throws JSONException never in practice (writing back a string value)
   */
  static void validateImportSettingsUpdate(JSONObject body,
      Supplier<FIN_FinancialAccount> storedAccount) throws JSONException {
    if (body == null) {
      return;
    }
    String groupingKey = FIN_FinancialAccount.PROPERTY_PSD2STATEMENTFREQUENCY;
    boolean clearsGrouping = FinancialAccountCountrySupport.isExplicitClear(body, groupingKey);
    boolean clearsImportFrom = FinancialAccountCountrySupport.isExplicitClear(body,
        FIN_FinancialAccount.PROPERTY_PSD2IMPORTFROMDATE);
    if (body.has(groupingKey) && !clearsGrouping) {
      // A value outside the codes is refused on every account; a valid one is written back trimmed.
      body.put(groupingKey, requireStatementGrouping(
          FinancialAccountBankConnectionSupport.bodyString(body, groupingKey)));
    }
    if (!clearsGrouping && !clearsImportFrom) {
      return;
    }
    // Loaded once, and only when something is sent null or blank.
    FIN_FinancialAccount stored = storedAccount.get();
    if (!BankStatementsSupport.isBankConnected(stored)) {
      return;
    }
    // Only a real stored value is protected: echoing a null over a null changes nothing.
    if (clearsGrouping && StringUtils.isNotBlank(stored.getPSD2StatementFrequency())) {
      throw new OBException(MSG_STATEMENT_GROUPING_REQUIRED);
    }
    if (clearsImportFrom && stored.getPSD2ImportFromDate() != null) {
      throw new OBException(MSG_IMPORT_FROM_DATE_REQUIRED);
    }
  }

  /**
   * Parses an import bound sent as {@code yyyy-MM-dd}, turning a malformed value into a business
   * error instead of the 500 an unchecked {@link DateTimeParseException} would produce.
   *
   * @param value          the raw value, possibly blank
   * @param invalidMessage the text of the 400 when the value is not a valid day
   * @return the day as UTC midnight, or {@code null} when the value is blank
   * @throws OBException with {@code invalidMessage} when the value is not a valid day
   */
  static Date parseImportDate(String value, String invalidMessage) {
    try {
      return FinancialAccountBankConnectionSupport.parseDate(value);
    } catch (DateTimeParseException e) {
      throw new OBException(invalidMessage);
    }
  }

  /**
   * Requires the import-from day: an account connected or configured from Etendo always knows from
   * which day its bank movements are imported (ETP-6151).
   *
   * @param importFrom the resolved import-from day, possibly {@code null}
   * @return the same day, never {@code null}
   * @throws OBException with {@link #MSG_IMPORT_FROM_DATE_REQUIRED} when it is {@code null}
   */
  static Date requireImportFromDate(Date importFrom) {
    if (importFrom == null) {
      throw new OBException(MSG_IMPORT_FROM_DATE_REQUIRED);
    }
    return importFrom;
  }

  /**
   * Requires a statement grouping the PSD2 module understands. Before ETP-6151 any string was
   * stored as is and only failed, silently, when the next synchronization grouped the movements.
   *
   * @param grouping the resolved grouping code, possibly blank
   * @return the same code, never blank
   * @throws OBException with {@link #MSG_STATEMENT_GROUPING_REQUIRED} when it is blank, or
   *     {@link #MSG_STATEMENT_GROUPING_INVALID} when it is not one of {@link #STATEMENT_GROUPINGS}
   */
  static String requireStatementGrouping(String grouping) {
    if (StringUtils.isBlank(grouping)) {
      throw new OBException(MSG_STATEMENT_GROUPING_REQUIRED);
    }
    if (!STATEMENT_GROUPINGS.contains(grouping)) {
      throw new OBException(MSG_STATEMENT_GROUPING_INVALID);
    }
    return grouping;
  }
}
