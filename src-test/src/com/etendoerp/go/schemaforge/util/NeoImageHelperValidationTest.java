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
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.util.NeoImageHelper.ImageValidationException;

/**
 * Unit tests for the byte-level image validation {@link NeoImageHelper} shares between the MCP
 * base64 tool and the ticketed upload endpoint (ETP-5184).
 *
 * <p>The MIME guard is the security-relevant part: a declared {@code mime_type} is never trusted, so
 * a caller cannot store an HTML page or a PDF in an image column by mislabelling it.
 */
class NeoImageHelperValidationTest {

  private static final int CAP = 256 * 1024;
  private static final String ADVICE = "Use neo_request_image_upload instead.";

  /** A real 2x3 PNG, produced by ImageIO so the magic bytes and the header are genuine. */
  private static byte[] realPng() throws Exception {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(2, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  /** A real 4x5 JPEG. */
  private static byte[] realJpeg() throws Exception {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(4, 5, java.awt.image.BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "jpeg", out);
    return out.toByteArray();
  }

  @Nested
  @DisplayName("sniffMimeType")
  class SniffMimeType {

    @Test
    @DisplayName("recognises a PNG and a JPEG from their magic bytes")
    void recognisesAllowedTypes() throws Exception {
      assertEquals(NeoImageHelper.MIME_PNG, NeoImageHelper.sniffMimeType(realPng()));
      assertEquals(NeoImageHelper.MIME_JPEG, NeoImageHelper.sniffMimeType(realJpeg()));
    }

    @Test
    @DisplayName("returns null for anything else")
    void rejectsEverythingElse() {
      assertNull(NeoImageHelper.sniffMimeType(null));
      assertNull(NeoImageHelper.sniffMimeType(new byte[0]));
      assertNull(NeoImageHelper.sniffMimeType("<html>hi</html>".getBytes(StandardCharsets.UTF_8)));
      assertNull(NeoImageHelper.sniffMimeType("%PDF-1.7".getBytes(StandardCharsets.UTF_8)));
      assertNull(NeoImageHelper.sniffMimeType(new byte[] { (byte) 0x89, 0x50 }),
          "a truncated PNG signature is not a PNG");
    }
  }

  @Nested
  @DisplayName("validateImageBytes")
  class ValidateImageBytes {

    @Test
    @DisplayName("accepts a PNG and returns the sniffed type")
    void acceptsPng() throws Exception {
      assertEquals(NeoImageHelper.MIME_PNG,
          NeoImageHelper.validateImageBytes(realPng(), null, CAP, ADVICE));
    }

    @Test
    @DisplayName("empty bytes are rejected first, before any other check")
    void emptyIsRejectedFirst() {
      ImageValidationException thrown = assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.validateImageBytes(new byte[0], "image/png", CAP, ADVICE));
      assertEquals("empty", thrown.getReason());
    }

    @Test
    @DisplayName("over the cap is rejected before the MIME sniff, and the message names the way out")
    void overCapIsRejectedBeforeSniffing() {
      // Not an image at all: if the sniff ran first, the reason would be unsupported_mime.
      byte[] tooBig = new byte[CAP + 1];
      ImageValidationException thrown = assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.validateImageBytes(tooBig, null, CAP, ADVICE));
      assertEquals("too_large", thrown.getReason());
      assertTrue(thrown.getMessage().contains(ADVICE),
          "the too-large error must be self-correctable: " + thrown.getMessage());
    }

    @Test
    @DisplayName("an HTML payload is rejected however it is labelled")
    void htmlIsRejected() {
      byte[] html = "<html><body>not an image</body></html>".getBytes(StandardCharsets.UTF_8);
      ImageValidationException thrown = assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.validateImageBytes(html, "image/png", CAP, ADVICE));
      assertEquals("unsupported_mime", thrown.getReason());
    }

    @Test
    @DisplayName("a PDF payload is rejected however it is labelled")
    void pdfIsRejected() {
      byte[] pdf = "%PDF-1.7\n%fake".getBytes(StandardCharsets.UTF_8);
      ImageValidationException thrown = assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.validateImageBytes(pdf, "image/jpeg", CAP, ADVICE));
      assertEquals("unsupported_mime", thrown.getReason());
    }

    @Test
    @DisplayName("a PNG declared as image/jpeg is rejected — the declared type is never trusted")
    void lyingMimeTypeIsRejected() throws Exception {
      ImageValidationException thrown = assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.validateImageBytes(realPng(), "image/jpeg", CAP, ADVICE));
      assertEquals("mime_mismatch", thrown.getReason());
      assertTrue(thrown.getMessage().contains("image/png"),
          "the error must say what the bytes really are: " + thrown.getMessage());
    }

    @Test
    @DisplayName("an omitted mime_type is sniffed instead of demanded")
    void omittedMimeTypeIsSniffed() throws Exception {
      assertEquals(NeoImageHelper.MIME_JPEG,
          NeoImageHelper.validateImageBytes(realJpeg(), null, CAP, ADVICE));
      assertEquals(NeoImageHelper.MIME_JPEG,
          NeoImageHelper.validateImageBytes(realJpeg(), "  ", CAP, ADVICE));
    }

    @Test
    @DisplayName("a correctly declared mime_type is accepted case-insensitively")
    void declaredTypeIsCaseInsensitive() throws Exception {
      assertEquals(NeoImageHelper.MIME_PNG,
          NeoImageHelper.validateImageBytes(realPng(), "IMAGE/PNG", CAP, ADVICE));
    }
  }

  @Nested
  @DisplayName("decodeBase64Image")
  class DecodeBase64Image {

    @Test
    @DisplayName("decodes a plain base64 payload")
    void decodesPlainBase64() throws Exception {
      byte[] png = realPng();
      String encoded = Base64.getEncoder().encodeToString(png);
      assertArrayEquals(png, NeoImageHelper.decodeBase64Image(encoded, CAP, ADVICE));
    }

    @Test
    @DisplayName("strips a data: URI prefix and still stores the correct bytes")
    void stripsDataUriPrefix() throws Exception {
      byte[] png = realPng();
      String encoded = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
      assertArrayEquals(png, NeoImageHelper.decodeBase64Image(encoded, CAP, ADVICE));
    }

    @Test
    @DisplayName("tolerates whitespace and newlines inside the payload")
    void toleratesWrappedBase64() throws Exception {
      byte[] png = realPng();
      String encoded = Base64.getEncoder().encodeToString(png);
      String wrapped = encoded.substring(0, 8) + "\n  " + encoded.substring(8);
      assertArrayEquals(png, NeoImageHelper.decodeBase64Image(wrapped, CAP, ADVICE));
    }

    @Test
    @DisplayName("a blank payload gets its own reason, distinct from malformed")
    void blankPayloadIsItsOwnError() {
      assertEquals("empty", assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.decodeBase64Image(null, CAP, ADVICE)).getReason());
      assertEquals("empty", assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.decodeBase64Image("   ", CAP, ADVICE)).getReason());
    }

    @Test
    @DisplayName("a malformed payload gets its own reason, distinct from blank")
    void malformedPayloadIsItsOwnError() {
      ImageValidationException thrown = assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.decodeBase64Image("!!!not base64!!!", CAP, ADVICE));
      assertEquals("malformed_base64", thrown.getReason());
    }

    @Test
    @DisplayName("an over-cap payload is refused from its encoded length, without being decoded")
    void overCapIsRefusedBeforeAllocating() {
      // 4/3 of the cap worth of base64 characters — valid base64, decodes to just over the cap.
      String oversized = "A".repeat((CAP + 1024) / 3 * 4);
      ImageValidationException thrown = assertThrows(ImageValidationException.class,
          () -> NeoImageHelper.decodeBase64Image(oversized, CAP, ADVICE));
      assertEquals("too_large", thrown.getReason());
      assertTrue(thrown.getMessage().contains(ADVICE),
          "over the cap the error must name the cheap path: " + thrown.getMessage());
    }
  }

  @Nested
  @DisplayName("readDimensions")
  class ReadDimensions {

    @Test
    @DisplayName("reports the real pixel dimensions")
    void readsRealDimensions() throws Exception {
      assertArrayEquals(new int[] { 2, 3 }, NeoImageHelper.readDimensions(realPng()));
      assertArrayEquals(new int[] { 4, 5 }, NeoImageHelper.readDimensions(realJpeg()));
    }

    @Test
    @DisplayName("omits dimensions rather than guessing when the bytes are not an image")
    void undecodableBytesYieldNull() {
      assertNull(NeoImageHelper.readDimensions(null));
      assertNull(NeoImageHelper.readDimensions(new byte[0]));
      assertNull(NeoImageHelper.readDimensions("not an image".getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  @DisplayName("the allowlist is exactly PNG and JPEG")
  void allowlistIsPngAndJpeg() {
    assertEquals(java.util.List.of("image/png", "image/jpeg"), NeoImageHelper.ALLOWED_MIME_TYPES);
    assertNotNull(NeoImageHelper.INVALID_UPLOAD_LINK_MESSAGE);
    assertTrue(NeoImageHelper.INVALID_UPLOAD_LINK_MESSAGE.contains("neo_request_image_upload"),
        "the 403 must tell the caller how to get a working link");
  }
}
