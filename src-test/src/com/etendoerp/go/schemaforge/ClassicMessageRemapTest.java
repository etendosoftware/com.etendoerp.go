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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The Classic message remap (ETP-6158): a catalogued Classic fiscal message that reaches NEO
 * already resolved, in any language and in any shape Classic produces it, leaves NEO as the GO
 * text in the request language. The AD_MESSAGE texts are injected (the real ones are read from the
 * database), copied from the rows the Classic modules ship.
 *
 * @covers com.etendoerp.go.schemaforge.ClassicMessageRemap
 */
public class ClassicMessageRemapTest {

  private static final String REVERSED_ES = "Si el tipo de factura es Rectificativa o Factura "
      + "emitida en sustitución de facturas simplificadas, debe añadir las facturas relacionadas "
      + "en la solapa Facturas Rectificativas.";
  private static final String REVERSED_GO_ES = "El Tipo de Factura de VERI*FACTU (solapa SIF) es "
      + "rectificativo o F3 (emitida en sustitución de simplificadas): añade las facturas que "
      + "rectifica en la pestaña Rectificaciones.";
  private static final String CERT_EN = "No valid certificate was found. Add the certificate to "
      + "the configured legal organization using the “Añadir Certificado Digital” process.";
  private static final String CERT_ES = "No se ha encontrado ningún certificado válido. Añada el "
      + "certificado a la organización legal configurada mediante el proceso “Añadir Certificado "
      + "Digital”.";
  /** The es_ES translation exactly as stored: indented continuation lines and HTML breaks. */
  private static final String MANUAL_SENDING_ES = "%0 factura(s) enviadas con éxito<br />\n"
      + "      %1 factura(s) no enviadas<br />\n"
      + "      %2 factura(s) enviadas previamente<br />\n"
      + "      %3\n"
      + "      Por favor, refresque la pantalla.";
  /** The base text as stored: HTML breaks, quotes and a {@code %s} that String.format fills. */
  private static final String MARSHALL = "Error interno al generar el fichero XML:<br />%s.<br />"
      + "<br />Este error se debe habitualmente a que la estructura o alguno de los datos del "
      + "fichero XML no son válidos. Puede establecer a \"N\" el parámetro \"Validación estricta "
      + "contra el esquema XSD\" dentro del grupo \"Validación\" en la configuración de este "
      + "Modelo para evitar esta validación y forzar la generación del fichero.";
  private static final String NIF_GO_ES = "El contacto no está marcado como Factura Simplificada, así que su CIF/NIF es obligatorio. Complétalo en Contactos.";

  @Before
  public void setUp() {
    ClassicMessageRemap.setTextsForTest(Map.of(
        "ETVFAC_reversed_invoice", List.of(REVERSED_ES),
        "AEATSII_EmptyCertificatePath", List.of(CERT_EN, CERT_ES),
        "AEATSII_InvoiceManualSending", List.of(MANUAL_SENDING_ES),
        "AEAT390_MARSHALLERROR", List.of(MARSHALL),
        "TBAI_ConfigNotFound", List.of("No existe configuración de TBAI alcanzable. Revise la "
            + "ventana de configuración.")));
  }

  @After
  public void tearDown() {
    ClassicMessageRemap.setTextsForTest(null);
  }

  /** The REST error body: top-level message and error.message, the key published in error. */
  @Test
  public void errorBodyIsRemappedAndKeepsTheMessageIdentity() throws Exception {
    NeoResponse response = NeoResponse.ensureTopLevelMessage(NeoResponse.error(422, REVERSED_ES));

    ClassicMessageRemap.apply(response, "es_ES");

    JSONObject body = response.getBody();
    assertEquals(REVERSED_GO_ES, body.getString("message"));
    assertEquals(REVERSED_GO_ES, body.getJSONObject("error").getString("message"));
    JSONArray keys = body.getJSONObject("error").getJSONArray("messageKeys");
    assertEquals("ETVFAC_reversed_invoice", keys.getString(0));
  }

  /** Classic resolved the message in Spanish; the request is English: the English GO text wins. */
  @Test
  public void aTranslationInAnotherLanguageIsRecognisedAndAnsweredInTheRequestLanguage()
      throws Exception {
    NeoResponse response = NeoResponse.error(500, "Process execution failed: " + CERT_ES);

    ClassicMessageRemap.apply(response, "en_US");

    assertEquals("Process execution failed: No valid certificate was found. Upload the "
        + "organization certificate in the Digital certificate section of Fiscal configuration.",
        response.getBody().getJSONObject("error").getString("message"));
  }

  /** Placeholders become arguments; HTML breaks and indentation do not prevent the match. */
  @Test
  public void placeholdersAreCapturedFromAnIndentedHtmlTranslation() throws Exception {
    String classic = "2 factura(s) enviadas con éxito<br />\n      1 factura(s) no enviadas<br />\n"
        + "      0 factura(s) enviadas previamente<br />\n      FV-7: NIF no censado\n"
        + "      Por favor, refresque la pantalla.";
    JSONObject body = new JSONObject().put("message",
        new JSONObject().put("severity", "success").put("text", classic));

    ClassicMessageRemap.applyToBody(body, "es_ES");

    assertEquals("Facturas enviadas con éxito: 2. No enviadas: 1. Enviadas previamente: 0. "
        + "FV-7: NIF no censado", body.getJSONObject("message").getString("text"));
  }

  /** An empty placeholder still matches. */
  @Test
  public void anEmptyPlaceholderStillMatches() {
    String classic = "3 factura(s) enviadas con éxito<br />\n      0 factura(s) no enviadas<br />\n"
        + "      0 factura(s) enviadas previamente<br />\n      \n"
        + "      Por favor, refresque la pantalla.";

    assertEquals("Facturas enviadas con éxito: 3. No enviadas: 0. Enviadas previamente: 0.",
        ClassicMessageRemap.remapText(classic, "es_ES"));
  }

  /** {@code messageBD} escapes quotes as {@code &quot;}; {@code %s} is captured. */
  @Test
  public void theMessageBdEscapedFormMatches() {
    String escaped = String.format(MARSHALL.replace("\"", "&quot;"),
        "cvc-pattern-valid: X no es válido");

    assertEquals("Error interno al generar el fichero XML: cvc-pattern-valid: X no es válido. "
        + "Suele deberse a que la estructura o algún dato de la declaración no es válido. Revisa "
        + "los datos indicados en el error y vuelve a generar el fichero.",
        ClassicMessageRemap.remapText(escaped, "es_ES"));
  }

  /**
   * A text already reworded for GO before it reaches the exit (a batch document's result, a
   * handler that remaps itself) still publishes its key, and is left as is.
   */
  @Test
  public void anAlreadyRewordedTextStillPublishesItsKey() throws Exception {
    JSONObject body = new JSONObject().put("results", new JSONArray().put(new JSONObject()
        .put("ok", false).put("error", new JSONObject().put("message", NIF_GO_ES))));

    ClassicMessageRemap.applyToBody(body, "en_US");

    JSONObject error = body.getJSONArray("results").getJSONObject(0).getJSONObject("error");
    assertEquals(NIF_GO_ES, error.getString("message"));
    assertEquals("TBAI_EmptyNifCode", error.getJSONArray("messageKeys").getString(0));
  }

  /** A remapped text is trimmed; padding around an untouched text is kept. */
  @Test
  public void aRemappedTextIsTrimmed() {
    assertEquals("No hay ninguna configuración de TicketBAI activa para esta organización. "
        + "Revísala en Configuración fiscal (pestaña TicketBAI).",
        ClassicMessageRemap.remapText(" @TBAI_ConfigNotFound@ ", "es_ES"));
    assertEquals("  Una nota cualquiera de la factura  ",
        ClassicMessageRemap.remapText("  Una nota cualquiera de la factura  ", "es_ES"));
  }

  /** The raw, unparsed token is replaced too. */
  @Test
  public void theRawTokenIsReplaced() {
    assertEquals("Error: No hay ninguna configuración de TicketBAI activa para esta organización. "
        + "Revísala en Configuración fiscal (pestaña TicketBAI).",
        ClassicMessageRemap.remapText("Error: @TBAI_ConfigNotFound@", "es_ES"));
  }

  /** Records and unrelated texts are left alone; a messageKeys array outside error is extended. */
  @Test
  public void onlyMessageKeysAreTouchedAndAnExistingKeyArrayIsExtended() throws Exception {
    JSONObject body = new JSONObject()
        .put("description", REVERSED_ES)
        .put("message", REVERSED_ES)
        .put("messageKeys", new JSONArray().put("Other"))
        .put("data", new JSONArray().put(new JSONObject().put("text", "Una nota cualquiera de "
            + "la factura")));

    ClassicMessageRemap.applyToBody(body, "es_ES");

    assertEquals(REVERSED_ES, body.getString("description"));
    assertEquals(REVERSED_GO_ES, body.getString("message"));
    assertEquals(2, body.getJSONArray("messageKeys").length());
    assertEquals("ETVFAC_reversed_invoice", body.getJSONArray("messageKeys").getString(1));
    assertEquals("Una nota cualquiera de la factura",
        body.getJSONArray("data").getJSONObject(0).getString("text"));
  }

  /** A GO text is not recognised again: applying twice changes nothing more. */
  @Test
  public void applyingTwiceIsIdempotent() {
    String once = ClassicMessageRemap.remapText(REVERSED_ES, "es_ES");

    assertEquals(once, ClassicMessageRemap.remapText(once, "es_ES"));
  }

  /** No database texts at all: AD_MESSAGE entries are skipped, nothing fails. */
  @Test
  public void withoutTheDatabaseTextsNothingFails() throws Exception {
    ClassicMessageRemap.setTextsForTest(null);
    NeoResponse response = NeoResponse.error(422, REVERSED_ES);

    ClassicMessageRemap.apply(response, "es_ES");

    assertEquals(REVERSED_ES, response.getBody().getJSONObject("error").getString("message"));
    assertNull(ClassicMessageRemap.apply(null, "es_ES"));
    assertNull(ClassicMessageRemap.remapText(null, "es_ES"));
  }

  /** TicketBAI address check on Confirmar: points to the GO address field, in English too. */
  @Test
  public void theTicketBaiAddressLineCheckPointsToContacts() {
    String classic = "La Línea 1ª de la dirección del tercero debe estar completa para continuar. "
        + "Por favor, verificar.";
    ClassicMessageRemap.setTextsForTest(Map.of("TBAI_EmptyDir", List.of(classic)));

    assertEquals("The contact is not marked as Simplified Invoice, so the invoice Partner Address "
        + "must have its First line (the street) filled in. Fill it in in Contacts, Location tab.",
        ClassicMessageRemap.remapText(classic, "en_US"));
  }

  /** Messages a GO user cannot trigger are not catalogued. */
  @Test
  public void messagesGoNeverShowsAreNotCatalogued() {
    for (ClassicMessageRemap.Entry entry : ClassicMessageRemap.ENTRIES) {
      assertFalse(entry.catalogKey, List.of("AEATSII_RECTIFIC_2017",
          "ETVFAC_complete_rectifying_type", "ETVFAC_null_description")
          .contains(entry.catalogKey));
    }
  }

  /** Every catalogued entry has a GO text in both catalogs, with no HTML. */
  @Test
  public void everyEntryHasAGoTextInBothLanguages() {
    for (ClassicMessageRemap.Entry entry : ClassicMessageRemap.ENTRIES) {
      for (String language : List.of("es_ES", "en_US")) {
        String text = NeoHints.catalogText(entry.catalogKey, language);
        assertTrue(entry.catalogKey + " " + language, text != null && !text.isBlank());
        assertFalse(entry.catalogKey + " " + language, text.contains("<"));
      }
    }
  }
}
