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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailMessageEdits;

/**
 * The validated arguments of {@code etendo_send_document_email} (ETP-5715).
 *
 * <p><b>Why this validates more than the server does.</b> The send path is the application's own
 * and re-validates everything, but it answers some problems by silently repairing them (the subject
 * has its CR/LF stripped) and others only after the document has been resolved. An agent's text is
 * not an operator's keystrokes, so this is the one place that REFUSES instead: an address carrying
 * a line break or a second address, a subject with a control character, a message over the cap. The
 * address syntax is the server's own ({@link EmailContractCommandSupport#isValidEmail}) plus the
 * shapes that regex lets through but no mailbox has. Nothing here is echoed back in an error: a
 * rejected value may be the injection attempt itself.</p>
 */
final class McpDocumentEmailRequest {

  /** Argument: the recipients, a list of addresses. */
  static final String PARAM_TO = "to";
  /** Argument: the copied recipients, optional. */
  static final String PARAM_CC = "cc";
  /** Argument: the subject, optional (the application's default when omitted). */
  static final String PARAM_SUBJECT = "subject";
  /** Argument: the message, optional (the application's default when omitted). */
  static final String PARAM_MESSAGE = "message";
  /** Argument: the language of the email copy, optional. */
  static final String PARAM_LANGUAGE = "language";
  /**
   * The language the send modal posts when nobody switched it. The modal sends its UI locale, which
   * lives only in the browser ({@code localStorage "schema-forge-locale"}, default {@code es_ES}:
   * {@code useLocaleState}); the user's AD language is never consulted. The MCP has no browser, so
   * it uses the modal's default.
   */
  static final String DEFAULT_LANGUAGE = "es_ES";
  /** The locales the application ships ({@code tools/app-shell/src/locales}); the copy exists in the first two. */
  static final List<String> SUPPORTED_LANGUAGES = List.of(DEFAULT_LANGUAGE, "en_US", "es_AR");
  /** Argument: {@code true} shows what would be sent and sends nothing. */
  static final String PARAM_PREVIEW = "preview";

  /** Longest address (RFC 5321). */
  static final int MAX_ADDRESS_LENGTH = 254;
  private static final int MAX_LOCAL_LENGTH = 64;
  private static final char FIRST_PRINTABLE = 0x20;
  private static final char DELETE = 0x7f;
  private static final char C1_FIRST = 0x80;
  private static final char C1_LAST = 0x9f;
  /**
   * Line and paragraph separators (line breaks that are not CR or LF) and the bidi embedding,
   * override and isolate controls, which reorder how a subject or message reads.
   */
  private static final String INVISIBLE = "\u2028\u2029\u202A\u202B\u202C\u202D\u202E"
      + "\u2066\u2067\u2068\u2069";
  private static final String HINT_ADDRESSES = "Pass 'to' as a list of plain addresses such as "
      + "[\"ana@cliente.example\"]: one address per entry, no names, no angle brackets, no commas.";

  /** An argument problem, with the hint that fixes it. */
  static final class Invalid extends Exception {
    private static final long serialVersionUID = 1L;
    private final String hint;

    Invalid(String message, String hint) {
      super(message);
      this.hint = hint;
    }

    /** The hint that tells the caller how to correct the call. */
    String getHint() {
      return hint;
    }
  }

  private final String id;
  private final List<String> to;
  private final List<String> cc;
  private final boolean preview;
  private final Texts texts;
  private final List<String> removedDuplicates;

  /** The caller's optional subject, message and language, each {@code null} when omitted. */
  private static final class Texts {
    private final String subject;
    private final String message;
    private final String language;

    private Texts(String subject, String message, String language) {
      this.subject = subject;
      this.message = message;
      this.language = language;
    }
  }

  private McpDocumentEmailRequest(String id, List<String> to, List<String> cc, boolean preview,
      Texts texts, List<String> removedDuplicates) {
    this.id = id;
    this.to = to;
    this.cc = cc;
    this.preview = preview;
    this.texts = texts;
    this.removedDuplicates = removedDuplicates;
  }

  /**
   * Validates the call's arguments.
   *
   * @param args the tool arguments
   * @return the validated request
   * @throws Invalid when any argument is missing or unacceptable
   */
  static McpDocumentEmailRequest parse(JSONObject args) throws Invalid {
    String id = StringUtils.trimToNull(McpArgumentUtils.optionalString(args,
        McpConstants.PARAM_ID));
    if (id == null) {
      throw new Invalid("'id' is required: the document to send.",
          "Take the id from etendo_list.");
    }
    boolean preview = previewOf(args);
    List<String> removed = new ArrayList<>();
    List<String> to = addresses(args, PARAM_TO, removed);
    List<String> cc = addresses(args, PARAM_CC, removed);
    if (to.isEmpty() && !preview) {
      throw new Invalid("'to' is required: at least one recipient address.", HINT_ADDRESSES);
    }
    List<String> shared = lowerCaseMatches(cc, to);
    cc.removeAll(shared);
    removed.addAll(shared);
    return new McpDocumentEmailRequest(id, Collections.unmodifiableList(to),
        Collections.unmodifiableList(cc), preview,
        new Texts(subjectOf(args), messageOf(args), languageOf(args)),
        Collections.unmodifiableList(removed));
  }

  /**
   * {@code preview} fails closed: absent means send, so a present value must be exactly a JSON
   * boolean. {@code JSONObject#optBoolean} reads "yes", 1 or null as {@code false}, which would turn
   * a model's wrong-typed preview request into a real, irreversible email.
   */
  private static boolean previewOf(JSONObject args) throws Invalid {
    if (!args.has(PARAM_PREVIEW)) {
      return false;
    }
    Object raw = args.opt(PARAM_PREVIEW);
    if (!(raw instanceof Boolean)) {
      throw new Invalid("'preview' must be the JSON boolean true or false.",
          "Pass preview:true to see what would be sent, or omit it to send. Nothing was sent.");
    }
    return (Boolean) raw;
  }

  /** The cc entries that are also in to: the application drops them from cc, "to" wins. */
  private static List<String> lowerCaseMatches(List<String> cc, List<String> to) {
    Map<String, String> toKeys = new LinkedHashMap<>();
    for (String address : to) {
      toKeys.put(address.toLowerCase(Locale.ROOT), address);
    }
    List<String> shared = new ArrayList<>();
    for (String address : cc) {
      if (toKeys.containsKey(address.toLowerCase(Locale.ROOT))) {
        shared.add(address);
      }
    }
    return shared;
  }

  private static String languageOf(JSONObject args) throws Invalid {
    if (!args.has(PARAM_LANGUAGE)) {
      return null;
    }
    Object raw = args.opt(PARAM_LANGUAGE);
    if (!(raw instanceof String) || !SUPPORTED_LANGUAGES.contains(raw)) {
      throw new Invalid("'language' is not a supported locale.", "Use one of "
          + String.join(", ", SUPPORTED_LANGUAGES) + ", or omit it for " + DEFAULT_LANGUAGE
          + ", what the application sends by default.");
    }
    return (String) raw;
  }

  private static List<String> addresses(JSONObject args, String key, List<String> removed)
      throws Invalid {
    if (!args.has(key) || args.isNull(key)) {
      return new ArrayList<>();
    }
    Object raw = args.opt(key);
    if (!(raw instanceof JSONArray)) {
      throw new Invalid("'" + key + "' must be a list of addresses.", HINT_ADDRESSES);
    }
    JSONArray array = (JSONArray) raw;
    Map<String, String> unique = new LinkedHashMap<>();
    for (int i = 0; i < array.length(); i++) {
      Object entry = array.opt(i);
      if (!(entry instanceof String)) {
        throw new Invalid("'" + key + "' must contain only text addresses.", HINT_ADDRESSES);
      }
      // Checked untrimmed: String#trim also strips control characters at the ends, which would
      // turn "a@b.example\u0000" or a trailing line break into a clean address.
      String problem = hasControlCharacter((String) entry, false)
          ? "it contains a control character or a line break."
          : addressProblem(((String) entry).trim());
      String address = ((String) entry).trim();
      if (problem != null) {
        throw new Invalid("'" + key + "' entry " + (i + 1) + " is not acceptable: " + problem,
            HINT_ADDRESSES);
      }
      if (unique.putIfAbsent(address.toLowerCase(Locale.ROOT), address) != null) {
        removed.add(address);
      }
    }
    return new ArrayList<>(unique.values());
  }

  /** What is wrong with one address, or {@code null}. The value itself is never quoted. */
  static String addressProblem(String address) {
    if (address.isEmpty()) {
      return "it is empty.";
    }
    if (address.length() > MAX_ADDRESS_LENGTH) {
      return "it is longer than " + maxLength(MAX_ADDRESS_LENGTH);
    }
    if (hasControlCharacter(address, false)) {
      return "it contains a control character or a line break.";
    }
    if (!EmailContractCommandSupport.isValidEmail(address)) {
      return "it is not a plain address of the form name@domain.tld.";
    }
    int at = address.lastIndexOf('@');
    String local = address.substring(0, at);
    String domain = address.substring(at + 1);
    if (local.length() > MAX_LOCAL_LENGTH || local.startsWith(".") || local.endsWith(".")
        || local.contains("..") || domain.contains("..")) {
      return "its local part or domain is malformed.";
    }
    for (String label : domain.split("\\.")) {
      if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) {
        return "its domain is malformed.";
      }
    }
    return null;
  }

  private static String maxLength(int max) {
    return max + " characters.";
  }

  private static String subjectOf(JSONObject args) throws Invalid {
    String raw = text(args, PARAM_SUBJECT);
    if (raw == null) {
      return null;
    }
    if (hasControlCharacter(raw, false)) {
      throw new Invalid("'subject' must be a single line: it contains a line break or a control "
          + "character.", "Remove the line breaks; put longer text in 'message'.");
    }
    String subject = StringUtils.trimToNull(raw);
    if (subject != null && subject.length() > EmailMessageEdits.MAX_SUBJECT_LENGTH) {
      throw new Invalid("'subject' is longer than " + maxLength(EmailMessageEdits.MAX_SUBJECT_LENGTH),
          "Shorten the subject.");
    }
    return subject;
  }

  private static String messageOf(JSONObject args) throws Invalid {
    String raw = text(args, PARAM_MESSAGE);
    if (raw == null) {
      return null;
    }
    if (hasControlCharacter(raw, true)) {
      throw new Invalid("'message' contains a control character. Line breaks and tabs are "
          + "allowed.", "Send plain text; **double asterisks** make a word bold.");
    }
    String message = StringUtils.trimToNull(raw);
    if (message != null && message.length() > EmailMessageEdits.MAX_MESSAGE_LENGTH) {
      throw new Invalid("'message' is longer than " + maxLength(EmailMessageEdits.MAX_MESSAGE_LENGTH),
          "Shorten the message.");
    }
    return message;
  }

  private static String text(JSONObject args, String key) throws Invalid {
    if (!args.has(key)) {
      return null;
    }
    Object raw = args.opt(key);
    if (!(raw instanceof String)) {
      throw new Invalid("'" + key + "' must be text.", "Pass a string.");
    }
    return (String) raw;
  }

  /** Control characters (C0 and C1), separators, bidi controls and DEL. */
  private static boolean hasControlCharacter(String value, boolean allowLayout) {
    for (int i = 0; i < value.length(); i++) {
      if (isForbidden(value.charAt(i), allowLayout)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isForbidden(char c, boolean allowLayout) {
    if (c == '\n' || c == '\r' || c == '\t') {
      return !allowLayout;
    }
    if (c < FIRST_PRINTABLE || c == DELETE) {
      return true;
    }
    return (c >= C1_FIRST && c <= C1_LAST) || INVISIBLE.indexOf(c) >= 0;
  }

  /**
   * Refuses a recipient list longer than the contract accepts.
   *
   * @param max the most recipients, to and cc together
   * @throws Invalid when to and cc together exceed it
   */
  void requireRecipientCap(int max) throws Invalid {
    int total = to.size() + cc.size();
    if (total > max) {
      throw new Invalid("Too many recipients: " + total + " (to and cc together); the most this "
          + "document type accepts is " + max + ".", "Send to fewer addresses, or send two "
          + "separate emails.");
    }
  }

  String getId() {
    return id;
  }

  List<String> getTo() {
    return to;
  }

  List<String> getCc() {
    return cc;
  }

  /** The subject the caller wrote, or {@code null} for the application's default. */
  String getSubject() {
    return texts.subject;
  }

  /** The message the caller wrote, or {@code null} for the application's default. */
  String getMessage() {
    return texts.message;
  }

  /** The language the caller asked for, or {@code null} for {@link #DEFAULT_LANGUAGE}. */
  String getLanguage() {
    return texts.language;
  }

  /**
   * Addresses dropped because they repeated another one, or because a cc address was already in
   * to. The application does the same silently (to wins); the answer reports it.
   */
  List<String> getRemovedDuplicates() {
    return removedDuplicates;
  }

  boolean isPreview() {
    return preview;
  }
}
