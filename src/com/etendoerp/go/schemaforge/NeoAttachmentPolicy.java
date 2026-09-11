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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * THE single source of truth for what may be attached to a record: the accepted file
 * types and the maximum upload size (ETP-5038).
 *
 * <p>Both ends read this one list. The backend enforces it in
 * {@code NeoAttachmentsHelper.handleUpload}; the React Attachments tab fetches it from
 * {@code GET /sws/neo/attachments/config} and builds its {@code accept} attribute and
 * client-side check from the response. That mirrors the currency-format precedent
 * (ETP-4314, {@code NeoCurrencyFormatServlet} + {@code currencyFormatConfig.js}): a
 * policy the two ends must agree on lives on the server, never duplicated as two
 * hardcoded lists that drift until the UI says yes and the API says 400.</p>
 *
 * <h3>How a file is judged</h3>
 * <ol>
 *   <li><b>Size</b> — {@link #MAX_SIZE_MB}, enforced on the multipart part's declared size.</li>
 *   <li><b>Extension</b> — must be one of {@link #allowedExtensions()}.</li>
 *   <li><b>Magic bytes</b> — the file's leading bytes must match the signature the
 *       extension implies, so renaming {@code notes.txt} to {@code notes.pdf} (with a
 *       spoofed {@code Content-Type: application/pdf}) is still rejected.</li>
 * </ol>
 *
 * <p>The multipart part's own {@code Content-Type} is deliberately NOT the check: it is
 * chosen by the caller and therefore proves nothing. The MIME list this class publishes
 * exists for the browser's file picker, not for server-side authorization.</p>
 *
 * <p><b>Known limit — text-based formats.</b> XML and SVG have no binary signature; the
 * only structural check possible is "the first non-whitespace character is {@code '<'}".
 * A hand-crafted text file can satisfy that. Both are accepted as document formats
 * (Facturae XML is a real use case) and are stored, never rendered inline by the app, so
 * the residual risk is bounded by the download path, not by this check.</p>
 */
public final class NeoAttachmentPolicy {

  /**
   * Maximum accepted upload size, in megabytes. Published to the frontend as {@code maxSizeMB}.
   *
   * <p>Coupled to {@code NeoServlet}'s {@code @MultipartConfig(maxFileSize = 10 MB)}: the
   * container rejects anything larger before this class ever sees the part, so raising this
   * number alone would only move the failure to a less descriptive one. Raise both or neither.</p>
   */
  public static final int MAX_SIZE_MB = 10;

  private static final long MAX_SIZE_BYTES = MAX_SIZE_MB * 1024L * 1024L;

  /** How many leading bytes are read from the uploaded file to test its signature. */
  static final int SIGNATURE_PROBE_BYTES = 64;

  // Type groups — the coarse buckets the UI turns into a translated "Supported formats" label.
  private static final String GROUP_PDF = "pdf";
  private static final String GROUP_WORD = "word";
  private static final String GROUP_EXCEL = "excel";
  private static final String GROUP_POWERPOINT = "powerpoint";
  private static final String GROUP_IMAGE = "image";
  private static final String GROUP_XML = "xml";
  private static final String GROUP_ZIP = "zip";
  private static final String GROUP_RTF = "rtf";

  /**
   * One accepted file type: its extension, the UI bucket it belongs to, the MIME types a
   * browser may report for it, and the signature its content must carry.
   */
  private static final class AllowedType {
    private final String extension;
    private final String group;
    private final List<String> mimeTypes;
    private final Predicate<byte[]> signature;

    private AllowedType(String extension, String group, Predicate<byte[]> signature,
        String... mimeTypes) {
      this.extension = extension;
      this.group = group;
      this.signature = signature;
      this.mimeTypes = Collections.unmodifiableList(Arrays.asList(mimeTypes));
    }
  }

  // ── Signatures ──────────────────────────────────────────────────────────────

  private static final Predicate<byte[]> SIG_PDF = head -> startsWith(head, "%PDF");
  /** ZIP local file header — also every OOXML container (docx/xlsx/pptx). */
  private static final Predicate<byte[]> SIG_ZIP = head -> startsWith(head, new int[] { 0x50, 0x4B })
      && head.length > 3
      && (head[2] == 0x03 || head[2] == 0x05 || head[2] == 0x07);
  /** OLE2 compound document — the legacy .doc/.xls/.ppt container. */
  private static final Predicate<byte[]> SIG_OLE2 = head -> startsWith(head,
      new int[] { 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 });
  private static final Predicate<byte[]> SIG_RTF = head -> startsWith(head, "{\\rtf");
  private static final Predicate<byte[]> SIG_PNG = head -> startsWith(head,
      new int[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });
  private static final Predicate<byte[]> SIG_JPEG = head -> startsWith(head,
      new int[] { 0xFF, 0xD8, 0xFF });
  private static final Predicate<byte[]> SIG_GIF = head -> startsWith(head, "GIF87a")
      || startsWith(head, "GIF89a");
  private static final Predicate<byte[]> SIG_BMP = head -> startsWith(head, "BM");
  private static final Predicate<byte[]> SIG_WEBP = head -> startsWith(head, "RIFF")
      && matchesAt(head, 8, "WEBP");
  private static final Predicate<byte[]> SIG_TIFF = head -> startsWith(head,
      new int[] { 0x49, 0x49, 0x2A, 0x00 })
      || startsWith(head, new int[] { 0x4D, 0x4D, 0x00, 0x2A });
  /** ISO-BMFF box: any {@code ....ftyp} header covers HEIC/HEIF/AVIF stills. */
  private static final Predicate<byte[]> SIG_ISO_BMFF = head -> matchesAt(head, 4, "ftyp");
  /** Text markup (XML, SVG): first non-whitespace character must open a tag. See class javadoc. */
  private static final Predicate<byte[]> SIG_TEXT_MARKUP = NeoAttachmentPolicy::startsWithMarkup;

  /**
   * The allowlist. Order matters twice: it fixes the order of {@code allowedMimeTypes}
   * and, through first appearance, the order of {@code typeGroups} in the published JSON.
   *
   * <p>{@code text/plain} is deliberately absent (ETP-5038): the UI has always advertised
   * "PDF, Word, Excel, PowerPoint, images", and a plain {@code .txt} is not a document
   * anyone attaches on purpose here. ZIP / XML / RTF ARE kept — Facturae XML and zipped
   * document bundles are real, in-use cases.</p>
   */
  private static final List<AllowedType> ALLOWED_TYPES = Collections.unmodifiableList(Arrays.asList(
      new AllowedType("pdf", GROUP_PDF, SIG_PDF, "application/pdf"),
      new AllowedType("doc", GROUP_WORD, SIG_OLE2, "application/msword"),
      new AllowedType("docx", GROUP_WORD, SIG_ZIP,
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      new AllowedType("xls", GROUP_EXCEL, SIG_OLE2, "application/vnd.ms-excel"),
      new AllowedType("xlsx", GROUP_EXCEL, SIG_ZIP,
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      new AllowedType("ppt", GROUP_POWERPOINT, SIG_OLE2, "application/vnd.ms-powerpoint"),
      new AllowedType("pptx", GROUP_POWERPOINT, SIG_ZIP,
          "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
      new AllowedType("png", GROUP_IMAGE, SIG_PNG, "image/png"),
      new AllowedType("jpg", GROUP_IMAGE, SIG_JPEG, "image/jpeg"),
      new AllowedType("jpeg", GROUP_IMAGE, SIG_JPEG, "image/jpeg"),
      new AllowedType("gif", GROUP_IMAGE, SIG_GIF, "image/gif"),
      new AllowedType("bmp", GROUP_IMAGE, SIG_BMP, "image/bmp"),
      new AllowedType("webp", GROUP_IMAGE, SIG_WEBP, "image/webp"),
      new AllowedType("tif", GROUP_IMAGE, SIG_TIFF, "image/tiff"),
      new AllowedType("tiff", GROUP_IMAGE, SIG_TIFF, "image/tiff"),
      new AllowedType("heic", GROUP_IMAGE, SIG_ISO_BMFF, "image/heic"),
      new AllowedType("heif", GROUP_IMAGE, SIG_ISO_BMFF, "image/heif"),
      new AllowedType("svg", GROUP_IMAGE, SIG_TEXT_MARKUP, "image/svg+xml"),
      new AllowedType("xml", GROUP_XML, SIG_TEXT_MARKUP, "application/xml", "text/xml"),
      new AllowedType("zip", GROUP_ZIP, SIG_ZIP, "application/zip", "application/x-zip-compressed"),
      new AllowedType("rtf", GROUP_RTF, SIG_RTF, "application/rtf")));

  private NeoAttachmentPolicy() {
  }

  // ── Published policy ────────────────────────────────────────────────────────

  /**
   * The policy as served by {@code GET /sws/neo/attachments/config}:
   * {@code {maxSizeMB, allowedMimeTypes, allowedExtensions, typeGroups}}.
   *
   * @return the policy document
   * @throws JSONException if the document cannot be built
   */
  public static JSONObject toJson() throws JSONException {
    JSONObject json = new JSONObject();
    json.put("maxSizeMB", MAX_SIZE_MB);
    json.put("allowedMimeTypes", new JSONArray(allowedMimeTypes()));
    json.put("allowedExtensions", new JSONArray(allowedExtensions()));
    json.put("typeGroups", new JSONArray(typeGroups()));
    return json;
  }

  /** @return every MIME type a browser may report for an accepted file, de-duplicated, in list order. */
  public static List<String> allowedMimeTypes() {
    Set<String> mimes = new LinkedHashSet<>();
    for (AllowedType type : ALLOWED_TYPES) {
      mimes.addAll(type.mimeTypes);
    }
    return new ArrayList<>(mimes);
  }

  /** @return every accepted extension, lower-case and without the dot. */
  public static List<String> allowedExtensions() {
    List<String> extensions = new ArrayList<>();
    for (AllowedType type : ALLOWED_TYPES) {
      extensions.add(type.extension);
    }
    return extensions;
  }

  /** @return the UI buckets, in first-appearance order, that the frontend turns into a translated label. */
  public static List<String> typeGroups() {
    Set<String> groups = new LinkedHashSet<>();
    for (AllowedType type : ALLOWED_TYPES) {
      groups.add(type.group);
    }
    return new ArrayList<>(groups);
  }

  // ── Enforcement ─────────────────────────────────────────────────────────────

  /**
   * Checks everything that can be judged before the upload is written to disk: the
   * declared size and the file extension.
   *
   * @param fileName   the submitted file name
   * @param sizeBytes  the multipart part's declared size, or a negative value when unknown
   * @return {@code null} when acceptable, otherwise the rejection message for the 400 body
   */
  public static String validateMetadata(String fileName, long sizeBytes) {
    if (sizeBytes > MAX_SIZE_BYTES) {
      return "File exceeds the maximum allowed size of " + MAX_SIZE_MB + " MB";
    }
    String extension = extensionOf(fileName);
    if (findType(extension) == null) {
      return "File type not allowed"
          + (StringUtils.isBlank(extension) ? "" : ": ." + extension)
          + ". Allowed types: " + String.join(", ", allowedExtensions());
    }
    return null;
  }

  /**
   * Checks the materialized file's content against the signature its extension implies —
   * the part of the policy a spoofed {@code Content-Type} cannot get past.
   *
   * <p>Also re-checks the size against the bytes actually received: a multipart part is
   * allowed to declare {@code -1}, so {@link #validateMetadata} alone is not a size
   * guarantee.</p>
   *
   * @param fileName the submitted file name (drives which signature is expected)
   * @param file     the temp file holding the uploaded bytes
   * @return {@code null} when acceptable, otherwise the rejection message for the 400 body
   */
  public static String validateContent(String fileName, File file) {
    if (file == null || !file.isFile()) {
      return "Uploaded file could not be read";
    }
    if (file.length() > MAX_SIZE_BYTES) {
      return "File exceeds the maximum allowed size of " + MAX_SIZE_MB + " MB";
    }
    String extension = extensionOf(fileName);
    AllowedType type = findType(extension);
    if (type == null) {
      return "File type not allowed"
          + (StringUtils.isBlank(extension) ? "" : ": ." + extension)
          + ". Allowed types: " + String.join(", ", allowedExtensions());
    }
    byte[] head;
    try {
      head = readHead(file);
    } catch (IOException e) {
      return "Uploaded file could not be read";
    }
    if (!type.signature.test(head)) {
      return "File content does not match its ." + extension + " extension";
    }
    return null;
  }

  // ── Internals ───────────────────────────────────────────────────────────────

  private static AllowedType findType(String extension) {
    if (StringUtils.isBlank(extension)) {
      return null;
    }
    for (AllowedType type : ALLOWED_TYPES) {
      if (type.extension.equals(extension)) {
        return type;
      }
    }
    return null;
  }

  /** @return the lower-case extension without the dot, or an empty string when there is none. */
  static String extensionOf(String fileName) {
    if (StringUtils.isBlank(fileName)) {
      return "";
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return "";
    }
    return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  static byte[] readHead(File file) throws IOException {
    byte[] buffer = new byte[SIGNATURE_PROBE_BYTES];
    int read;
    try (InputStream in = Files.newInputStream(file.toPath())) {
      read = readFully(in, buffer);
    }
    return read == buffer.length ? buffer : Arrays.copyOf(buffer, Math.max(read, 0));
  }

  private static int readFully(InputStream in, byte[] buffer) throws IOException {
    int total = 0;
    while (total < buffer.length) {
      int read = in.read(buffer, total, buffer.length - total);
      if (read < 0) {
        break;
      }
      total += read;
    }
    return total;
  }

  private static boolean startsWith(byte[] head, String ascii) {
    return matchesAt(head, 0, ascii);
  }

  private static boolean matchesAt(byte[] head, int offset, String ascii) {
    byte[] expected = ascii.getBytes(StandardCharsets.US_ASCII);
    if (head == null || head.length < offset + expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if (head[offset + i] != expected[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean startsWith(byte[] head, int[] expected) {
    if (head == null || head.length < expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((head[i] & 0xFF) != expected[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * True when the first meaningful character is {@code '<'}. A UTF-8 BOM, leading
   * whitespace and the NUL padding a UTF-16 encoder emits are skipped first, since all
   * three are legal before an XML prolog.
   */
  private static boolean startsWithMarkup(byte[] head) {
    if (head == null) {
      return false;
    }
    int i = 0;
    if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB
        && (head[2] & 0xFF) == 0xBF) {
      i = 3;
    }
    for (; i < head.length; i++) {
      byte b = head[i];
      if (b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == 0) {
        continue;
      }
      return b == '<';
    }
    return false;
  }
}
