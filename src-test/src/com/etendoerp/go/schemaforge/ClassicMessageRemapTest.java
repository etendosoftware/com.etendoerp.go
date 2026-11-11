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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.exception.GenericJDBCException;
import org.hibernate.query.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usage.IsolatedDalRead;

/**
 * The Classic message remap (ETP-6158): a catalogued Classic fiscal message that reaches NEO
 * already resolved, in any language and in any shape Classic produces it, leaves NEO as the GO
 * text in the request language. The AD_MESSAGE texts are injected (the real ones are read from the
 * database), copied from the rows the Classic modules ship; the database read itself is covered
 * with a mocked Hibernate session (one load, the 30 s backoff, an aborted transaction).
 *
 * @covers com.etendoerp.go.schemaforge.ClassicMessageRemap
 */
public class ClassicMessageRemapTest {

  private static final String REVERSED_ES = "Si el tipo de factura es Rectificativa o Factura "
      + "emitida en sustitución de facturas simplificadas, debe añadir las facturas relacionadas "
      + "en la solapa Facturas Rectificativas.";
  private static final String REVERSED_GO_ES = "El Tipo de Factura de VERI*FACTU (pestaña SIF) es "
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
  private static final String CERT_GO_ES = "No se ha encontrado ningún certificado válido. Sube "
      + "el certificado de la organización en la sección Certificado digital de Configuración "
      + "fiscal.";
  private static final String CONFIG_ES = "No existe configuración de TBAI alcanzable. Revise la "
      + "ventana de configuración.";
  private static final String CONFIG_GO_ES = "No hay ninguna configuración de TicketBAI activa "
      + "para esta organización. Revísala en Configuración fiscal (pestaña TicketBAI).";
  /** The SII literal sentence: needs no AD_MESSAGE row, so it works without the database. */
  private static final String SII_TYPE_CLASSIC = "La factura debe contener información en el campo "
      + "Clave tipo factura.";
  private static final String NIF_GO_ES = "El contacto no está marcado como Factura Simplificada, así que su NIF/CIF es obligatorio. Complétalo en Contactos.";

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

  /**
   * A tool result whose {@code text} is a JSON-serialized body (an MCP content item): the Classic
   * message inside it, with {@code messageBD}'s {@code &quot;} and the serialized {@code \n}, is
   * remapped, and the content stays valid JSON with its other fields intact.
   */
  @Test
  public void aMessageInsideAJsonSerializedContentStringIsRemappedAndTheJsonStaysValid()
      throws Exception {
    String classic = "El certificado no es válido. Asegúrese de cargarlo correctamente desde la "
        + "organización legal mediante el proceso \"Añadir Certificado Digital\".";
    ClassicMessageRemap.setTextsForTest(Map.of("TBAI_CertificateNotValid", List.of(classic)));
    String serialized = new JSONObject().put("status", "error").put("title", "Error: ")
        .put("message", classic.replace("\"", "&quot;") + "\nDetalle: el fichero .p12 caducó")
        .put("error", "validation_error").toString();
    JSONObject body = new JSONObject().put("isError", true).put("content", new JSONArray()
        .put(new JSONObject().put("type", "text").put("text", serialized)));

    ClassicMessageRemap.applyToBody(body, "es_ES");

    JSONObject content = new JSONObject(
        body.getJSONArray("content").getJSONObject(0).getString("text"));
    assertEquals("El certificado no es válido. Vuelve a subirlo en la sección Certificado digital "
        + "de Configuración fiscal. Detalle: el fichero .p12 caducó", content.getString("message"));
    assertEquals("error", content.getString("status"));
    assertEquals("Error: ", content.getString("title"));
    assertEquals("validation_error", content.getString("error"));
  }

  /** A replacement adds no separator next to punctuation or a quote. */
  @Test
  public void aReplacementAddsNoSpaceAroundIt() {
    assertEquals("Error (No hay ninguna configuración de TicketBAI activa para esta organización. "
        + "Revísala en Configuración fiscal (pestaña TicketBAI).)",
        ClassicMessageRemap.remapText("Error (No existe configuración de TBAI alcanzable. Revise "
            + "la ventana de configuración.)", "es_ES"));
  }

  /**
   * A {@code messageBD} literal backslash-n outside the matched span is left exactly as it was,
   * so a serialized body around it stays valid JSON for a strict parser.
   */
  @Test
  public void anEscapeOutsideTheMatchSurvivesAndTheJsonStaysStrictlyValid() throws Exception {
    String classic = "No existe configuración de TBAI alcanzable. Revise la ventana de "
        + "configuración.";
    String serialized = new JSONObject().put("message", classic + " Detalle: línea 1\\nlínea 2")
        .toString();
    JSONObject body = new JSONObject().put("content", new JSONArray()
        .put(new JSONObject().put("type", "text").put("text", serialized)));

    ClassicMessageRemap.applyToBody(body, "es_ES");

    String out = body.getJSONArray("content").getJSONObject(0).getString("text");
    com.fasterxml.jackson.databind.JsonNode strict =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
    assertEquals("No hay ninguna configuración de TicketBAI activa para esta organización. "
        + "Revísala en Configuración fiscal (pestaña TicketBAI). Detalle: línea 1\\nlínea 2",
        strict.get("message").asText());
    assertEquals("No hay ninguna configuración de TicketBAI activa para esta organización. "
        + "Revísala en Configuración fiscal (pestaña TicketBAI). Detalle: línea 1\\nlínea 2",
        ClassicMessageRemap.remapText(classic + "  Detalle: línea 1\\nlínea 2", "es_ES"));
  }

  /** A break as a JSON serializer writes it ({@code <br \/>}) is a break too. */
  @Test
  public void aSerializedBreakIsRecognised() {
    String serializedForm = String.format(MARSHALL, "X").replace("/", "\\/");

    assertTrue(ClassicMessageRemap.remapText(serializedForm, "es_ES")
        .startsWith("Error interno al generar el fichero XML: X. Suele deberse"));
  }

  /**
   * BUG-2: the MCP write-path envelope carries the Classic text under {@code detail}, padded: it
   * is remapped there, trimmed, and its key is published at the root of the error result.
   */
  @Test
  public void aClassicTextUnderDetailIsRemappedTrimmedAndKeyed() throws Exception {
    String classic = "El campo \"Fecha acogida SII\" en la ventana de configuración está vacío. "
        + "Debe rellenarse para guardar la cabecera de la factura.";
    ClassicMessageRemap.setTextsForTest(Map.of("AEATSII_SII_ACCRUAL_DATE_EMPTY", List.of(classic)));
    JSONObject envelope = new JSONObject().put("status", 422).put("error", "validation_error")
        .put("hint", "A business rule rejected the values sent.")
        .put("detail", " " + classic.replace("\"", "&quot;") + " ")
        .put("seeAlso", "etendo://docs");

    ClassicMessageRemap.applyToErrorBody(envelope);

    assertEquals(ClassicMessageRemap.goText("AEATSII_SII_ACCRUAL_DATE_EMPTY", null),
        envelope.getString("detail"));
    assertEquals("AEATSII_SII_ACCRUAL_DATE_EMPTY",
        envelope.getJSONArray("messageKeys").getString(0));
  }


  // ── cost and bounds (review B1, W2, W3, N5) ─────────────────────────────────────────────────

  private static final String MANUAL_ANCHORS = "1 factura(s) enviadas con éxito 2 factura(s) no "
      + "enviadas ";

  /**
   * A placeholder that starts the Classic text takes only the word before it: text in front of
   * the message stays where it was and does not leak into the argument.
   */
  @Test
  public void aLeadingPlaceholderTakesOnlyTheWordBeforeIt() {
    String classic = "Proceso terminado. 3 factura(s) enviadas con éxito<br />\n      0 factura(s) "
        + "no enviadas<br />\n      0 factura(s) enviadas previamente<br />\n      \n"
        + "      Por favor, refresque la pantalla.";

    assertEquals("Proceso terminado. Facturas enviadas con éxito: 3. No enviadas: 0. Enviadas "
        + "previamente: 0.", ClassicMessageRemap.remapText(classic, "es_ES"));
  }

  /** B1: a long text that matches nothing, and an adversarial repetition of anchors, stay cheap. */
  @Test
  public void longAndAdversarialTextsAreRemappedWithinBudget() {
    StringBuilder noise = new StringBuilder();
    while (noise.length() < 50_000) {
      noise.append("texto sin relación con ningún mensaje ");
    }
    StringBuilder adversarial = new StringBuilder();
    while (adversarial.length() < ClassicMessageRemap.MAX_TEXT_LENGTH - MANUAL_ANCHORS.length()) {
      adversarial.append(MANUAL_ANCHORS);
    }
    String big = noise.toString();
    String hostile = adversarial.toString();
    ClassicMessageRemap.remapText(hostile, "es_ES"); // warm-up

    long started = System.nanoTime();
    String bigOut = ClassicMessageRemap.remapText(big, "es_ES");
    String hostileOut = ClassicMessageRemap.remapText(hostile, "es_ES");
    long millis = (System.nanoTime() - started) / 1_000_000L;

    assertSame(big, bigOut);
    assertEquals(hostile, hostileOut);
    assertTrue("took " + millis + " ms", millis < 200);
  }

  /** B1: a text longer than the cap is not even tried, even when it carries a message. */
  @Test
  public void aTextLongerThanTheCapIsLeftUntouched() {
    StringBuilder padding = new StringBuilder();
    while (padding.length() <= ClassicMessageRemap.MAX_TEXT_LENGTH) {
      padding.append("relleno ");
    }
    String text = padding + CONFIG_ES;

    assertSame(text, ClassicMessageRemap.remapText(text, "es_ES"));
  }

  /** W2: an argument is taken from the original text: a Windows path keeps its backslashes. */
  @Test
  public void anArgumentKeepsItsOriginalCharacters() {
    String classic = String.format(MARSHALL, "C:\\new\\cert.p12 &quot;roto&quot;");

    assertEquals("Error interno al generar el fichero XML: C:\\new\\cert.p12 &quot;roto&quot;. "
        + "Suele deberse a que la estructura o algún dato de la declaración no es válido. Revisa "
        + "los datos indicados en el error y vuelve a generar el fichero.",
        ClassicMessageRemap.remapText(classic, "es_ES"));
  }

  /** W3: the same message twice in one text is replaced twice. */
  @Test
  public void theSameMessageTwiceIsReplacedTwice() {
    assertEquals(CONFIG_GO_ES + " / " + CONFIG_GO_ES,
        ClassicMessageRemap.remapText(CONFIG_ES + " / " + CONFIG_ES, "es_ES"));
  }

  /**
   * N5: only whitespace touching the match changes (one space between it and other text, none
   * against an edge of the string); padding elsewhere stays.
   */
  @Test
  public void onlyTheWhitespaceTouchingTheMatchChanges() {
    assertEquals("Error: " + CONFIG_GO_ES,
        ClassicMessageRemap.remapText("Error:\t" + CONFIG_ES + "   ", "es_ES"));
    assertEquals("  Nota: " + CONFIG_GO_ES + " fin  ",
        ClassicMessageRemap.remapText("  Nota:  " + CONFIG_ES + "\n fin  ", "es_ES"));
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
        + "must have its First line (the street) filled in. Complete it in Contacts, Location tab.",
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

  // ── the database read (mocked Hibernate session) ────────────────────────────────────────────

  /** The two HQL reads of {@code readTexts}: base texts and translations. */
  private static final class DalStub implements AutoCloseable {
    private final MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
    private final MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
    /** The isolated read runs inline here, so the DAL stubs of this thread answer it. */
    private final MockedStatic<IsolatedDalRead> isolated = mockStatic(IsolatedDalRead.class);
    private final Session session = mock(Session.class);
    @SuppressWarnings("unchecked")
    private final Query<Object[]> base = mock(Query.class);
    @SuppressWarnings("unchecked")
    private final Query<Object[]> translated = mock(Query.class);

    @SuppressWarnings({ "unchecked", "rawtypes" })
    DalStub() {
      isolated.when(() -> IsolatedDalRead.call(any()))
          .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(startsWith("select m.searchKey"))).thenReturn((Query) base);
      when(session.createQuery(startsWith("select t.message"))).thenReturn((Query) translated);
      when(base.setParameterList(eq("keys"), anyCollection())).thenReturn(base);
      when(translated.setParameterList(eq("keys"), anyCollection())).thenReturn(translated);
    }

    void rows(List<Object[]> baseRows, List<Object[]> translatedRows) {
      when(base.list()).thenReturn(baseRows);
      when(translated.list()).thenReturn(translatedRows);
    }

    @Override
    public void close() {
      isolated.close();
      obContext.close();
      obDal.close();
    }
  }

  private static List<Object[]> rowsOf(String key, String text) {
    List<Object[]> rows = new ArrayList<>();
    rows.add(new Object[] { key, text });
    return rows;
  }

  private static void setLastFailedLoad(long value) throws Exception {
    Field field = ClassicMessageRemap.class.getDeclaredField("lastFailedLoad");
    field.setAccessible(true);
    field.setLong(null, value);
  }

  /**
   * The AD_MESSAGE texts are read once for the JVM — the base and the translation query, in admin
   * mode that is always restored — and then serve every later response without a query.
   */
  @Test
  public void theAdMessageTextsAreReadOnceAndServeEveryLaterResponse() throws Exception {
    ClassicMessageRemap.setTextsForTest(null);
    try (DalStub dal = new DalStub()) {
      dal.rows(rowsOf("TBAI_ConfigNotFound", CONFIG_ES),
          rowsOf("AEATSII_EmptyCertificatePath", CERT_ES));

      for (int i = 0; i < 3; i++) {
        NeoResponse response = NeoResponse.error(422, i % 2 == 0 ? CONFIG_ES : CERT_ES);
        ClassicMessageRemap.apply(response, "es_ES");
        assertEquals(i % 2 == 0 ? CONFIG_GO_ES : CERT_GO_ES,
            response.getBody().getJSONObject("error").getString("message"));
      }

      verify(dal.session, times(2)).createQuery(anyString());
      verify(dal.base).setParameterList(eq("keys"), anyCollection());
      dal.obContext.verify(() -> OBContext.setAdminMode(true), times(1));
      dal.obContext.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /**
   * A failed read is not retried on every response: within the 30 s backoff nothing queries the
   * database again (and the AD_MESSAGE entries are skipped, the literal ones still work); once
   * the backoff has passed, the next response reads again.
   */
  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void aFailedReadIsRetriedOnlyAfterTheBackoff() throws Exception {
    ClassicMessageRemap.setTextsForTest(null);
    try (DalStub dal = new DalStub()) {
      when(dal.session.createQuery(startsWith("select m.searchKey")))
          .thenThrow(new IllegalStateException("No session"))
          .thenReturn((Query) dal.base);
      dal.rows(rowsOf("TBAI_ConfigNotFound", CONFIG_ES), Collections.emptyList());

      for (int i = 0; i < 3; i++) {
        assertEquals(CONFIG_ES, ClassicMessageRemap.remapText(CONFIG_ES, "es_ES"));
      }
      assertEquals("La factura debe contener información en el campo Tipo factura.",
          ClassicMessageRemap.remapText(SII_TYPE_CLASSIC, "es_ES"));
      verify(dal.session, times(1)).createQuery(startsWith("select m.searchKey"));

      setLastFailedLoad(System.currentTimeMillis() - 31_000L);

      assertEquals(CONFIG_GO_ES, ClassicMessageRemap.remapText(CONFIG_ES, "es_ES"));
      verify(dal.session, times(2)).createQuery(startsWith("select m.searchKey"));
    }
  }

  /**
   * An aborted transaction (the request already failed in the database) makes the read fail: the
   * response is still written, unchanged, and the admin mode entered for the read is restored.
   */
  @Test
  public void anAbortedTransactionDoesNotBreakTheResponse() throws Exception {
    ClassicMessageRemap.setTextsForTest(null);
    try (DalStub dal = new DalStub()) {
      when(dal.base.list()).thenThrow(new GenericJDBCException("could not extract ResultSet",
          new SQLException("ERROR: current transaction is aborted, commands ignored until end "
              + "of transaction block", "25P02")));
      NeoResponse response = NeoResponse.ensureTopLevelMessage(NeoResponse.error(422, REVERSED_ES));

      assertSame(response, ClassicMessageRemap.apply(response, "es_ES"));

      assertEquals(422, response.getHttpStatus());
      assertEquals(REVERSED_ES, response.getBody().getString("message"));
      assertEquals(REVERSED_ES, response.getBody().getJSONObject("error").getString("message"));
      assertFalse(response.getBody().getJSONObject("error").has("messageKeys"));
      verify(dal.translated, never()).list();
      dal.obContext.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ── shapes and edge cases ───────────────────────────────────────────────────────────────────

  /**
   * {@code AEAT390_MARSHALLERROR}: the Classic text is filled with {@code String.format} and the
   * validator's exception spans several lines. The whole exception text is the argument, taken
   * from the original text as it is (its line breaks kept).
   */
  @Test
  public void aMultiLineExceptionFillsTheFormatPlaceholder() {
    String exception = "cvc-pattern-valid: Value 'B1' is not facet-valid with respect to "
        + "pattern.\ncvc-type.3.1.3: The value 'B1' of element 'NIF' is not valid";
    String classic = String.format(MARSHALL.replace("\"", "&quot;"), exception);

    assertEquals("Error interno al generar el fichero XML: cvc-pattern-valid: Value 'B1' is not "
        + "facet-valid with respect to pattern.\ncvc-type.3.1.3: The value 'B1' of element 'NIF' "
        + "is not valid. Suele deberse a que la estructura o algún dato de la declaración no es "
        + "válido. Revisa los datos indicados en el error y vuelve a generar el fichero.",
        ClassicMessageRemap.remapText(classic, "es_ES"));
    assertEquals("Internal error while generating the XML file: cvc-pattern-valid: Value 'B1' "
        + "is not facet-valid with respect to pattern.\ncvc-type.3.1.3: The value 'B1' of element "
        + "'NIF' is not valid. This is usually caused by an invalid structure or value in the "
        + "return. Check the data named in the error and generate the file again.",
        ClassicMessageRemap.remapText(classic, "en_US"));
  }

  /**
   * A failed {@code /batch}: the batch-level error and each per-operation error are remapped, and
   * each error object gets its own key.
   */
  @Test
  public void aBatchBodyIsRemappedAtEveryLevel() throws Exception {
    JSONObject body = new JSONObject()
        .put("failedAt", new JSONObject().put("index", 1))
        .put("error", new JSONObject().put("status", 422).put("message", REVERSED_ES))
        .put("results", new JSONArray()
            .put(new JSONObject().put("ok", true).put("id", "A1"))
            .put(new JSONObject().put("ok", false).put("error",
                new JSONObject().put("message", "Process execution failed: " + CERT_ES))));

    ClassicMessageRemap.applyToBody(body, "es_ES");

    JSONObject batchError = body.getJSONObject("error");
    assertEquals(REVERSED_GO_ES, batchError.getString("message"));
    assertEquals("ETVFAC_reversed_invoice", batchError.getJSONArray("messageKeys").getString(0));
    JSONObject opError = body.getJSONArray("results").getJSONObject(1).getJSONObject("error");
    assertEquals("Process execution failed: " + CERT_GO_ES, opError.getString("message"));
    assertEquals("AEATSII_EmptyCertificatePath",
        opError.getJSONArray("messageKeys").getString(0));
    assertFalse(body.getJSONArray("results").getJSONObject(0).has("messageKeys"));
  }

  /** An async batch job poll: the finished job's result is the batch body, one level deeper. */
  @Test
  public void anAsyncBatchJobPollIsRemapped() throws Exception {
    JSONObject body = new JSONObject().put("jobId", "J1").put("state", "failed")
        .put("result", new JSONObject().put("results", new JSONArray().put(new JSONObject()
            .put("ok", false).put("error", new JSONObject().put("status", 422)
                .put("message", CONFIG_ES)))));

    ClassicMessageRemap.applyToBody(body, "es_ES");

    JSONObject error = body.getJSONObject("result").getJSONArray("results").getJSONObject(0)
        .getJSONObject("error");
    assertEquals(CONFIG_GO_ES, error.getString("message"));
    assertEquals("TBAI_ConfigNotFound", error.getJSONArray("messageKeys").getString(0));
    assertEquals("J1", body.getString("jobId"));
  }

  /** Arrays inside arrays are walked too; outside an error object no key array is created. */
  @Test
  public void nestedArraysAreWalked() throws Exception {
    JSONObject body = new JSONObject().put("data", new JSONArray()
        .put(new JSONArray().put(new JSONObject().put("text", REVERSED_ES)))
        .put(new JSONArray().put(new JSONArray().put(new JSONObject().put("msgText", CERT_ES)))));

    ClassicMessageRemap.applyToBody(body, "es_ES");

    JSONObject first = body.getJSONArray("data").getJSONArray(0).getJSONObject(0);
    assertEquals(REVERSED_GO_ES, first.getString("text"));
    assertFalse(first.has("messageKeys"));
    assertEquals(CERT_GO_ES, body.getJSONArray("data").getJSONArray(1).getJSONArray(0)
        .getJSONObject(0).getString("msgText"));
  }

  /**
   * A raw token is replaced however short the text (a whole message can be just
   * {@code @TBAI_SetKey@}), and keeps its key; a short text that is not a token is never tried.
   */
  @Test
  public void aShortRawTokenIsRemappedAndAShortTextIsLeftAlone() throws Exception {
    JSONObject error = new JSONObject().put("message", "@TBAI_SetKey@");
    JSONObject body = new JSONObject().put("error", error);

    ClassicMessageRemap.applyToBody(body, "es_ES");

    assertTrue(error.getString("message").startsWith("Faltan datos fiscales de TicketBAI"));
    assertEquals("TBAI_SetKey", error.getJSONArray("messageKeys").getString(0));
    assertTrue(ClassicMessageRemap.remapText("Error al completar: @TBAI_SetKey@", "es_ES")
        .startsWith("Error al completar: Faltan datos fiscales de TicketBAI"));
    assertEquals("Tipo factura vacío", ClassicMessageRemap.remapText("Tipo factura vacío", "es_ES"));
  }

  /** A leading placeholder takes only the letters and digits right before the first fragment. */
  @Test
  public void aLeadingPlaceholderTakesOnlyTheTrailingAlphanumericRun() {
    String rest = " factura(s) enviadas con éxito<br />\n      0 factura(s) no enviadas<br />\n"
        + "      0 factura(s) enviadas previamente<br />\n      \n"
        + "      Por favor, refresque la pantalla.";
    String go = "Facturas enviadas con éxito: 3. No enviadas: 0. Enviadas previamente: 0.";

    assertEquals("Resultado:" + go, ClassicMessageRemap.remapText("Resultado:3" + rest, "es_ES"));
    assertEquals("(" + go + ")", ClassicMessageRemap.remapText("(3" + rest + ")", "es_ES"));
  }

  /**
   * An earlier, incomplete copy of the first fragment does not become part of the match: the
   * full message after it is remapped and the copy stays as it was.
   */
  @Test
  public void anEarlierIncompleteOccurrenceDoesNotHideTheFullMatch() {
    String text = "Error interno al generar el fichero XML: (copia cortada) / "
        + String.format(MARSHALL, "X");

    assertEquals("Error interno al generar el fichero XML: (copia cortada) / Error interno al "
        + "generar el fichero XML: X. Suele deberse a que la estructura o algún dato de la "
        + "declaración no es válido. Revisa los datos indicados en el error y vuelve a generar el "
        + "fichero.", ClassicMessageRemap.remapText(text, "es_ES"));
  }

  /** A blank or missing literal sentence yields an entry that matches nothing, no failure. */
  @Test
  public void aBlankLiteralSentenceIsHarmless() {
    ClassicMessageRemap.Entry none = ClassicMessageRemap.Entry.ofLiteral("k", null);
    ClassicMessageRemap.Entry blank = ClassicMessageRemap.Entry.ofLiteral("k", "   ");

    assertEquals("k", none.catalogKey);
    assertEquals("k", blank.catalogKey);
  }

  /** Two catalogued messages in one string: both are remapped and both keys published. */
  @Test
  public void twoCataloguedMessagesInOneStringAreBothRemapped() throws Exception {
    NeoResponse response = NeoResponse.error(422, REVERSED_ES + "\n" + CERT_ES);

    ClassicMessageRemap.apply(response, "es_ES");

    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(REVERSED_GO_ES + " " + CERT_GO_ES, error.getString("message"));
    JSONArray keys = error.getJSONArray("messageKeys");
    assertEquals(2, keys.length());
    List<String> published = List.of(keys.getString(0), keys.getString(1));
    assertTrue(published.toString(), published.contains("ETVFAC_reversed_invoice"));
    assertTrue(published.toString(), published.contains("AEATSII_EmptyCertificatePath"));
  }
}
