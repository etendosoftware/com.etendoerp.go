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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.sun.net.httpserver.HttpServer;

/**
 * Unit tests for {@link RemittanceSepaSchema}, the XSD validation of a remittance's SEPA file:
 * <ul>
 *   <li>{@code locate}: the XSD next to the generator class first, else the copy GO ships in
 *       {@code schemaforge/sepa/} (pain.008.001.08 and pain.001.001.09 only), else {@code null};</li>
 *   <li>{@code failure}: {@code null} for a valid document, the parser's message otherwise —
 *       against a small schema written here and against GO's bundled pain.008.001.08;</li>
 *   <li>no external access, on the Xerces 2.9 the class path carries (its JAXP factories do not
 *       know the JAXP 1.5 properties, which used to make every file invalid): a document with a
 *       DOCTYPE is refused and its entities are never read (XXE), an {@code xsi} schema location
 *       hint is never fetched and a schema that includes another one is never completed from the
 *       network. A local HTTP server counts what would have been fetched.</li>
 * </ul>
 *
 * <p>A class of its own: {@code RemittanceSepaSchema} is a new production class; its cases moved
 * here from {@code RemittanceFileGeneratorTest}, which keeps the generator's use of it.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaSchema
 */
class RemittanceSepaSchemaTest {

  private static final String PAIN_008 = "pain.008.001.08";
  private static final String PAIN_001 = "pain.001.001.09";

  @TempDir
  Path tempDir;

  private HttpServer server;
  private final AtomicInteger fetches = new AtomicInteger();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      fetches.incrementAndGet();
      byte[] body = "ABC".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  // ---------------------------------------------------------------- locate

  @Test
  void theXsdNextToTheGeneratorWins() throws Exception {
    Class<?> classic = Class.forName(RemittanceSepaFileSetup.CLASS_CORE);

    URL xsd = RemittanceSepaSchema.locate(classic, PAIN_008);

    assertNotNull(xsd);
    assertEquals(classic.getResource(PAIN_008 + ".xsd"), xsd);
  }

  @Test
  void withoutAnXsdOfItsOwnGosBundledCopyIsUsed() {
    for (String version : new String[] { PAIN_008, PAIN_001 }) {
      URL xsd = RemittanceSepaSchema.locate(Object.class, version);

      assertNotNull(xsd, version);
      assertTrue(xsd.toString().endsWith("com/etendoerp/go/schemaforge/sepa/" + version + ".xsd"),
          xsd.toString());
      assertEquals(xsd, RemittanceSepaSchema.locate(null, version), "no generator class");
    }
  }

  @Test
  void aVersionNobodyHasOrNoVersionLocatesNothing() {
    assertNull(RemittanceSepaSchema.locate(Object.class, "pain.008.001.02"));
    assertNull(RemittanceSepaSchema.locate(Object.class, "no.such.schema"));
    assertNull(RemittanceSepaSchema.locate(Object.class, " "));
    assertNull(RemittanceSepaSchema.locate(Object.class, null));
  }

  // ---------------------------------------------------------------- failure

  @Test
  void aValidDocumentHasNoFailureAndAnInvalidOneTheParsersMessage() throws Exception {
    URL xsd = writeSchema("");

    assertNull(RemittanceSepaSchema.failure(xsd, utf8("<doc><code>ABC</code></doc>")));
    String failure = RemittanceSepaSchema.failure(xsd, utf8("<doc><code>TOO-LONG</code></doc>"));
    assertNotNull(failure);
    assertTrue(failure.contains("cvc-"), failure);
    assertNotNull(RemittanceSepaSchema.failure(xsd, utf8("not xml")));
  }

  @Test
  void theBundledPain008RejectsAnInitiationWithoutGroupHeader() {
    String failure = RemittanceSepaSchema.failure(
        RemittanceSepaSchema.locate(null, PAIN_008),
        utf8("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.008.001.08\">"
            + "<CstmrDrctDbtInitn/></Document>"));

    assertNotNull(failure);
    assertTrue(failure.contains("GrpHdr"), failure);
  }

  // ---------------------------------------------------------------- no external access

  /** XXE: the entity would make the document valid («ABC»), so only a refusal fails it. */
  @Test
  void aDocumentWithADoctypeIsRefusedAndItsEntityNeverRead() throws Exception {
    URL xsd = writeSchema("");

    String failure = RemittanceSepaSchema.failure(xsd, utf8(
        "<!DOCTYPE doc [<!ENTITY x SYSTEM \"" + url("secret") + "\">]>"
            + "<doc><code>&x;</code></doc>"));

    assertNotNull(failure);
    assertTrue(failure.contains("DOCTYPE"), failure);
    assertEquals(0, fetches.get(), "the entity was fetched");
  }

  @Test
  void anExternalDtdIsRefusedAndNeverRead() throws Exception {
    URL xsd = writeSchema("");

    String failure = RemittanceSepaSchema.failure(xsd, utf8(
        "<!DOCTYPE doc SYSTEM \"" + url("doc.dtd") + "\"><doc><code>ABC</code></doc>"));

    assertNotNull(failure);
    assertEquals(0, fetches.get(), "the DTD was fetched");
  }

  @Test
  void aSchemaLocationHintIsNeverFetched() throws Exception {
    URL xsd = writeSchema("");

    String failure = RemittanceSepaSchema.failure(xsd, utf8(
        "<doc xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:noNamespaceSchemaLocation=\"" + url("hint.xsd") + "\">"
            + "<code>ABC</code></doc>"));

    assertNull(failure, "the given schema decides");
    assertEquals(0, fetches.get(), "the hint was fetched");
  }

  @Test
  void aSchemaIsNeverCompletedFromTheNetwork() throws Exception {
    URL xsd = writeSchema("<xs:include schemaLocation=\"" + url("other.xsd") + "\"/>");

    assertNotNull(RemittanceSepaSchema.failure(xsd, utf8("<doc><code>ABC</code></doc>")));
    assertEquals(0, fetches.get(), "the included schema was fetched");
  }

  @Test
  void theSecureReaderRefusesADoctypeAndResolvesNoEntity() throws Exception {
    XMLReader reader = RemittanceSepaSchema.secureReader();

    assertTrue(reader.getFeature("http://xml.org/sax/features/namespaces"));
    assertTrue(reader.getFeature(RemittanceSepaSchema.FEATURE_DISALLOW_DOCTYPE));
    assertThrows(SAXException.class,
        () -> reader.getEntityResolver().resolveEntity(null, url("secret")));
    assertEquals(0, fetches.get());
  }

  // ---------------------------------------------------------------- helpers

  /** {@code <doc><code/></doc>} with a code of at most 3 characters, plus {@code extra}. */
  private URL writeSchema(String extra) throws IOException {
    Path xsd = tempDir.resolve("doc.xsd");
    Files.write(xsd, utf8("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">" + extra
        + "<xs:element name=\"doc\"><xs:complexType><xs:sequence>"
        + "<xs:element name=\"code\"><xs:simpleType><xs:restriction base=\"xs:string\">"
        + "<xs:maxLength value=\"3\"/></xs:restriction></xs:simpleType></xs:element>"
        + "</xs:sequence></xs:complexType></xs:element></xs:schema>"));
    return xsd.toUri().toURL();
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + path;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
