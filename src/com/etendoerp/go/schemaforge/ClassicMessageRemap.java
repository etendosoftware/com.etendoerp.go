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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoAdminMode;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

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
 * handlers' {@code sendError}, batch-job polls) and {@code McpResponseSanitizer#render} (every MCP
 * JSON tool result) call {@link #apply}; nothing else needs to.
 *
 * <p><b>Recognition.</b> Every string under a {@code message}, {@code text} or {@code msgText} key
 * of the body, at any depth, is tried against each {@link #ENTRIES entry}:
 * <ul>
 *   <li>an <em>AD_MESSAGE entry</em> matches the {@code AD_MESSAGE} base text and every
 *       {@code AD_MESSAGE_TRL} text of its key (read from the database once, cached), because the
 *       Classic text may arrive in a language other than the request's. {@code %0..%n} and
 *       {@code %s} become capture groups, handed to the GO text as {@code {0}..{n}}. The raw,
 *       unparsed {@code @KEY@} form is recognised too.</li>
 *   <li>a <em>literal entry</em> matches a fixed Classic sentence that has no {@code AD_MESSAGE}
 *       (the SII pre-send validations of {@code SIIUtils}, ETP-5745).</li>
 * </ul>
 * Both sides are compared with HTML line breaks, {@code &quot;}, the {@code \n} that
 * {@code messageBD} escapes and runs of whitespace normalised, so a translation indented with
 * newlines still matches. Only the matched span is replaced.
 *
 * <p><b>Text.</b> The GO text is the {@code neo_hints_<lang>.properties} catalog entry keyed by the
 * {@code AD_MESSAGE} value (or the literal entry's own key), in the language captured at the start
 * of the request ({@link NeoLanguage#requestCode()}), never the current {@code OBContext} language,
 * which a Classic call can change (see {@code SiiSendHandler}).
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
  private static final Set<String> TEXT_KEYS = Set.of("message", "text", "msgText");
  /** Shortest text worth trying: every catalogued message is longer. */
  private static final int MIN_TEXT_LENGTH = 20;
  /** Do not retry a failed catalog read on every response. */
  private static final long RELOAD_BACKOFF_MS = 30_000L;

  private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+|s)");
  private static final Pattern HTML_BREAK = Pattern.compile("(?i)<br\\s*/?>");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
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
      Entry.adMessage("ETVFAC_invoice_type_rectifying"),
      Entry.adMessage("ETVFAC_reversed_invoice"),
      Entry.adMessage("AEAT349_CINVREV_CHECK"),
      Entry.adMessage("AEATSII_EmptyCertificatePath"),
      Entry.adMessage("TBAI_CertificateNotValid"),
      Entry.adMessage("AEATSII_InvoiceManualSending"),
      Entry.adMessage("AEAT390_MARSHALLERROR"),
      Entry.adMessage("AEAT303_Periodicity_NotValid"),
      Entry.adMessage("AEAT349_Periodicity_NotValid"),
      Entry.adMessage("AEAT303_Period_NotDetected"),
      Entry.adMessage("AEAT349_Period_NotDetected"),
      Entry.adMessage("ETVFAC_Missing_DocType_Config"),
      Entry.adMessage("AEATSII_SII_ACCRUAL_DATE_EMPTY"),
      Entry.adMessage("TBAI_ConfigNotFound"),
      // B — the field has another name in GO
      Entry.adMessage("ETVFAC_error_code_idtype"),
      Entry.adMessage("TBAI_EmptyNifCode"),
      Entry.adMessage("TBAI_EmptyPostalCode"),
      Entry.adMessage("TBAI_EmptyDir"),
      // C — misleading in both products, corrected in GO only
      Entry.adMessage("TBAI_SetKey"),
      Entry.adMessage("TBAI_NullCertificate"),
      // Literal Classic sentences with no AD_MESSAGE (SIIUtils.invoicePreviousValidations,
      // ETP-5745). GO labels: sifDataTabs.field.invoiceType ("Tipo factura") and the purchase
      // invoice's POReference override ("Nº documento"), see purchase-invoice.md.
      Entry.literal("siiMissingInvoiceType",
          "La factura debe contener información en el campo Clave tipo factura."),
      Entry.literal("siiMissingDocumentNo",
          "La factura de compra debe contener información en el campo Referencia del proveedor."));

  /** Compiled patterns per AD_MESSAGE key; filled once from the database. */
  private static volatile Map<String, List<Template>> adMessageTemplates;
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

  /**
   * The remapped text, or {@code text} itself when it carries no catalogued Classic message.
   * Total: never throws.
   *
   * @param text     the text as received; may be {@code null}
   * @param language the language of the GO text
   * @return the text with each recognised Classic message replaced by its GO text
   */
  public static String remapText(String text, String language) {
    Result result = remap(text, language);
    return result == null ? text : result.text;
  }

  // ── walking the body ────────────────────────────────────────────────────────────────────────

  private static void visit(JSONObject object, String keyInParent, String language)
      throws Exception {
    List<String> keys = new ArrayList<>();
    for (Iterator<?> it = object.keys(); it.hasNext();) {
      keys.add(String.valueOf(it.next()));
    }
    for (String key : keys) {
      Object value = object.opt(key);
      if (value instanceof String && TEXT_KEYS.contains(key)) {
        Result result = remap((String) value, language);
        if (result != null) {
          if (!result.text.equals(value)) {
            object.put(key, result.text);
          }
          recordKeys(object, keyInParent, result.adMessageKeys);
        }
      } else if (value instanceof JSONObject) {
        visit((JSONObject) value, key, language);
      } else if (value instanceof JSONArray) {
        visit((JSONArray) value, language);
      }
    }
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

  /** Adds the recognised keys to the object's {@code messageKeys}, see the class javadoc. */
  private static void recordKeys(JSONObject object, String keyInParent, List<String> keys)
      throws Exception {
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

  // ── recognising one text ────────────────────────────────────────────────────────────────────

  /** The outcome of a remap that changed something. */
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
    if (text == null || text.length() < MIN_TEXT_LENGTH) {
      return null;
    }
    try {
      List<String> recognised = new ArrayList<>();
      String current = remapRawTokens(text, language, recognised);
      String normalized = null;
      for (Entry entry : ENTRIES) {
        for (Template template : templatesOf(entry)) {
          if (normalized == null) {
            normalized = normalize(current);
          }
          String replaced = template.replaceIn(normalized, entry.catalogKey, language);
          if (replaced != null) {
            normalized = replaced;
            current = replaced;
            if (entry.adMessage && !recognised.contains(entry.catalogKey)) {
              recognised.add(entry.catalogKey);
            }
            break;
          }
        }
      }
      recogniseGoTexts(current, recognised);
      if (current.equals(text)) {
        return recognised.isEmpty() ? null : new Result(text, recognised);
      }
      return new Result(current.trim(), recognised);
    } catch (Exception e) {
      log.debug("[ETP-6158] Could not remap a Classic message: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Adds to {@code recognised} the AD_MESSAGE key of every GO text already present in
   * {@code text}, in any catalog language. A text can be remapped before it reaches the exit that
   * publishes the key (a plain-text MCP error, a handler that remaps itself, a tool result parsed
   * and rendered again by {@code etendo_action_batch}); recognising the GO text there keeps the
   * identity without touching the text, and keeps the remap idempotent.
   */
  private static void recogniseGoTexts(String text, List<String> recognised) {
    String normalized = normalize(text);
    for (Map.Entry<String, List<Template>> go : goTemplates().entrySet()) {
      if (recognised.contains(go.getKey())) {
        continue;
      }
      for (Template template : go.getValue()) {
        if (template.matches(normalized)) {
          recognised.add(go.getKey());
          break;
        }
      }
    }
  }

  /** The GO texts of the AD_MESSAGE entries as templates, {@code {n}} as placeholders. */
  private static volatile Map<String, List<Template>> goTemplates;

  private static Map<String, List<Template>> goTemplates() {
    Map<String, List<Template>> current = goTemplates;
    if (current == null) {
      Map<String, List<Template>> built = new java.util.LinkedHashMap<>();
      for (Entry entry : ENTRIES) {
        if (!entry.adMessage) {
          continue;
        }
        List<Template> templates = new ArrayList<>();
        for (String language : GO_LANGUAGES) {
          String pattern = NeoHints.catalogText(entry.catalogKey, language);
          if (pattern != null) {
            Template template = Template.compile(GO_PLACEHOLDER.matcher(pattern).replaceAll("%$1"));
            if (template != null) {
              templates.add(template);
            }
          }
        }
        built.put(entry.catalogKey, Collections.unmodifiableList(templates));
      }
      current = Collections.unmodifiableMap(built);
      goTemplates = current;
    }
    return current;
  }

  /** Replaces raw {@code @KEY@} tokens of catalogued, parameterless AD_MESSAGEs. */
  private static String remapRawTokens(String text, String language, List<String> recognised) {
    if (text.indexOf('@') < 0) {
      return text;
    }
    String result = text;
    for (Entry entry : ENTRIES) {
      String token = "@" + entry.catalogKey + "@";
      if (entry.adMessage && result.contains(token)) {
        String go = goText(entry.catalogKey, language);
        // a raw token carries no arguments: a GO text that needs them is not usable here
        if (go != null && !go.contains("{")) {
          result = result.replace(token, go);
          recognised.add(entry.catalogKey);
        }
      }
    }
    return result;
  }

  private static List<Template> templatesOf(Entry entry) {
    if (!entry.adMessage) {
      return entry.literalTemplate;
    }
    Map<String, List<Template>> loaded = loadedTemplates();
    List<Template> templates = loaded == null ? null : loaded.get(entry.catalogKey);
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
    return text == null ? null : StringUtils.trimToNull(text);
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
    String result = text.replace("&quot;", "\"").replace("\\n", " ");
    result = HTML_BREAK.matcher(result).replaceAll(" ");
    return WHITESPACE.matcher(result).replaceAll(" ").trim();
  }

  /** One Classic text compiled to a pattern; its groups are the placeholders, in order. */
  static final class Template {
    private final Pattern pattern;
    /** For each capture group, the index of the GO text argument it fills. */
    private final int[] argIndexes;
    private final int argCount;

    private Template(Pattern pattern, int[] argIndexes, int argCount) {
      this.pattern = pattern;
      this.argIndexes = argIndexes;
      this.argCount = argCount;
    }

    /**
     * Compiles a Classic text. {@code %N} fills argument N, each {@code %s} the next argument in
     * order. Whitespace is optional around every literal word, so an empty placeholder still
     * matches; the last placeholder is greedy when nothing follows it.
     *
     * @return the template, or {@code null} for a blank text
     */
    static Template compile(String classicText) {
      String normalized = normalize(classicText);
      if (normalized.isEmpty()) {
        return null;
      }
      StringBuilder regex = new StringBuilder();
      List<Integer> indexes = new ArrayList<>();
      int positional = 0;
      int max = -1;
      Matcher matcher = PLACEHOLDER.matcher(normalized);
      int last = 0;
      while (matcher.find()) {
        appendLiteral(regex, normalized.substring(last, matcher.start()));
        boolean atEnd = normalized.substring(matcher.end()).trim().isEmpty();
        regex.append(atEnd ? "(.*)" : "(.*?)");
        int index = "s".equals(matcher.group(1)) ? positional++ : Integer.parseInt(matcher.group(1));
        indexes.add(index);
        max = Math.max(max, index);
        last = matcher.end();
      }
      appendLiteral(regex, normalized.substring(last));
      int[] argIndexes = indexes.stream().mapToInt(Integer::intValue).toArray();
      return new Template(Pattern.compile(regex.toString(), Pattern.DOTALL), argIndexes, max + 1);
    }

    private static void appendLiteral(StringBuilder regex, String literal) {
      String[] words = literal.trim().split(" ");
      for (String word : words) {
        if (!word.isEmpty()) {
          regex.append("\\s*").append(Pattern.quote(word));
        }
      }
      regex.append("\\s*");
    }

    /** Whether {@code normalized} contains this text. */
    boolean matches(String normalized) {
      return pattern.matcher(normalized).find();
    }

    /**
     * {@code normalized} with the first match replaced by the GO text of {@code catalogKey}, or
     * {@code null} when there is no match or no GO text.
     */
    String replaceIn(String normalized, String catalogKey, String language) {
      Matcher matcher = pattern.matcher(normalized);
      if (!matcher.find()) {
        return null;
      }
      Object[] args = new Object[argCount];
      Arrays.fill(args, "");
      for (int g = 0; g < argIndexes.length; g++) {
        args[argIndexes[g]] = StringUtils.trimToEmpty(matcher.group(g + 1));
      }
      String go = goText(catalogKey, language, args);
      if (go == null) {
        return null;
      }
      String before = normalized.substring(0, matcher.start()).trim();
      String after = normalized.substring(matcher.end()).trim();
      return WHITESPACE.matcher((before + " " + go + " " + after)).replaceAll(" ").trim();
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

    static Entry adMessage(String value) {
      return new Entry(value, true, Collections.emptyList());
    }

    static Entry literal(String catalogKey, String classicSentence) {
      return new Entry(catalogKey, false, List.of(Template.compile(classicSentence)));
    }
  }

  // ── AD_MESSAGE texts ────────────────────────────────────────────────────────────────────────

  /**
   * The compiled templates of every AD_MESSAGE entry, read in one query the first time they are
   * needed. A failed read (no session, an aborted transaction) is retried after
   * {@link #RELOAD_BACKOFF_MS}, never on every response.
   */
  private static Map<String, List<Template>> loadedTemplates() {
    Map<String, List<Template>> current = adMessageTemplates;
    if (current != null) {
      return current;
    }
    if (System.currentTimeMillis() - lastFailedLoad < RELOAD_BACKOFF_MS) {
      return null;
    }
    synchronized (ClassicMessageRemap.class) {
      if (adMessageTemplates == null) {
        Map<String, List<String>> texts = readTexts();
        if (texts == null) {
          lastFailedLoad = System.currentTimeMillis();
          return null;
        }
        adMessageTemplates = compileAll(texts);
      }
      return adMessageTemplates;
    }
  }

  static Map<String, List<Template>> compileAll(Map<String, List<String>> texts) {
    Map<String, List<Template>> compiled = new HashMap<>();
    texts.forEach((key, list) -> {
      List<Template> templates = new ArrayList<>();
      List<String> seen = new ArrayList<>();
      for (String text : list) {
        String normalized = text == null ? "" : normalize(text);
        if (!normalized.isEmpty() && !seen.contains(normalized)) {
          seen.add(normalized);
          templates.add(Template.compile(text));
        }
      }
      compiled.put(key, Collections.unmodifiableList(templates));
    });
    return Collections.unmodifiableMap(compiled);
  }

  /** For tests: replaces the AD_MESSAGE texts ({@code null} forgets them). */
  static void setTextsForTest(Map<String, List<String>> texts) {
    adMessageTemplates = texts == null ? null : compileAll(texts);
    lastFailedLoad = 0L;
  }

  /** Base text and every translation of every AD_MESSAGE entry, or {@code null} on failure. */
  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> readTexts() {
    List<String> keys = new ArrayList<>();
    for (Entry entry : ENTRIES) {
      if (entry.adMessage) {
        keys.add(entry.catalogKey);
      }
    }
    try {
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
    } catch (Exception e) {
      log.debug("[ETP-6158] Could not read the Classic message texts: {}", e.getMessage());
      return null;
    }
  }
}
