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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Plain unit tests for {@link NeoAttachmentPolicy} (ETP-5038). No Openbravo/DB dependency:
 * this class only depends on commons-lang3 and jettison.
 */
public class NeoAttachmentPolicyTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private static final byte[] PDF_HEADER = "%PDF-1.7\n%âãÏÓ\n"
      .getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] ZIP_HEADER = new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00 };
  private static final byte[] OLE2_HEADER = new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
      (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0x00, 0x00 };
  private static final byte[] PNG_HEADER = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A,
      0x0A };
  private static final byte[] UTF8_BOM = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
  private static final byte[] RTF_HEADER = "{\\rtf1\\ansi".getBytes(StandardCharsets.US_ASCII);

  // ── TC-01: legitimate content for every supported family validates ────────────

  @Test
  public void acceptsRealPdf() throws IOException {
    File file = writeFile("doc.pdf", PDF_HEADER);
    assertNull(NeoAttachmentPolicy.validateMetadata("doc.pdf", file.length()));
    assertNull(NeoAttachmentPolicy.validateContent("doc.pdf", file));
  }

  @Test
  public void acceptsZipFamilyAsXlsx() throws IOException {
    File file = writeFile("sheet.xlsx", ZIP_HEADER);
    assertNull(NeoAttachmentPolicy.validateMetadata("sheet.xlsx", file.length()));
    assertNull(NeoAttachmentPolicy.validateContent("sheet.xlsx", file));
  }

  @Test
  public void acceptsZipFamilyAsZip() throws IOException {
    File file = writeFile("bundle.zip", ZIP_HEADER);
    assertNull(NeoAttachmentPolicy.validateMetadata("bundle.zip", file.length()));
    assertNull(NeoAttachmentPolicy.validateContent("bundle.zip", file));
  }

  @Test
  public void acceptsOle2AsDoc() throws IOException {
    File file = writeFile("legacy.doc", OLE2_HEADER);
    assertNull(NeoAttachmentPolicy.validateMetadata("legacy.doc", file.length()));
    assertNull(NeoAttachmentPolicy.validateContent("legacy.doc", file));
  }

  @Test
  public void acceptsPng() throws IOException {
    File file = writeFile("image.png", PNG_HEADER);
    assertNull(NeoAttachmentPolicy.validateMetadata("image.png", file.length()));
    assertNull(NeoAttachmentPolicy.validateContent("image.png", file));
  }

  @Test
  public void acceptsXmlWithUtf8Bom() throws IOException {
    byte[] body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root/>".getBytes(StandardCharsets.UTF_8);
    byte[] withBom = concat(UTF8_BOM, body);
    File file = writeFile("invoice.xml", withBom);
    assertNull(NeoAttachmentPolicy.validateMetadata("invoice.xml", file.length()));
    assertNull(NeoAttachmentPolicy.validateContent("invoice.xml", file));
  }

  @Test
  public void acceptsRtf() throws IOException {
    File file = writeFile("note.rtf", RTF_HEADER);
    assertNull(NeoAttachmentPolicy.validateMetadata("note.rtf", file.length()));
    assertNull(NeoAttachmentPolicy.validateContent("note.rtf", file));
  }

  // ── TC-03: disallowed extension is rejected with a message naming it ──────────

  @Test
  public void rejectsTxtExtensionByName() {
    String message = NeoAttachmentPolicy.validateMetadata("notes.txt", 10);
    assertNotNull(message);
    assertTrue("Message should name the rejected extension: " + message, message.contains(".txt"));
  }

  // ── Magic-byte spoofing: declared type says pdf, bytes say plain text ─────────

  @Test
  public void rejectsSpoofedPdfWithPlainTextContent() throws IOException {
    File file = writeFile("notes.pdf", "just some plain text, not a pdf".getBytes(StandardCharsets.UTF_8));
    // Metadata alone (extension + size) cannot see the mismatch.
    assertNull(NeoAttachmentPolicy.validateMetadata("notes.pdf", file.length()));
    String message = NeoAttachmentPolicy.validateContent("notes.pdf", file);
    assertNotNull(message);
  }

  // ── Size limits ─────────────────────────────────────────────────────────────

  @Test
  public void rejectsOversizedDeclaredSize() {
    long tooLarge = 11L * 1024 * 1024;
    String message = NeoAttachmentPolicy.validateMetadata("d.pdf", tooLarge);
    assertNotNull(message);
  }

  @Test
  public void rejectsOversizedActualFile() throws IOException {
    File file = tempFolder.newFile("big.pdf");
    long tooLarge = 11L * 1024 * 1024;
    // Write sparsely via a stream instead of allocating an 11 MB array in memory.
    try (OutputStream out = new FileOutputStream(file)) {
      out.write(PDF_HEADER);
      byte[] chunk = new byte[64 * 1024];
      long written = PDF_HEADER.length;
      while (written < tooLarge) {
        long remaining = tooLarge - written;
        int toWrite = (int) Math.min(chunk.length, remaining);
        out.write(chunk, 0, toWrite);
        written += toWrite;
      }
    }
    assertTrue(file.length() > 10L * 1024 * 1024);
    String message = NeoAttachmentPolicy.validateContent("big.pdf", file);
    assertNotNull(message);
  }

  // ── No extension at all ────────────────────────────────────────────────────

  @Test
  public void rejectsFileNameWithNoExtension() {
    String message = NeoAttachmentPolicy.validateMetadata("attachment", 10);
    assertNotNull(message);
  }

  @Test
  public void extensionOfReturnsEmptyForNoExtension() {
    assertEquals("", NeoAttachmentPolicy.extensionOf("attachment"));
    assertEquals("", NeoAttachmentPolicy.extensionOf(null));
    assertEquals("", NeoAttachmentPolicy.extensionOf("trailing."));
  }

  // ── Published policy JSON ──────────────────────────────────────────────────

  @Test
  public void toJsonContainsExpectedShape() throws Exception {
    JSONObject json = NeoAttachmentPolicy.toJson();
    assertTrue(json.has("maxSizeMB"));
    assertTrue(json.has("allowedMimeTypes"));
    assertTrue(json.has("allowedExtensions"));
    assertTrue(json.has("typeGroups"));
    assertEquals(NeoAttachmentPolicy.MAX_SIZE_MB, json.getInt("maxSizeMB"));

    JSONArray mimeTypes = json.getJSONArray("allowedMimeTypes");
    boolean hasTextPlain = false;
    boolean hasZip = false;
    boolean hasXml = false;
    boolean hasRtf = false;
    for (int i = 0; i < mimeTypes.length(); i++) {
      String mime = mimeTypes.getString(i);
      if ("text/plain".equals(mime)) {
        hasTextPlain = true;
      }
      if ("application/zip".equals(mime)) {
        hasZip = true;
      }
      if ("application/xml".equals(mime)) {
        hasXml = true;
      }
      if ("application/rtf".equals(mime)) {
        hasRtf = true;
      }
    }
    assertFalse("text/plain must not be an allowed MIME type", hasTextPlain);
    assertTrue("application/zip must be allowed", hasZip);
    assertTrue("application/xml must be allowed", hasXml);
    assertTrue("application/rtf must be allowed", hasRtf);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private File writeFile(String name, byte[] content) throws IOException {
    File file = tempFolder.newFile(name);
    Files.write(file.toPath(), content);
    return file;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] result = new byte[a.length + b.length];
    System.arraycopy(a, 0, result, 0, a.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }
}
