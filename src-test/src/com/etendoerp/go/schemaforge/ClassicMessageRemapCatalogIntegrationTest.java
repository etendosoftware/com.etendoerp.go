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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * The Classic message remap against the real {@code AD_MESSAGE} rows (ETP-6158): the catalog is
 * read through the DAL (the HQL of the loader runs for real), and every catalogued key's base text
 * and its {@code es_ES} / {@code en_US} translations, filled the way Classic fills them, compile
 * into patterns that match and leave as the GO text of the request language.
 *
 * <p>The mocked {@link ClassicMessageRemapTest} injects copies of these texts; this class is what
 * notices when a Classic module ships a reworded row that no longer matches. Read-only: nothing is
 * written, the session is rolled back.
 *
 * @covers com.etendoerp.go.schemaforge.ClassicMessageRemap
 */
public class ClassicMessageRemapCatalogIntegrationTest extends OBBaseTest {

  private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+|s)");
  private static final List<String> REQUEST_LANGUAGES = List.of("es_ES", "en_US");

  @Before
  public void forgetInjectedTexts() {
    setTestAdminContext();
    ClassicMessageRemap.setTextsForTest(null);
  }

  @After
  public void tearDown() {
    ClassicMessageRemap.setTextsForTest(null);
    OBDal.getInstance().rollbackAndClose();
  }

  /** A Classic text with its placeholders filled, and the arguments the GO text receives. */
  private static final class Filled {
    private final String text;
    private final Object[] args;

    private Filled(String text, Object[] args) {
      this.text = text;
      this.args = args;
    }
  }

  /** {@code %N} becomes {@code ARGN}, each {@code %s} the next argument, as Classic fills them. */
  private static Filled fill(String classic) {
    Matcher matcher = PLACEHOLDER.matcher(classic);
    StringBuilder text = new StringBuilder();
    List<Integer> indexes = new ArrayList<>();
    int positional = 0;
    int max = -1;
    while (matcher.find()) {
      int index = "s".equals(matcher.group(1)) ? positional++ : Integer.parseInt(matcher.group(1));
      indexes.add(index);
      max = Math.max(max, index);
      matcher.appendReplacement(text, "ARG" + index);
    }
    matcher.appendTail(text);
    Object[] args = new Object[max + 1];
    for (int i = 0; i <= max; i++) {
      args[i] = indexes.contains(i) ? "ARG" + i : "";
    }
    return new Filled(text.toString(), args);
  }

  /** The base text and the es_ES / en_US translations of {@code key}. */
  private static List<String> realTexts(String key) throws Exception {
    List<String> texts = new ArrayList<>();
    Connection connection = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT m.msgtext FROM ad_message m WHERE m.value = ?"
            + " UNION ALL SELECT t.msgtext FROM ad_message_trl t"
            + " JOIN ad_message m ON m.ad_message_id = t.ad_message_id"
            + " WHERE m.value = ? AND t.ad_language IN ('es_ES', 'en_US')")) {
      ps.setString(1, key);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          texts.add(rs.getString(1));
        }
      }
    }
    return texts;
  }

  @Test
  public void everyCataloguedMessageMatchesItsRealTextsInEveryLanguage() throws Exception {
    int checked = 0;
    for (ClassicMessageRemap.Entry entry : ClassicMessageRemap.ENTRIES) {
      if (!entry.adMessage) {
        continue;
      }
      List<String> texts = realTexts(entry.catalogKey);
      assertFalse(entry.catalogKey + " has no AD_MESSAGE row", texts.isEmpty());
      for (String classic : texts) {
        Filled filled = fill(classic);
        // as resolved by messageBD (quotes and line breaks escaped) and as raised verbatim
        List<String> shapes = List.of(filled.text,
            filled.text.replace("\"", "&quot;").replace("\n", "\\n"));
        for (String language : REQUEST_LANGUAGES) {
          String expected = ClassicMessageRemap.normalize(
              ClassicMessageRemap.goText(entry.catalogKey, language, filled.args));
          for (String shape : shapes) {
            String label = entry.catalogKey + " [" + language + "] <- " + shape;
            String remapped = ClassicMessageRemap.remapText(shape, language);
            assertNotNull(label, remapped);
            assertEquals(label, expected, remapped);
            checked++;
          }
        }
      }
    }
    assertFalse("nothing was checked", checked == 0);
  }
}
