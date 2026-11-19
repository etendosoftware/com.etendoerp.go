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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * ETP-6247 — puts the creditor identifier SAVED on the financial account into a pain.008 file.
 *
 * <p><b>Why a rewrite.</b> Classic's {@code CoreDirectDebit} always computes the identifier it
 * writes, with {@code SEPAUtil.getAT02Identification(organization country, business code, organization
 * tax ID)}, and has no hook, parameter or override to give it another one. The modules that own
 * the generator are not ours to change, so the generator runs untouched and this class replaces
 * the value in the two places the generator writes it, by structure and not by value:
 * <ul>
 *   <li>{@code GrpHdr/InitgPty/Id/OrgId/Othr/Id} — the initiating party;</li>
 *   <li>{@code PmtInf/CdtrSchmeId/Id/PrvtId/Othr/Id} — the creditor scheme identification, once per
 *       payment information block.</li>
 * </ul>
 * Debtor identifiers ({@code Dbtr}, {@code MndtRltdInf}) are never touched. The result is
 * validated against the XSD afterwards by {@link RemittanceFileGenerator}, like the original.
 *
 * <p>The XML is read with {@link RemittanceSepaSchema#secureReader} (no DOCTYPE, no external
 * entity) and written back as UTF-8.
 */
final class RemittanceCreditorIdRewriter {

  static final String CREDITOR_SCHEME_ID = "CdtrSchmeId";
  static final String INITIATING_PARTY = "InitgPty";
  private static final String OTHER = "Othr";
  private static final String ID = "Id";

  private RemittanceCreditorIdRewriter() {
  }

  /**
   * Replaces the creditor identifier of a pain.008 document.
   *
   * @param xml        the document the generator wrote, UTF-8
   * @param creditorId the valid, normalized identifier saved on the account
   * @return the document with {@code creditorId} as the initiating party's and every creditor
   *         scheme identification, UTF-8
   * @throws IOException when the document cannot be read or written, or has no creditor scheme
   *                     identification to replace (a file the generator wrote without one would
   *                     silently keep a computed identifier)
   */
  static byte[] apply(byte[] xml, String creditorId) throws IOException {
    try {
      Document document = parse(xml);
      int replaced = replace(document, CREDITOR_SCHEME_ID, creditorId);
      if (replaced == 0) {
        throw new IOException("The SEPA file has no " + CREDITOR_SCHEME_ID
            + ": the creditor identifier cannot be applied");
      }
      replace(document, INITIATING_PARTY, creditorId);
      return write(document);
    } catch (SAXException | ParserConfigurationException | TransformerException e) {
      throw new IOException("Could not apply the creditor identifier: " + e.getMessage(), e);
    }
  }

  /** pain.008: the creditor's (the company's) account of a payment information block. */
  static final String COLLECTION_ACCOUNT = "CdtrAcct";
  /** pain.001: the debtor's (the company's) account of a payment information block. */
  static final String PAYMENT_ACCOUNT = "DbtrAcct";
  private static final String PAYMENT_INFORMATION = "PmtInf";
  private static final String IBAN = "IBAN";

  /**
   * Replaces the IBAN of the COMPANY's account in a pain.008 / pain.001 document (ETP-6247): the
   * remittance's own IBAN, not the account's. By structure: the account element named
   * {@code accountElement} that is a direct child of {@code PmtInf} — {@value #COLLECTION_ACCOUNT}
   * in a collection, {@value #PAYMENT_ACCOUNT} in a payment. The accounts of the lines
   * ({@code DbtrAcct} under {@code DrctDbtTxInf}, {@code CdtrAcct} under {@code CdtTrfTxInf}) are
   * children of the transaction, not of {@code PmtInf}, and are never touched.
   *
   * @param xml            the document the generator wrote, UTF-8
   * @param accountElement {@link #COLLECTION_ACCOUNT} or {@link #PAYMENT_ACCOUNT}
   * @param iban           the valid, normalized IBAN of the remittance
   * @return the document with the IBAN replaced, UTF-8
   * @throws IOException when it cannot be read or written, or has no such account to replace
   */
  static byte[] applyIban(byte[] xml, String accountElement, String iban) throws IOException {
    try {
      Document document = parse(xml);
      int replaced = 0;
      NodeList accounts = document.getElementsByTagNameNS("*", accountElement);
      for (int i = 0; i < accounts.getLength(); i++) {
        Node account = accounts.item(i);
        Node parent = account.getParentNode();
        if (parent == null || !PAYMENT_INFORMATION.equals(localNameOf(parent))) {
          continue;
        }
        NodeList ibans = ((Element) account).getElementsByTagNameNS("*", IBAN);
        for (int j = 0; j < ibans.getLength(); j++) {
          ibans.item(j).setTextContent(iban);
          replaced++;
        }
      }
      if (replaced == 0) {
        throw new IOException("The SEPA file has no " + accountElement
            + " IBAN under " + PAYMENT_INFORMATION + ": the IBAN cannot be applied");
      }
      return write(document);
    } catch (SAXException | ParserConfigurationException | TransformerException e) {
      throw new IOException("Could not apply the IBAN: " + e.getMessage(), e);
    }
  }

  /**
   * ETP-6247 — applies the per-remittance direct debit options to a pain.008 document, by
   * structure: {@code LclInstrm/Cd} of every payment information block becomes {@code scheme}
   * (CORE or B2B) and, for a financed remittance, {@code GrpHdr/MsgId} starts with {@code
   * msgIdPrefix} (cut to {@value RemittanceSepaOptions#MSG_ID_MAX_LENGTH} characters, left as is
   * when it already starts with it).
   *
   * @param xml         the document the generator wrote, UTF-8
   * @param scheme      the scheme to write, or {@code null} to leave it
   * @param msgIdPrefix the prefix of a financed remittance, or {@code null} to leave the id
   * @return the document, UTF-8
   * @throws IOException when it cannot be read or written, or has no instrument or message
   *                     identifier to change when one was asked
   */
  static byte[] applyOptions(byte[] xml, String scheme, String msgIdPrefix) throws IOException {
    try {
      Document document = parse(xml);
      if (scheme != null) {
        int changed = 0;
        NodeList instruments = document.getElementsByTagNameNS("*", "LclInstrm");
        for (int i = 0; i < instruments.getLength(); i++) {
          Element code = child(instruments.item(i), "Cd");
          if (code != null) {
            code.setTextContent(scheme);
            changed++;
          }
        }
        if (changed == 0) {
          throw new IOException("The SEPA file has no LclInstrm/Cd: the scheme cannot be applied");
        }
      }
      if (msgIdPrefix != null) {
        NodeList ids = document.getElementsByTagNameNS("*", "MsgId");
        if (ids.getLength() == 0) {
          throw new IOException("The SEPA file has no MsgId: the financed prefix cannot be applied");
        }
        Node id = ids.item(0);
        String current = id.getTextContent() == null ? "" : id.getTextContent();
        if (!current.startsWith(msgIdPrefix)) {
          String joined = msgIdPrefix + current;
          id.setTextContent(joined.length() > RemittanceSepaOptions.MSG_ID_MAX_LENGTH
              ? joined.substring(0, RemittanceSepaOptions.MSG_ID_MAX_LENGTH) : joined);
        }
      }
      return write(document);
    } catch (SAXException | ParserConfigurationException | TransformerException e) {
      throw new IOException("Could not apply the direct debit options: " + e.getMessage(), e);
    }
  }

  /** Agent elements whose empty institution identification the EPC does not accept. */
  private static final String[] AGENTS = {"CdtrAgt", "DbtrAgt"};
  static final String NOT_PROVIDED = "NOTPROVIDED";
  // BICFIDec2014Identifier in pain.008.001.08.xsd. Keep malformed input for schema validation.
  private static final Pattern BICFI_FORMAT = Pattern.compile("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");

  /**
   * ETP-6247 — cleans up what Classic's generator writes in a file about to be validated, by
   * structure and for every element:
   * <ul>
   *   <li>the text of every leaf element is trimmed (the generator copies the address data as it
   *       is stored, e.g. {@code <StrtNm> Calle X</StrtNm>}); nothing else is collapsed;</li>
   *   <li>the IBAN-only profile identifies each bank with {@code Othr/Id = NOTPROVIDED} even
   *       when Classic copied a directory BIC into the file. The bank's explicit request is a
   *       condition for sending a BIC (EPC130-08 sections 2.49 and 2.155). Malformed or conflicting
   *       identifications are left for validation.</li>
   * </ul>
   *
   * @param xml        the document the generator wrote, UTF-8
   * @param ibanOnly whether the collection type is configured without a BIC requirement
   * @return the document, UTF-8
   * @throws IOException when it cannot be read or written
   */
  static byte[] tidy(byte[] xml, boolean ibanOnly) throws IOException {
    try {
      return tidy(parse(xml), ibanOnly);
    } catch (SAXException | ParserConfigurationException | TransformerException e) {
      throw new IOException("Could not tidy the SEPA file: " + e.getMessage(), e);
    }
  }


  static byte[] tidy(Document document, boolean ibanOnly) throws TransformerException {
    trimLeaves(document.getDocumentElement());
    if (ibanOnly) {
      for (String agent : AGENTS) {
        NodeList agents = document.getElementsByTagNameNS(
            document.getDocumentElement().getNamespaceURI(), agent);
        for (int i = 0; i < agents.getLength(); i++) {
          useIbanOnlyIdentification(document, child(agents.item(i), "FinInstnId"));
        }
      }
    }
    return write(document);
  }

  private static void trimLeaves(Node node) {
    if (!hasElementChild(node)) {
      String text = node.getTextContent();
      if (text != null && !text.equals(text.trim())) {
        node.setTextContent(text.trim());
      }
      return;
    }
    for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        trimLeaves(n);
      }
    }
  }

  private static boolean hasElementChild(Node node) {
    for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        return true;
      }
    }
    return false;
  }

  private static void useIbanOnlyIdentification(Document document, Element institution) {
    if (institution == null) {
      return;
    }
    Element bic = child(institution, "BICFI");
    if (bic != null) {
      if (child(institution, OTHER) != null || hasElementChild(bic)
          || !BICFI_FORMAT.matcher(bic.getTextContent()).matches()) {
        return;
      }
      institution.removeChild(bic);
    } else if (hasElementChild(institution)) {
      return;
    }
    Element other = qualified(document, institution, OTHER);
    Element id = qualified(document, institution, ID);
    id.setTextContent(NOT_PROVIDED);
    other.appendChild(id);
    institution.appendChild(other);
  }

  private static Element qualified(Document document, Element like, String localName) {
    String prefix = like.getPrefix();
    return document.createElementNS(like.getNamespaceURI(),
        prefix == null ? localName : prefix + ":" + localName);
  }

  static Document parse(byte[] xml)
      throws SAXException, ParserConfigurationException, TransformerException {
    DOMResult result = new DOMResult();
    secureFactory().newTransformer().transform(
        new SAXSource(RemittanceSepaSchema.secureReader(),
            new InputSource(new ByteArrayInputStream(xml))),
        result);
    return (Document) result.getNode();
  }

  private static byte[] write(Document document) throws TransformerException {
    Transformer transformer = secureFactory().newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
    transformer.setOutputProperty(OutputKeys.INDENT, "no");
    document.setXmlStandalone(true);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transformer.transform(new DOMSource(document), new StreamResult(out));
    return out.toByteArray();
  }

  private static TransformerFactory secureFactory() {
    TransformerFactory factory = TransformerFactory.newInstance();
    try {
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    } catch (TransformerConfigurationException e) {
      // A factory that does not know the feature keeps the identity transform, which loads nothing.
    }
    return factory;
  }

  /**
   * Sets {@code Othr/Id} inside every element named {@code container}.
   *
   * @return how many identifiers were replaced
   */
  private static int replace(Document document, String container, String creditorId) {
    int replaced = 0;
    NodeList containers = document.getElementsByTagNameNS("*", container);
    for (int i = 0; i < containers.getLength(); i++) {
      Element element = (Element) containers.item(i);
      NodeList others = element.getElementsByTagNameNS("*", OTHER);
      for (int j = 0; j < others.getLength(); j++) {
        Element id = child(others.item(j), ID);
        if (id != null) {
          id.setTextContent(creditorId);
          replaced++;
        }
      }
    }
    return replaced;
  }

  static Element child(Node parent, String localName) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(localNameOf(node))
          && Objects.equals(parent.getNamespaceURI(), node.getNamespaceURI())) {
        return (Element) node;
      }
    }
    return null;
  }

  private static String localNameOf(Node node) {
    return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
  }
}
