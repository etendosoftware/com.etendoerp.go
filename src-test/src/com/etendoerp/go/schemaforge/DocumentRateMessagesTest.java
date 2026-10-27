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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Language;

/**
 * Unit tests for {@link DocumentRateMessages}: where an exchange-rate text comes from — a translated
 * {@code AD_MESSAGE_TRL} row, then the versioned {@code neo_hints_<lang>} catalog, then the
 * {@code AD_MESSAGE} base text, then the key — and that the refusal text writes the date
 * {@code dd/MM/yyyy} in every language while its params keep it ISO.
 *
 * <p>The two message queries are answered by mocked JDBC statements; the catalog is the real one.
 *
 * <p>The resolution itself lives in {@link NeoMessageText} (shared with the ETP-5473
 * financial-account refusals); this class exercises it through both entry points.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentRateMessages
 * @covers com.etendoerp.go.schemaforge.NeoMessageText
 */
public class DocumentRateMessagesTest {

  private static final Object[] ARGS = { "EUR", "USD", "31/03/2026", "Fecha contable" };

  /** A statement whose single query answers {@code text}, or no row when {@code null}. */
  private static PreparedStatement answering(String text) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(text != null);
    when(rs.getString(1)).thenReturn(text);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);
    return ps;
  }

  /** Runs {@code body} with the TRL and base queries answering {@code trlText} / {@code baseText}. */
  private static <T> T withMessageRows(String trlText, String baseText, String language,
      java.util.function.Function<PreparedStatement, T> body) throws SQLException {
    PreparedStatement trl = answering(trlText);
    PreparedStatement base = answering(baseText);
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(contains("ad_message_trl"))).thenReturn(trl);
    when(conn.prepareStatement(startsWith("SELECT msgtext FROM ad_message "))).thenReturn(base);
    OBDal dal = mock(OBDal.class);
    when(dal.getConnection()).thenReturn(conn);
    OBContext context = mock(OBContext.class);
    Language lang = null;
    if (language != null) {
      lang = mock(Language.class);
      when(lang.getLanguage()).thenReturn(language);
    }
    when(context.getLanguage()).thenReturn(lang);
    try (MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(context);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      return body.apply(trl);
    }
  }

  /**
   * Table-driven resolution order: a translated TRL row wins (its {@code %n} placeholders
   * substituted); without one, the language's catalog; a language the catalog lacks falls to the
   * {@code AD_MESSAGE} base text before the English catalog; with nothing at all, the key.
   */
  @Test
  public void textResolvesTrlThenCatalogThenBaseThenKey() throws Exception {
    String key = DocumentRateMessages.MSG_MISSING_ON_SAVE;
    Object[][] cases = {
        // name, key, language, TRL text, base text, expected prefix
        { "translated TRL row wins", key, "es_ES", "Sin tasa de %0 a %1 el %2", "base %0",
            "Sin tasa de EUR a USD el 31/03/2026" },
        { "catalog of the language", key, "es_ES", null, "base %0",
            "No hay tipo de cambio de EUR a USD para el 31/03/2026 (Fecha contable)" },
        { "language without catalog reads the AD base", key, "fr_FR", null,
            "No rate from %0 to %1", "No rate from EUR to USD" },
        { "no catalog, no AD row: the English catalog", key, "fr_FR", null, null,
            "There is no exchange rate from EUR to USD for 31/03/2026" },
        { "nothing anywhere gives the key", "ETGO_NoSuchMessage", "fr_FR", null, null,
            "ETGO_NoSuchMessage" },
    };
    for (Object[] c : cases) {
      String name = (String) c[0];
      String text = withMessageRows((String) c[3], (String) c[4], null,
          trl -> DocumentRateMessages.text((String) c[1], (String) c[2], ARGS));
      assertTrue(name + ": " + text, text.startsWith((String) c[5]));
    }
  }

  /** The TRL lookup is bound to the requested key and language. */
  @Test
  public void translatedRowIsLookedUpForTheKeyAndLanguage() throws Exception {
    withMessageRows("x", null, null, trl -> {
      DocumentRateMessages.text(DocumentRateMessages.MSG_MISSING_ON_COMPLETE, "es_ES", ARGS);
      try {
        verify(trl).setString(1, DocumentRateMessages.MSG_MISSING_ON_COMPLETE);
        verify(trl).setString(2, "es_ES");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
      return null;
    });
  }

  /** {@code dd/MM/yyyy} (the format takes no language); an absent date is empty. */
  @Test
  public void formatDateIsDayMonthYearInEveryLanguage() {
    LocalDate date = LocalDate.of(2026, 3, 31);
    assertEquals("31/03/2026", DocumentRateMessages.formatDate(date));
    assertEquals("", DocumentRateMessages.formatDate(null));
  }

  /**
   * The save refusal is written in the request language — reference-date name included — with the
   * date {@code dd/MM/yyyy}, while {@code messageParams.date} stays ISO for a client that renders its
   * own text.
   */
  @Test
  public void missingOnSaveTextFollowsTheRequestLanguageAndParamsStayIso() throws Exception {
    JSONObject error = withMessageRows(null, null, "es_ES", trl -> {
      try {
        return DocumentRateMessages.missingOnSave("EUR", "USD", LocalDate.of(2026, 3, 31),
            DocumentRateMessages.LABEL_ACCOUNTING_DATE, "accountingDate")
            .getBody().getJSONObject("error");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });

    assertTrue(error.getString("message"), error.getString("message")
        .startsWith("No hay tipo de cambio de EUR a USD para el 31/03/2026 (Fecha contable)"));
    assertEquals("2026-03-31",
        error.getJSONObject(NeoProcessService.MESSAGE_PARAMS).getString("date"));
    assertEquals(422, error.getInt("status"));
  }

  /**
   * {@link NeoMessageText#error} keeps the caller's status and the usual {@code error} envelope,
   * writes the text in the request language and names the key in {@code messageKeys}.
   */
  @Test
  public void keyedErrorCarriesTheTranslatedTextAndTheKey() throws Exception {
    JSONObject error = withMessageRows(null, null, "es_ES", trl -> {
      try {
        return NeoMessageText.error(400, "ETGO_FinAccCountryRequired")
            .getBody().getJSONObject("error");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });

    assertEquals(400, error.getInt("status"));
    assertEquals("El pa\u00eds es obligatorio", error.getString("message"));
    assertEquals("ETGO_FinAccCountryRequired",
        error.getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
  }
}
