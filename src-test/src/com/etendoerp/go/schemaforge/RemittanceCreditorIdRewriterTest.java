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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * Unit tests for {@link RemittanceCreditorIdRewriter} (ETP-6247): the creditor identifier saved on
 * the account replaces the one Classic's generator computed, in the initiating party and in every
 * creditor scheme identification, and nowhere else.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceCreditorIdRewriter
 */
class RemittanceCreditorIdRewriterTest {

  private static final String NS = "urn:iso:std:iso:20022:tech:xsd:pain.008.001.08";
  private static final String CI = "ES82000B87654323";
  private static final String COMPUTED = "ES97000B12345678";
  private static final String DEBTOR = "ES11000B99999999";

  private static String document(int blocks) {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" "
        + "standalone=\"yes\"?><Document xmlns=\"" + NS + "\"><CstmrDrctDbtInitn><GrpHdr>"
        + "<InitgPty><Nm>Peña S.L.</Nm><Id><OrgId><Othr><Id>" + COMPUTED
        + "</Id></Othr></OrgId></Id></InitgPty></GrpHdr>");
    for (int i = 0; i < blocks; i++) {
      xml.append("<PmtInf><CdtrSchmeId><Id><PrvtId><Othr><Id>").append(COMPUTED)
          .append("</Id><SchmeNm><Prtry>SEPA</Prtry></SchmeNm></Othr></PrvtId></Id></CdtrSchmeId>"
              + "<DrctDbtTxInf><Dbtr><Id><PrvtId><Othr><Id>").append(DEBTOR)
          .append("</Id></Othr></PrvtId></Id></Dbtr></DrctDbtTxInf></PmtInf>");
    }
    return xml.append("</CstmrDrctDbtInitn></Document>").toString();
  }

  private static String apply(String xml) throws IOException {
    return new String(RemittanceCreditorIdRewriter.apply(xml.getBytes(StandardCharsets.UTF_8), CI),
        StandardCharsets.UTF_8);
  }

  @Test
  void theSavedIdReplacesTheInitiatingPartyAndEveryCreditorSchemeId() throws Exception {
    String out = apply(document(2));

    assertFalse(out.contains(COMPUTED), out);
    assertEquals(3, out.split(CI, -1).length - 1, "initiating party + two payment blocks: " + out);
  }

  @Test
  void theDebtorIdentifiersAreNeverTouched() throws Exception {
    String out = apply(document(2));

    assertEquals(2, out.split(DEBTOR, -1).length - 1, out);
  }

  /** The namespace, the other elements and the UTF-8 text survive the rewrite. */
  @Test
  void theRestOfTheDocumentSurvives() throws Exception {
    String out = apply(document(1));

    assertTrue(out.contains("xmlns=\"" + NS + "\""), out);
    assertTrue(out.contains("<Nm>Peña S.L.</Nm>"), out);
    assertTrue(out.contains("<Prtry>SEPA</Prtry>"), out);
    assertTrue(out.contains("<?xml version=\"1.0\" encoding=\"UTF-8\""), out);
  }

  /** Without Include_InitiatingPartyId there is no initiating party id: only the scheme ids change. */
  @Test
  void aFileWithoutAnInitiatingPartyIdIsRewrittenToo() throws Exception {
    String xml = document(1).replace("<Id><OrgId><Othr><Id>" + COMPUTED
        + "</Id></Othr></OrgId></Id>", "");

    String out = apply(xml);

    assertEquals(1, out.split(CI, -1).length - 1, out);
  }

  /** Never silently keep the computed identifier: no CdtrSchmeId to replace is an error. */
  @Test
  void aFileWithoutACreditorSchemeIdIsRefused() {
    assertThrows(IOException.class, () -> apply(document(0)));
  }

  /** A DOCTYPE is refused, as the schema validation refuses it. */
  @Test
  void aDoctypeIsRefused() {
    String xml = "<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + document(1);

    assertThrows(IOException.class, () -> apply(xml));
  }

  @Test
  void notXmlIsRefused() {
    assertThrows(IOException.class, () -> apply("not xml"));
  }

  @Test
  void ibanOnlyCleaningFillsBothAgentsAndOnlyTrimsOuterWhitespace() throws Exception {
    String xml = "<p:Document xmlns:p=\"" + NS + "\"><p:CdtrAgt><p:FinInstnId/>"
        + "</p:CdtrAgt><p:DbtrAgt><p:FinInstnId><p:BICFI>CAIXESBBXXX</p:BICFI>"
        + "</p:FinInstnId></p:DbtrAgt><p:StrtNm>  Calle  Mayor  </p:StrtNm></p:Document>";

    byte[] cleaned = RemittanceCreditorIdRewriter.tidy(xml.getBytes(StandardCharsets.UTF_8), true);
    Document document = RemittanceCreditorIdRewriter.parse(cleaned);

    assertEquals(2, document.getElementsByTagNameNS(NS, "Othr").getLength());
    assertEquals("NOTPROVIDED", document.getElementsByTagNameNS(NS, "Id").item(0).getTextContent());
    assertEquals(0, document.getElementsByTagNameNS(NS, "BICFI").getLength());
    assertEquals("Calle  Mayor", document.getElementsByTagNameNS(NS, "StrtNm").item(0).getTextContent());
  }

  // ---------------------------------------------------------------- options (ETP-6247)

  private static String withInstrument(String msgId) {
    return "<Document><CstmrDrctDbtInitn><GrpHdr><MsgId>" + msgId + "</MsgId></GrpHdr>"
        + "<PmtInf><PmtTpInf><LclInstrm><Cd>CORE</Cd></LclInstrm></PmtTpInf></PmtInf>"
        + "<PmtInf><PmtTpInf><LclInstrm><Cd>CORE</Cd></LclInstrm></PmtTpInf></PmtInf>"
        + "</CstmrDrctDbtInitn></Document>";
  }

  private static String options(String xml, String scheme, String prefix) throws IOException {
    return new String(RemittanceCreditorIdRewriter.applyOptions(
        xml.getBytes(StandardCharsets.UTF_8), scheme, prefix), StandardCharsets.UTF_8);
  }

  @Test
  void b2bReplacesTheInstrumentOfEveryPaymentBlock() throws Exception {
    String out = options(withInstrument("REM-1"), "B2B", null);

    assertEquals(2, out.split("<Cd>B2B</Cd>", -1).length - 1, out);
    assertFalse(out.contains("<Cd>CORE</Cd>"), out);
    assertTrue(out.contains("<MsgId>REM-1</MsgId>"), out);
  }

  @Test
  void financedPrefixesTheMessageIdOnceAndKeepsItWithin35() throws Exception {
    assertTrue(options(withInstrument("REM-1"), null, "FSDD").contains("<MsgId>FSDDREM-1</MsgId>"));
    assertTrue(options(withInstrument("FSDDREM-1"), null, "FSDD")
        .contains("<MsgId>FSDDREM-1</MsgId>"), "already prefixed: untouched");
    String longId = "X".repeat(35);
    assertTrue(options(withInstrument(longId), null, "FSDD")
        .contains("<MsgId>FSDD" + "X".repeat(31) + "</MsgId>"));
  }

  @Test
  void aFileWithoutWhatIsAskedToChangeIsRefused() {
    assertThrows(IOException.class, () -> options("<Document><PmtInf/></Document>", "B2B", null));
    assertThrows(IOException.class, () -> options("<Document><PmtInf/></Document>", null, "FSDD"));
  }
}
