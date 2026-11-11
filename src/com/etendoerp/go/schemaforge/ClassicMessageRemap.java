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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoAdminMode;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.usage.IsolatedDalRead;

/**
 * Replaces, on NEO's way out, the text of a Classic module message that names Classic-only UI (a
 * tab, a process, a window, a field label that differs in Etendo GO) with a GO-authored text in the
 * request language (ETP-6158).
 *
 * <p><b>Why here.</b> The Spanish fiscal modules (VERI*FACTU, TicketBAI, SII, AEAT 303/349/390)
 * share their {@code AD_MESSAGE} rows with Classic, so the rows themselves cannot change. The text
 * reaches NEO already resolved — by {@code OBMessageUtils.messageBD}, a DB function's
 * {@code RAISE}, a process result — so the only thing left to recognise is the text itself.
 *
 * <p><b>Choke points.</b> {@code NeoServlet#writeResponse} (REST single, {@code /batch}, the fiscal
 * handlers' {@code sendError}, batch-job polls) calls {@link #apply}; {@code McpResponseSanitizer}
 * (every MCP JSON tool result) calls {@link #applyToBody} or, for an error result,
 * {@link #applyToErrorBody}; the plain-text MCP error ({@code McpToolRouter#wrapAsErrorContent})
 * calls {@link #remapText}. A handler that rewords early ({@code SiiSendHandler}) is harmless: the
 * remap is idempotent.
 *
 * <p><b>Recognition.</b> Every string under a {@code message}, {@code text}, {@code msgText} or
 * {@code detail} key of the body, at any depth, of 20 ({@code MIN_TEXT_LENGTH}) to
 * {@link #MAX_TEXT_LENGTH} characters, is tried against each {@link #ENTRIES entry}:
 * <ul>
 *   <li>an <em>AD_MESSAGE entry</em> matches the {@code AD_MESSAGE} base text and every
 *       {@code AD_MESSAGE_TRL} text of its key (read once, in a session of its own, and cached),
 *       because the Classic text may arrive in a language other than the request's.
 *       {@code %0..%n} and {@code %s} are placeholders, handed to the GO text as
 *       {@code {0}..{n}}. The raw, unparsed {@code @KEY@} form is recognised too.</li>
 *   <li>a <em>literal entry</em> matches a fixed Classic sentence that has no {@code AD_MESSAGE}
 *       (the SII pre-send validations of {@code SIIUtils}, ETP-5745).</li>
 * </ul>
 * Matching uses no regular expression over the text: a template is the list of its literal
 * fragments, found in order with {@code indexOf}, so the cost is linear in the text whatever it
 * holds. A placeholder between two fragments takes what lies between them; one that starts the
 * template takes only the run of letters and digits before the first fragment; one that ends it
 * takes the rest. A raw {@code @KEY@} token is replaced whatever the length of the text. Both sides
 * are compared with HTML line breaks, {@code &quot;}, the {@code \n} that {@code messageBD} escapes
 * and runs of whitespace normalised; the match and every argument are mapped back to the original
 * string, so nothing outside the matched span changes.
 *
 * <p><b>Text.</b> The GO text is the {@code neo_hints_<lang>.properties} catalog entry keyed by the
 * {@code AD_MESSAGE} value (or the literal entry's own key), in the language captured at the start
 * of the request ({@link NeoLanguage#requestCode()}), never the current {@code OBContext} language,
 * which a Classic call can change (see {@code SiiSendHandler}); the English entry when that
 * language's bundle lacks the key, and the Classic text left as is when neither has it.
 *
 * <p><b>Identity.</b> When the recognised text sits next to a {@code messageKeys} array, or inside
 * an {@code error} object (or at the root of an MCP error result, {@link #applyToErrorBody}), its
 * {@code AD_MESSAGE} key is added there, so a client keyed on the identity keeps finding it. A text
 * that is already the GO text of an entry, in any catalog language, is recognised too: the key is
 * published and the text left alone, so the remap is idempotent and the key survives a text that
 * was reworded before the exit that publishes it (an {@code etendo_action_batch} document result).
 *
 * <p><b>Total.</b> Nothing here can fail a response: any failure leaves the original text.
 */
public final class ClassicMessageRemap {

  private static final Logger log = LogManager.getLogger(ClassicMessageRemap.class);

  private static final String ENGLISH = "en";
  private static final String KEY_MESSAGE_KEYS = "messageKeys";
  private static final String KEY_ERROR = "error";
  /** The body keys whose string value is a message for a person. */
  private static final Set<String> TEXT_KEYS = Set.of("message", "text", "msgText", "detail");
  /** Shortest text worth trying: every catalogued message is longer. */
  private static final int MIN_TEXT_LENGTH = 20;
  /** Longest text tried: a longer one is not a message and is left untouched. */
  static final int MAX_TEXT_LENGTH = 8192;
  /** Most occurrences of one message replaced in one text. */
  private static final int MAX_REPLACEMENTS = 10;
  /** Do not retry a failed catalog read on every response. */
  private static final long RELOAD_BACKOFF_MS = 30_000L;

  private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+|s)");
  /** An HTML line break, also as a JSON serializer writes it ({@code <br \/>}). */
  private static final Pattern HTML_BREAK = Pattern.compile("(?i)<br\\s{0,20}\\\\?/?>");
  /** A {@code {n}} argument of a GO text (left as is by a format call with no arguments). */
  private static final Pattern GO_PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");
  /** The catalog languages a GO text may already be in. */
  private static final List<String> GO_LANGUAGES = List.of("es", ENGLISH);

  /**
   * The catalogued messages: only messages a GO user can actually trigger (a message GO never
   * shows is left out, ETP-6158). To add one: add the entry here and its text to BOTH
   * {@code messages/neo_hints_es.properties} and {@code neo_hints_en.properties} under the same key
   * (see neo-headless.md §4.17).
   */
  static final List<Entry> ENTRIES = List.of(
      // A — the text names Classic-only UI
      Entry.ofAdMessage("ETVFAC_invoice_type_rectifying"),
      Entry.ofAdMessage("ETVFAC_reversed_invoice"),
      Entry.ofAdMessage("AEAT349_CINVREV_CHECK"),
      Entry.ofAdMessage("AEATSII_EmptyCertificatePath"),
      Entry.ofAdMessage("TBAI_CertificateNotValid"),
      Entry.ofAdMessage("AEATSII_InvoiceManualSending"),
      Entry.ofAdMessage("AEAT390_MARSHALLERROR"),
      Entry.ofAdMessage("AEAT303_Periodicity_NotValid"),
      Entry.ofAdMessage("AEAT349_Periodicity_NotValid"),
      Entry.ofAdMessage("AEAT303_Period_NotDetected"),
      Entry.ofAdMessage("AEAT349_Period_NotDetected"),
      Entry.ofAdMessage("ETVFAC_Missing_DocType_Config"),
      Entry.ofAdMessage("AEATSII_SII_ACCRUAL_DATE_EMPTY"),
      Entry.ofAdMessage("TBAI_ConfigNotFound"),
      // B — the field has another name in GO
      Entry.ofAdMessage("ETVFAC_error_code_idtype"),
      Entry.ofAdMessage("TBAI_EmptyNifCode"),
      Entry.ofAdMessage("TBAI_EmptyPostalCode"),
      Entry.ofAdMessage("TBAI_EmptyDir"),
      // C — misleading in both products, corrected in GO only
      Entry.ofAdMessage("TBAI_SetKey"),
      Entry.ofAdMessage("TBAI_NullCertificate"),
      // Literal Classic sentences with no AD_MESSAGE (SIIUtils.invoicePreviousValidations,
      // ETP-5745). GO labels: sifDataTabs.field.invoiceType ("Tipo factura") and the purchase
      // invoice's POReference override ("Nº documento"), see purchase-invoice.md.
      Entry.ofLiteral("siiMissingInvoiceType",
          "La factura debe contener información en el campo Clave tipo factura."),
      Entry.ofLiteral("siiMissingDocumentNo",
          "La factura de compra debe contener información en el campo Referencia del proveedor."));

  /** Templates per AD_MESSAGE key, from the database; {@code null} until read. */
  private static final AtomicReference<Map<String, List<Template>>> AD_TEMPLATES =
      new AtomicReference<>();
  /** Templates of the GO texts per AD_MESSAGE key, from the catalog; {@code null} until built. */
  private static final AtomicReference<Map<String, List<Template>>> GO_TEMPLATES =
      new AtomicReference<>();
  private static final Object LOAD_LOCK = new Object();
  private static volatile long lastFailedLoad;

  private ClassicMessageRemap() {
  }

  /**
   * {@link #apply(NeoResponse, String)} in the language captured at the start of the request.
   *
   * @param response the response about to be written; may be {@code null}
   * @return the same response
   */
  public static NeoResponse apply(NeoResponse response) {
    if (response == null || response.getBody() == null) {
      return response;
    }
    return apply(response, requestLanguage());
  }

  /**
   * Remaps, in place, every catalogued Classic text of the response body.
   *
   * @param response the response; may be {@code null}
   * @param language the language of the GO text (an AD code such as {@code es_ES}); English when
   *                 blank or not in the catalog
   * @return the same response
   */
  public static NeoResponse apply(NeoResponse response, String language) {
    if (response != null) {
      applyToBody(response.getBody(), language);
    }
    return response;
  }

  /**
   * {@link #applyToBody(JSONObject, String)} in the language captured at the start of the request.
   *
   * @param body the body, mutated in place; may be {@code null}
   */
  public static void applyToBody(JSONObject body) {
    if (body != null) {
      applyToBody(body, requestLanguage());
    }
  }

  /**
   * Remaps, in place, every catalogued Classic text under a message key of {@code body}, at any
   * depth. Total: never throws.
   *
   * @param body     the body, mutated in place; may be {@code null}
   * @param language the language of the GO text
   */
  public static void applyToBody(JSONObject body, String language) {
    visitRoot(body, null, language);
  }

  /**
   * {@link #applyToBody(JSONObject)} for a body that is itself an error object (an MCP error
   * tool result, whose root is the flattened error the client reads): a recognised message at
   * its root gets its key in a root {@code messageKeys}, as one inside {@code error} does.
   *
   * @param body the error body, mutated in place; may be {@code null}
   */
  public static void applyToErrorBody(JSONObject body) {
    if (body != null) {
      visitRoot(body, KEY_ERROR, requestLanguage());
    }
  }

  /**
   * The remapped text, or {@code text} itself when it carries no catalogued Classic message.
   * Total: never throws.
   *
   * @param text     the text as received; may be {@code null}
   * @param language the language of the GO text
   * @return the text with each recognised Classic message replaced by its GO text
   */
  public static String remapText(String text, String language) {
    String json = remapJsonString(text, language);
    if (json != null) {
      return json;
    }
    Result result = remap(text, language);
    return result == null ? text : result.text;
  }

  // ── walking the body ────────────────────────────────────────────────────────────────────────

  private static void visitRoot(JSONObject body, String rootKey, String language) {
    if (body == null) {
      return;
    }
    try {
      visit(body, rootKey, language);
    } catch (Exception e) {
      log.debug("[ETP-6158] Classic message remap skipped: {}", e.getMessage());
    }
  }

  private static void visit(JSONObject object, String keyInParent, String language)
      throws Exception {
    List<String> keys = new ArrayList<>();
    for (Iterator<?> it = object.keys(); it.hasNext();) {
      keys.add(String.valueOf(it.next()));
    }
    for (String key : keys) {
      visitValue(object, key, keyInParent, language);
    }
  }

  private static void visitValue(JSONObject object, String key, String keyInParent,
      String language) throws Exception {
    Object value = object.opt(key);
    if (value instanceof String && TEXT_KEYS.contains(key)) {
      remapField(object, key, (String) value, keyInParent, language);
    } else if (value instanceof JSONObject) {
      visit((JSONObject) value, key, language);
    } else if (value instanceof JSONArray) {
      visit((JSONArray) value, language);
    }
  }

  private static void remapField(JSONObject object, String key, String value, String keyInParent,
      String language) throws Exception {
    String json = remapJsonString(value, language);
    if (json != null) {
      object.put(key, json);
      return;
    }
    Result result = remap(value, language);
    if (result == null) {
      return;
    }
    if (!result.text.equals(value)) {
      object.put(key, result.text);
    }
    MessageKeys.publish(object, keyInParent, result.adMessageKeys);
  }

  private static void visit(JSONArray array, String language) throws Exception {
    for (int i = 0; i < array.length(); i++) {
      Object value = array.opt(i);
      if (value instanceof JSONObject) {
        visit((JSONObject) value, null, language);
      } else if (value instanceof JSONArray) {
        visit((JSONArray) value, language);
      }
    }
  }

  /**
   * A string that is itself a serialized JSON object (an MCP content item, a body rendered and
   * then wrapped as text) is remapped on its parsed values, never on its serialized text: the JSON
   * escapes ({@code \n}, {@code \/}, {@code \"}) are not message text and must survive intact.
   *
   * @return the serialized object when one of its values changed, else {@code null} (also when
   *         the string is not a JSON object, or is longer than {@link #MAX_TEXT_LENGTH})
   */
  private static String remapJsonString(String text, String language) {
    if (text == null || text.length() > MAX_TEXT_LENGTH) {
      return null;
    }
    String trimmed = text.trim();
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      return null;
    }
    try {
      JSONObject parsed = new JSONObject(trimmed);
      String before = parsed.toString();
      visit(parsed, null, language);
      String after = parsed.toString();
      return before.equals(after) ? null : after;
    } catch (Exception notJson) {
      return null;
    }
  }

  /** Publishes recognised keys in {@code messageKeys}, see the class javadoc. */
  private static final class MessageKeys {

    private MessageKeys() {
    }

    /** Adds the recognised keys to the object's {@code messageKeys}. */
    static void publish(JSONObject object, String keyInParent, List<String> keys) throws Exception {
      if (keys.isEmpty()) {
        return;
      }
      JSONArray messageKeys = object.optJSONArray(KEY_MESSAGE_KEYS);
      if (messageKeys == null) {
        if (!KEY_ERROR.equals(keyInParent)) {
          return;
        }
        messageKeys = new JSONArray();
        object.put(KEY_MESSAGE_KEYS, messageKeys);
      }
      for (String key : keys) {
        if (!contains(messageKeys, key)) {
          messageKeys.put(key);
        }
      }
    }

    private static boolean contains(JSONArray array, String value) {
      for (int i = 0; i < array.length(); i++) {
        if (value.equals(array.optString(i, null))) {
          return true;
        }
      }
      return false;
    }
  }

  // ── recognising one text ────────────────────────────────────────────────────────────────────

  /** The outcome of a remap that changed something or recognised a key. */
  private static final class Result {
    private final String text;
    private final List<String> adMessageKeys;

    private Result(String text, List<String> adMessageKeys) {
      this.text = text;
      this.adMessageKeys = adMessageKeys;
    }
  }

  /** The remapped text and the AD_MESSAGE keys recognised, or {@code null} when nothing matched. */
  private static Result remap(String text, String language) {
    if (text == null) {
      return null;
    }
    if (text.length() > MAX_TEXT_LENGTH) {
      logOverCap(text);
      return null;
    }
    try {
      List<String> recognised = new ArrayList<>();
      // a raw @KEY@ token is short: it is replaced whatever the length of the text
      String current = remapRawTokens(text, language, recognised);
      if (current.length() >= MIN_TEXT_LENGTH) {
        current = remapTemplates(current, language, recognised);
      }
      if (current.equals(text) && recognised.isEmpty()) {
        return null;
      }
      return new Result(current, recognised);
    } catch (Exception e) {
      log.debug("[ETP-6158] Could not remap a Classic message: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Every entry's templates against {@code text}, normalised once and again only after a
   * replacement; then the GO texts already present.
   */
  private static String remapTemplates(String text, String language, List<String> recognised) {
    String current = text;
    Normalized normalized = Normalized.of(current);
    for (Entry entry : ENTRIES) {
      String next = remapEntry(current, normalized, entry, language, recognised);
      if (!next.equals(current)) {
        current = next;
        normalized = Normalized.of(current);
      }
    }
    recogniseGoTexts(normalized.text, recognised);
    return current;
  }

  /** A text over the cap is left alone; say so when it looks like it held a message. */
  private static void logOverCap(String text) {
    if (!log.isDebugEnabled()) {
      return;
    }
    for (Entry entry : ENTRIES) {
      for (Template template : templatesOf(entry)) {
        if (text.contains(template.longest)) {
          log.debug("[ETP-6158] A text of {} characters, over the {} cap, holds a fragment of {} "
              + "and was left untouched", text.length(), MAX_TEXT_LENGTH, entry.catalogKey);
          return;
        }
      }
    }
  }

  /** {@code text} with every occurrence of {@code entry}'s Classic text replaced. */
  private static String remapEntry(String text, Normalized normalized, Entry entry,
      String language, List<String> recognised) {
    for (Template template : templatesOf(entry)) {
      String replaced = replaceAll(text, normalized, template, entry.catalogKey, language);
      if (replaced != null) {
        if (entry.adMessage) {
          addOnce(recognised, entry.catalogKey);
        }
        return replaced;
      }
    }
    return text;
  }

  /** Replaces occurrences until none is left (at most {@link #MAX_REPLACEMENTS}). */
  private static String replaceAll(String text, Normalized normalized, Template template,
      String catalogKey, String language) {
    String current = text;
    Normalized currentNormalized = normalized;
    boolean changed = false;
    for (int i = 0; i < MAX_REPLACEMENTS; i++) {
      String next = template.replaceIn(current, currentNormalized, catalogKey, language);
      if (next == null) {
        break;
      }
      current = next;
      currentNormalized = Normalized.of(current);
      changed = true;
    }
    return changed ? current : null;
  }

  private static void addOnce(List<String> list, String value) {
    if (!list.contains(value)) {
      list.add(value);
    }
  }

  /**
   * Adds to {@code recognised} the AD_MESSAGE key of every GO text already present in
   * {@code text}, in any catalog language. A text can be remapped before it reaches the exit that
   * publishes the key (a plain-text MCP error, a handler that remaps itself, a tool result parsed
   * and rendered again by {@code etendo_action_batch}); recognising the GO text there keeps the
   * identity without touching the text, and keeps the remap idempotent.
   */
  private static void recogniseGoTexts(String normalized, List<String> recognised) {
    for (Map.Entry<String, List<Template>> go : goTemplates().entrySet()) {
      if (!recognised.contains(go.getKey()) && anyMatches(go.getValue(), normalized)) {
        recognised.add(go.getKey());
      }
    }
  }

  private static boolean anyMatches(List<Template> templates, String normalized) {
    for (Template template : templates) {
      if (template.find(normalized) != null) {
        return true;
      }
    }
    return false;
  }

  /** The GO texts of the AD_MESSAGE entries as templates, {@code {n}} as placeholders. */
  private static Map<String, List<Template>> goTemplates() {
    Map<String, List<Template>> current = GO_TEMPLATES.get();
    if (current == null) {
      Map<String, List<Template>> built = new LinkedHashMap<>();
      for (Entry entry : ENTRIES) {
        if (entry.adMessage) {
          built.put(entry.catalogKey, goTemplatesOf(entry.catalogKey));
        }
      }
      current = Collections.unmodifiableMap(built);
      GO_TEMPLATES.set(current);
    }
    return current;
  }

  private static List<Template> goTemplatesOf(String catalogKey) {
    List<Template> templates = new ArrayList<>();
    for (String language : GO_LANGUAGES) {
      String pattern = NeoHints.catalogText(catalogKey, language);
      Template template = pattern == null ? null
          : Template.compile(GO_PLACEHOLDER.matcher(pattern).replaceAll("%$1"));
      if (template != null) {
        templates.add(template);
      }
    }
    return Collections.unmodifiableList(templates);
  }

  /**
   * Replaces raw {@code @KEY@} tokens of catalogued, parameterless AD_MESSAGEs. Whitespace that
   * only pads the token against the edge of the text is dropped with it.
   */
  private static String remapRawTokens(String text, String language, List<String> recognised) {
    if (text.indexOf('@') < 0) {
      return text;
    }
    String result = text;
    for (Entry entry : ENTRIES) {
      String token = "@" + entry.catalogKey + "@";
      String go = entry.adMessage && result.contains(token) ? goText(entry.catalogKey, language)
          : null;
      // a raw token carries no arguments: a GO text that needs them is not usable here
      if (go != null && !go.contains("{")) {
        result = replaceToken(result, token, go);
        addOnce(recognised, entry.catalogKey);
      }
    }
    return result;
  }

  private static String replaceToken(String text, String token, String go) {
    String result = text;
    int index = result.indexOf(token);
    for (int i = 0; index >= 0 && i < MAX_REPLACEMENTS; i++) {
      String before = result.substring(0, index);
      String after = result.substring(index + token.length());
      result = (before.isBlank() ? "" : before) + go + (after.isBlank() ? "" : after);
      index = result.indexOf(token);
    }
    return result;
  }

  private static List<Template> templatesOf(Entry entry) {
    if (!entry.adMessage) {
      return entry.literalTemplate;
    }
    List<Template> templates = loadedTemplates().get(entry.catalogKey);
    return templates == null ? Collections.emptyList() : templates;
  }

  /**
   * The GO text of {@code catalogKey} in {@code language}, English when that bundle lacks it, or
   * {@code null} when neither has it (then the Classic text is left alone).
   */
  static String goText(String catalogKey, String language, Object... args) {
    String text = NeoHints.catalogText(catalogKey, language, args);
    if (text == null) {
      text = NeoHints.catalogText(catalogKey, ENGLISH, args);
    }
    return text == null || text.isBlank() ? null : text.trim();
  }

  private static String requestLanguage() {
    try {
      return NeoLanguage.requestCode();
    } catch (Exception e) {
      return null;
    }
  }

  // ── normalisation and templates ─────────────────────────────────────────────────────────────

  /**
   * The form both sides are compared in: HTML line breaks, {@code &quot;}, the literal {@code \n}
   * {@code messageBD} escapes and runs of whitespace become single spaces.
   */
  static String normalize(String text) {
    return Normalized.of(text).text;
  }

  /**
   * A text in the compared form, with, for each of its characters, the span of the original text
   * it came from. Matching runs on the normalized form; the replacement and the arguments are taken
   * from the original through the map, so nothing outside the matched span changes.
   */
  static final class Normalized {
    private static final String QUOT = "&quot;";
    private static final String ESCAPED_NEWLINE = "\\n";

    final String text;
    /** Original start index of each normalized character. */
    private final int[] from;
    /** Original end index (exclusive) of each normalized character. */
    private final int[] to;
    private final int originalLength;

    private Normalized(String text, int[] from, int[] to, int originalLength) {
      this.text = text;
      this.from = from;
      this.to = to;
      this.originalLength = originalLength;
    }

    static Normalized of(String original) {
      Builder builder = new Builder(original.length());
      Matcher br = HTML_BREAK.matcher(original);
      int i = 0;
      while (i < original.length()) {
        int next = tokenEnd(original, i, br);
        builder.emit(tokenChar(original, i, next), i, next);
        i = next;
      }
      return builder.build(original.length());
    }

    /** Where the token starting at {@code i} ends: an entity, an escape, a break, or one char. */
    private static int tokenEnd(String original, int i, Matcher br) {
      if (original.startsWith(QUOT, i)) {
        return i + QUOT.length();
      }
      if (original.startsWith(ESCAPED_NEWLINE, i)) {
        return i + ESCAPED_NEWLINE.length();
      }
      if (original.charAt(i) == '<' && br.region(i, original.length()).lookingAt()) {
        return br.end();
      }
      return i + 1;
    }

    /** The compared character of the token {@code [i, next)}. */
    private static char tokenChar(String original, int i, int next) {
      if (original.startsWith(QUOT, i)) {
        return '"';
      }
      char c = original.charAt(i);
      return next - i > 1 || Character.isWhitespace(c) ? ' ' : c;
    }

    /** Accumulates the normalized text and its map; runs of whitespace become one space. */
    private static final class Builder {
      private final StringBuilder out;
      private final int[] from;
      private final int[] to;

      Builder(int capacity) {
        out = new StringBuilder(capacity);
        from = new int[capacity];
        to = new int[capacity];
      }

      void emit(char c, int start, int end) {
        int n = out.length();
        boolean space = c == ' ';
        if (space && n == 0) {
          return; // leading whitespace is dropped
        }
        if (space && out.charAt(n - 1) == ' ') {
          to[n - 1] = end; // one space for the whole run
          return;
        }
        out.append(c);
        from[n] = start;
        to[n] = end;
      }

      Normalized build(int originalLength) {
        int n = out.length();
        if (n > 0 && out.charAt(n - 1) == ' ') {
          out.setLength(n - 1);
        }
        return new Normalized(out.toString(), from, to, originalLength);
      }
    }

    boolean isSpace(int index) {
      return index >= 0 && index < text.length() && text.charAt(index) == ' ';
    }

    /** The original substring of normalized {@code [start, end)}, spaces at its edges left out. */
    String originalOf(String original, int start, int end) {
      int s = start;
      int e = end;
      while (s < e && isSpace(s)) {
        s++;
      }
      while (e > s && isSpace(e - 1)) {
        e--;
      }
      return s == e ? "" : original.substring(from[s], to[e - 1]);
    }

    /** Original start of normalized {@code index}; the text start for the first character. */
    int originalStart(int index) {
      return index == 0 ? 0 : from[index];
    }

    /** Original end of normalized {@code [.., end)}; the text end for the last character. */
    int originalEnd(int end) {
      return end == text.length() ? originalLength : to[end - 1];
    }
  }

  /** Where a template matched a normalized text: the span and one span per placeholder. */
  private static final class Match {
    private final int start;
    private final int end;
    private final int[][] groups;

    private Match(int start, int end, int[][] groups) {
      this.start = start;
      this.end = end;
      this.groups = groups;
    }
  }

  /**
   * One Classic (or GO) text as its literal fragments, in order, with a placeholder between each
   * two. Matched with {@code indexOf} only: linear in the text, no backtracking.
   */
  static final class Template {
    /** {@code literals.size() == argIndexes.length + 1}; the first and last may be empty. */
    private final List<String> literals;
    /** For each placeholder, the index of the GO text argument it fills. */
    private final int[] argIndexes;
    private final int argCount;
    /** The longest fragment: a text without it cannot match. */
    private final String longest;

    private Template(List<String> literals, int[] argIndexes, int argCount) {
      this.literals = literals;
      this.argIndexes = argIndexes;
      this.argCount = argCount;
      String best = "";
      for (String literal : literals) {
        if (literal.length() > best.length()) {
          best = literal;
        }
      }
      this.longest = best;
    }

    /**
     * Compiles a text. {@code %N} fills argument N, each {@code %s} the next argument in order.
     *
     * @return the template, or {@code null} when the text has no literal fragment at all
     */
    static Template compile(String classicText) {
      String normalized = normalize(classicText);
      List<String> literals = new ArrayList<>();
      List<Integer> indexes = new ArrayList<>();
      int positional = 0;
      int max = -1;
      Matcher matcher = PLACEHOLDER.matcher(normalized);
      int last = 0;
      while (matcher.find()) {
        literals.add(normalized.substring(last, matcher.start()).trim());
        int index = "s".equals(matcher.group(1)) ? positional++ : Integer.parseInt(matcher.group(1));
        indexes.add(index);
        max = Math.max(max, index);
        last = matcher.end();
      }
      literals.add(normalized.substring(last).trim());
      Template template = new Template(Collections.unmodifiableList(literals),
          indexes.stream().mapToInt(Integer::intValue).toArray(), max + 1);
      return template.longest.isEmpty() ? null : template;
    }

    /** The first match in {@code normalized}, or {@code null}. */
    Match find(String normalized) {
      if (!normalized.contains(longest)) {
        return null;
      }
      String first = literals.get(0);
      int start = first.isEmpty() ? 0 : normalized.indexOf(first);
      if (start < 0) {
        return null;
      }
      int[][] groups = new int[argIndexes.length][];
      int cursor = start + first.length();
      for (int i = 1; i <= argIndexes.length; i++) {
        String literal = literals.get(i);
        int at = nextLiteral(normalized, literal, cursor, i == argIndexes.length);
        if (at < 0) {
          return null;
        }
        groups[i - 1] = new int[] { cursor, at };
        cursor = at + literal.length();
      }
      if (groups.length > 0) {
        start = first.isEmpty() ? leadingStart(normalized, groups)
            : latestStart(normalized, first, start, groups);
      }
      return new Match(start, cursor, groups);
    }

    /**
     * Where {@code literal} is found from {@code cursor}; an empty one sits at the cursor, or at
     * the end of the text when it closes the template (a trailing placeholder takes the rest).
     */
    private static int nextLiteral(String normalized, String literal, int cursor, boolean last) {
      if (literal.isEmpty()) {
        return last ? normalized.length() : cursor;
      }
      return normalized.indexOf(literal, cursor);
    }

    /**
     * A placeholder that starts the template takes only the run of letters and digits right before
     * the first fragment ({@code "Resultado:3 factura(s)"} gives {@code 3}, not the whole word).
     */
    private static int[] leadingToken(String normalized, int literalAt) {
      int end = literalAt > 0 && normalized.charAt(literalAt - 1) == ' ' ? literalAt - 1 : literalAt;
      int start = end;
      while (start > 0 && Character.isLetterOrDigit(normalized.charAt(start - 1))) {
        start--;
      }
      return new int[] { start, end };
    }

    /** The start of a match whose template begins with a placeholder: its leading token. */
    private static int leadingStart(String normalized, int[][] groups) {
      groups[0] = leadingToken(normalized, groups[0][1]);
      return groups[0][0];
    }

    /**
     * Of every occurrence of the first fragment before the second one, the last: an earlier
     * occurrence that is not followed by the rest of the message (a truncated copy, the same
     * words used elsewhere) is not part of the match and never ends up inside an argument.
     */
    private static int latestStart(String normalized, String first, int start, int[][] groups) {
      int latest = normalized.lastIndexOf(first, groups[0][1] - first.length());
      if (latest <= start) {
        return start;
      }
      groups[0][0] = latest + first.length();
      return latest;
    }

    /**
     * {@code original} with the first match replaced by the GO text of {@code catalogKey}, or
     * {@code null} when there is no match or no GO text. Only the matched span changes, plus the
     * whitespace right next to it: between the GO text and other text it becomes one space, and
     * against the start or end of the string it is dropped.
     */
    String replaceIn(String original, Normalized normalized, String catalogKey, String language) {
      Match match = find(normalized.text);
      if (match == null) {
        return null;
      }
      Object[] args = new Object[argCount];
      Arrays.fill(args, "");
      for (int g = 0; g < argIndexes.length; g++) {
        args[argIndexes[g]] = normalized.originalOf(original, match.groups[g][0],
            match.groups[g][1]).trim();
      }
      String go = goText(catalogKey, language, args);
      if (go == null) {
        return null;
      }
      int start = match.start;
      int end = match.end;
      boolean spaceBefore = normalized.isSpace(start - 1);
      boolean spaceAfter = normalized.isSpace(end);
      start = spaceBefore ? start - 1 : start;
      end = spaceAfter ? end + 1 : end;
      int originalStart = normalized.originalStart(start);
      int originalEnd = normalized.originalEnd(end);
      String lead = spaceBefore ? " " : "";
      String trail = spaceAfter && end < normalized.text.length() ? " " : "";
      return original.substring(0, originalStart) + lead + go + trail
          + original.substring(originalEnd);
    }
  }

  /** One catalogued message. */
  static final class Entry {
    /** The catalog key: the AD_MESSAGE value, or the literal entry's own key. */
    final String catalogKey;
    final boolean adMessage;
    private final List<Template> literalTemplate;

    private Entry(String catalogKey, boolean adMessage, List<Template> literalTemplate) {
      this.catalogKey = catalogKey;
      this.adMessage = adMessage;
      this.literalTemplate = literalTemplate;
    }

    static Entry ofAdMessage(String value) {
      return new Entry(value, true, Collections.emptyList());
    }

    static Entry ofLiteral(String catalogKey, String classicSentence) {
      Template template = classicSentence == null || classicSentence.isBlank() ? null
          : Template.compile(classicSentence);
      return new Entry(catalogKey, false,
          template == null ? Collections.emptyList() : List.of(template));
    }
  }

  // ── AD_MESSAGE texts ────────────────────────────────────────────────────────────────────────

  /**
   * The templates of every AD_MESSAGE entry, read the first time they are needed; empty while
   * they cannot be read. A failed read is retried after {@link #RELOAD_BACKOFF_MS}, never on every
   * response.
   */
  private static Map<String, List<Template>> loadedTemplates() {
    Map<String, List<Template>> current = AD_TEMPLATES.get();
    if (current != null) {
      return current;
    }
    if (System.currentTimeMillis() - lastFailedLoad < RELOAD_BACKOFF_MS) {
      return Collections.emptyMap();
    }
    synchronized (LOAD_LOCK) {
      current = AD_TEMPLATES.get();
      if (current != null) {
        return current;
      }
      Optional<Map<String, List<String>>> texts = readTexts();
      if (texts.isEmpty()) {
        lastFailedLoad = System.currentTimeMillis();
        return Collections.emptyMap();
      }
      current = compileAll(texts.get());
      AD_TEMPLATES.set(current);
      return current;
    }
  }

  static Map<String, List<Template>> compileAll(Map<String, List<String>> texts) {
    Map<String, List<Template>> compiled = new HashMap<>();
    texts.forEach((key, list) -> compiled.put(key, compileDistinct(list)));
    return Collections.unmodifiableMap(compiled);
  }

  private static List<Template> compileDistinct(List<String> texts) {
    List<Template> templates = new ArrayList<>();
    List<String> seen = new ArrayList<>();
    for (String text : texts) {
      String normalized = text == null ? "" : normalize(text);
      Template template = normalized.isEmpty() || seen.contains(normalized) ? null
          : Template.compile(text);
      if (template != null) {
        seen.add(normalized);
        templates.add(template);
      }
    }
    return Collections.unmodifiableList(templates);
  }

  /** For tests: replaces the AD_MESSAGE texts ({@code null} forgets them). */
  static void setTextsForTest(Map<String, List<String>> texts) {
    AD_TEMPLATES.set(texts == null ? null : compileAll(texts));
    lastFailedLoad = 0L;
  }

  /**
   * Base text and every translation of every AD_MESSAGE entry (two HQL queries), read in a DAL
   * session of its own ({@link IsolatedDalRead}): a failure there can neither abort nor be
   * aborted by the request's transaction, which is still open while the response is written.
   *
   * @return the texts per key, or empty when they could not be read
   */
  private static Optional<Map<String, List<String>>> readTexts() {
    List<String> keys = new ArrayList<>();
    for (Entry entry : ENTRIES) {
      if (entry.adMessage) {
        keys.add(entry.catalogKey);
      }
    }
    try {
      return Optional.of(IsolatedDalRead.call(() -> queryTexts(keys)));
    } catch (Exception e) {
      log.debug("[ETP-6158] Could not read the Classic message texts: {}", e.getMessage());
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> queryTexts(List<String> keys) {
    NeoAdminMode.enter();
    try {
      Map<String, List<String>> texts = new HashMap<>();
      List<Object[]> base = OBDal.getInstance().getSession()
          .createQuery("select m.searchKey, m.messageText from ADMessage m"
              + " where m.searchKey in (:keys)")
          .setParameterList("keys", keys)
          .list();
      List<Object[]> translated = OBDal.getInstance().getSession()
          .createQuery("select t.message.searchKey, t.messageText from ADMessageTrl t"
              + " where t.message.searchKey in (:keys)")
          .setParameterList("keys", keys)
          .list();
      for (List<Object[]> rows : List.of(base, translated)) {
        for (Object[] row : rows) {
          texts.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
      }
      return texts;
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
