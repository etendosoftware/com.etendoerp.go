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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Checks the agent identifications, party names and control totals of an XSD-valid pain.008.001.08.
 * The EPC130-08 (Core) 2025 Customer-to-PSP Implementation Guidelines
 * v1.0 specify these fields in sections 1.4–1.7, 2.4–2.5, 2.20, 2.48, 2.154 and 2.167.
 */
final class RemittanceSepaRules {

  static final String MSG_BIC_REQUIRED = "ETGO_RemittanceFileRuleBicRequired";
  static final String MSG_REQUIRED = "ETGO_RemittanceFileRuleRequired";
  static final String MSG_AGENT = "ETGO_RemittanceFileRuleAgent";
  static final String MSG_COUNT = "ETGO_RemittanceFileRuleCount";
  static final String MSG_TOTAL = "ETGO_RemittanceFileRuleTotal";
  static final String MSG_NAME = "ETGO_RemittanceFileRuleName";

  static final int NAME_MAX_LENGTH = 70;

  private static final String PAYMENT_INFORMATION = "PmtInf";
  private static final String TRANSACTION = "DrctDbtTxInf";

  private RemittanceSepaRules() {
  }

  /** A rule the file breaks: the message key and its placeholders (the element path first). */
  static final class Violation {
    final String key;
    final Object[] args;

    Violation(String key, Object... args) {
      this.key = key;
      this.args = args;
    }
  }

  /**
   * Checks a pain.008 collection file.
   *
   * @param xml the document, UTF-8, already XSD-valid
   * @return the first violation, or {@code null} when the file respects every rule here
   * @throws IOException when the document cannot be read
   */
  static Violation firstViolation(byte[] xml) throws IOException {
    Document document;
    try {
      document = RemittanceCreditorIdRewriter.parse(xml);
    } catch (SAXException | ParserConfigurationException | TransformerException e) {
      throw new IOException("Could not read the SEPA file: " + e.getMessage(), e);
    }
    return firstViolation(document, false);
  }

  /** Classic can override the default scheme and GO can select B2B for an individual remittance. */
  static boolean isCore(Document document) {
    NodeList instruments = document.getElementsByTagNameNS(
        document.getDocumentElement().getNamespaceURI(), "LclInstrm");
    if (instruments.getLength() == 0) {
      return false;
    }
    for (int i = 0; i < instruments.getLength(); i++) {
      Element code = child((Element) instruments.item(i), "Cd");
      if (code == null || !RemittanceSepaOptions.SCHEME_CORE.equals(code.getTextContent().trim())) {
        return false;
      }
    }
    return true;
  }

  static Violation firstViolation(Document document, boolean requireBic) {
    Element message = child(document.getDocumentElement(), "CstmrDrctDbtInitn");
    Element header = child(message, "GrpHdr");
    Violation violation = partyName(child(header, "InitgPty"), false);
    if (violation != null) {
      return violation;
    }
    int transactionCount = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (Element block : children(message, PAYMENT_INFORMATION)) {
      violation = partyAndAgent(block, "Cdtr", "CdtrAgt", requireBic);
      if (violation != null) {
        return violation;
      }
      List<Element> transactions = children(block, TRANSACTION);
      BigDecimal blockTotal = BigDecimal.ZERO;
      for (Element transaction : transactions) {
        violation = partyAndAgent(transaction, "Dbtr", "DbtrAgt", requireBic);
        if (violation != null) {
          return violation;
        }
        String amount = child(transaction, "InstdAmt").getTextContent().trim();
        blockTotal = blockTotal.add(new BigDecimal(amount));
      }
      violation = controls(block, transactions.size(), blockTotal);
      if (violation != null) {
        return violation;
      }
      transactionCount += transactions.size();
      total = total.add(blockTotal);
    }
    return controls(header, transactionCount, total);
  }

  private static Violation partyAndAgent(Element parent, String party, String agentName,
      boolean requireBic) {
    Element agent = child(parent, agentName);
    Element institution = child(agent, "FinInstnId");
    Element other = child(institution, "Othr");
    boolean hasBic = child(institution, "BICFI") != null;
    boolean hasOther = other != null;
    if (requireBic && !hasBic) {
      return new Violation(MSG_BIC_REQUIRED, path(institution));
    }
    if (hasBic == hasOther || (hasOther && !RemittanceCreditorIdRewriter.NOT_PROVIDED
        .equals(child(other, "Id").getTextContent()))) {
      return new Violation(MSG_AGENT, path(institution), RemittanceCreditorIdRewriter.NOT_PROVIDED);
    }
    return partyName(child(parent, party), true);
  }

  private static Violation partyName(Element party, boolean required) {
    Element name = child(party, "Nm");
    if (name == null) {
      return required ? missing(party, "Nm") : null;
    }
    String value = name.getTextContent();
    int length = value.codePointCount(0, value.length());
    return length > NAME_MAX_LENGTH
        ? new Violation(MSG_NAME, path(name), NAME_MAX_LENGTH, length) : null;
  }

  private static Violation controls(Element holder, int count, BigDecimal total) {
    Element number = child(holder, "NbOfTxs");
    if (number == null) {
      return missing(holder, "NbOfTxs");
    }
    // The ISO schema permits up to 15 digits, including leading zeroes.
    if (Long.parseLong(number.getTextContent()) != count) {
      return new Violation(MSG_COUNT, path(number), number.getTextContent(), count);
    }
    Element sum = child(holder, "CtrlSum");
    if (sum == null) {
      return missing(holder, "CtrlSum");
    }
    BigDecimal controlSum = new BigDecimal(sum.getTextContent().trim());
    if (controlSum.compareTo(total) != 0) {
      return new Violation(MSG_TOTAL, path(sum), sum.getTextContent(), total.toPlainString());
    }
    return null;
  }

  private static Violation missing(Element parent, String localName) {
    return new Violation(MSG_REQUIRED, path(parent) + "/" + localName);
  }

  private static List<Element> children(Element parent, String localName) {
    List<Element> found = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())
          && parent.getNamespaceURI().equals(node.getNamespaceURI())) {
        found.add((Element) node);
      }
    }
    return found;
  }

  private static Element child(Element parent, String localName) {
    return RemittanceCreditorIdRewriter.child(parent, localName);
  }

  /** {@code /Document/CstmrDrctDbtInitn/PmtInf[2]/DrctDbtTxInf[1]/DbtrAgt}-style path. */
  static String path(Node node) {
    StringBuilder path = new StringBuilder();
    for (Node n = node; n != null && n.getNodeType() == Node.ELEMENT_NODE;
        n = n.getParentNode()) {
      int index = 1;
      int same = 0;
      for (Node s = n.getParentNode() == null ? null : n.getParentNode().getFirstChild();
          s != null; s = s.getNextSibling()) {
        if (s.getNodeType() == Node.ELEMENT_NODE && n.getLocalName().equals(s.getLocalName())) {
          same++;
          if (s == n) {
            index = same;
          }
        }
      }
      path.insert(0, "/" + n.getLocalName() + (same > 1 ? "[" + index + "]" : ""));
    }
    return path.toString();
  }
}
