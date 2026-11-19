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
import java.util.ArrayList;
import java.util.List;
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
   * msgIdPrefix} ({@link #prefixedMsgId}: the original is cut from its start so the result fits
   * {@value RemittanceSepaOptions#MSG_ID_MAX_LENGTH} characters and keeps its unique end; left as
   * is when it already starts with the prefix).
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
          id.setTextContent(prefixedMsgId(msgIdPrefix, current));
        }
      }
      return write(document);
    } catch (SAXException | ParserConfigurationException | TransformerException e) {
      throw new IOException("Could not apply the direct debit options: " + e.getMessage(), e);
    }
  }

  /**
   * {@code prefix + original}, at most {@value RemittanceSepaOptions#MSG_ID_MAX_LENGTH}
   * characters. When it does not fit, the ORIGINAL is cut, keeping its end: the generator's id ends
   * in what makes it unique (the timestamp), which cutting the joined value at its end would drop.
   *
   * @param prefix   the financed prefix, shorter than the maximum
   * @param original the identifier the generator wrote
   * @return the prefixed identifier
   */
  static String prefixedMsgId(String prefix, String original) {
    int room = Math.max(0, RemittanceSepaOptions.MSG_ID_MAX_LENGTH - prefix.length());
    String kept = original.length() > room ? original.substring(original.length() - room)
        : original;
    return prefix + kept;
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
   *   <li>the debtor identification ({@code Dbtr/Id}) is dropped (see
   *       {@link #removeDebtorIdentifications}) — CORE only, a B2B file keeps it
   *       ({@link #tidy(Document, boolean, boolean)});</li>
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
      return tidy(parse(xml), ibanOnly, true);
    } catch (SAXException | ParserConfigurationException | TransformerException e) {
      throw new IOException("Could not tidy the SEPA file: " + e.getMessage(), e);
    }
  }

  /**
   * {@link #tidy(byte[], boolean)} on a parsed document (modified in place), for either scheme:
   * a B2B collection gets the same trim and agent identification as a CORE one and keeps its
   * debtor identification (ETP-6247: the B2B debtor is an organisation its bank identifies).
   *
   * @param document            the parsed pain.008 document
   * @param ibanOnly            whether the collection type is configured without a BIC requirement
   * @param dropDebtorIds       whether {@code Dbtr/Id} is dropped: CORE only
   * @return the document, UTF-8
   * @throws TransformerException when it cannot be written
   */
  static byte[] tidy(Document document, boolean ibanOnly, boolean dropDebtorIds)
      throws TransformerException {
    trimLeaves(document.getDocumentElement());
    if (dropDebtorIds) {
      removeDebtorIdentifications(document);
    }
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

  /**
   * ETP-6247 — cleans up a pain.001 (SEPA credit transfer) file the generator wrote, by structure
   * and for every element, before it is validated. EPC132-08 2025 v1.0 (SCT Customer-to-PSP IG):
   * <ul>
   *   <li>the text of every leaf element is trimmed ({@code <StrtNm> Calle X</StrtNm>}); nothing
   *       else is collapsed;</li>
   *   <li>{@code DbtrAgt} is 1..1 and takes either {@code BICFI} or {@code Othr/Id = NOTPROVIDED}
   *       (2.53, 2.54, 2.61): the generator writes an empty {@code FinInstnId} when the account has
   *       no BIC. As in a collection, a BIC is sent only when the remittance type requires it
   *       ({@code ibanOnly} false); otherwise the agent is identified with {@code NOTPROVIDED}
   *       even when the account has a BIC;</li>
   *   <li>{@code CdtrAgt} is 0..1 and takes only {@code BICFI} (2.114: «If the BIC is not
   *       indicated 'Creditor Agent' structure is not to be used»): a creditor agent with no
   *       identification is removed, never {@code NOTPROVIDED}; a valid BIC stays.</li>
   * </ul>
   *
   * @param document the parsed pain.001.001.09 document (modified in place)
   * @param ibanOnly whether the remittance type has no BIC requirement
   * @return the document, UTF-8
   * @throws TransformerException when it cannot be written
   */
  static byte[] tidyPayment(Document document, boolean ibanOnly) throws TransformerException {
    trimLeaves(document.getDocumentElement());
    String namespace = document.getDocumentElement().getNamespaceURI();
    if (ibanOnly) {
      NodeList debtorAgents = document.getElementsByTagNameNS(namespace, "DbtrAgt");
      for (int i = 0; i < debtorAgents.getLength(); i++) {
        useIbanOnlyIdentification(document, child(debtorAgents.item(i), "FinInstnId"));
      }
    }
    NodeList creditorAgents = document.getElementsByTagNameNS(namespace, "CdtrAgt");
    List<Node> empty = new ArrayList<>();
    for (int i = 0; i < creditorAgents.getLength(); i++) {
      Element institution = child(creditorAgents.item(i), "FinInstnId");
      if (institution == null || !hasElementChild(institution)) {
        empty.add(creditorAgents.item(i));
      }
    }
    for (Node agent : empty) {
      agent.getParentNode().removeChild(agent);
    }
    return write(document);
  }

  /**
   * Drops {@code Dbtr/Id} of every {@code DrctDbtTxInf}. Classic's generator always writes it, as
   * an AT-02-style code built from the debtor's tax ID (country, check digits, {@code ZZZ}, tax
   * ID): the code of a creditor invented for a debtor, and always as an organisation. The debtor
   * identification (AT-P004, EPC130-08 2.185) is optional; {@code Nm} and {@code PstlAdr} stay.
   * {@code InitgPty/Id} and {@code CdtrSchmeId} are not under a transaction and are not touched.
   */
  private static void removeDebtorIdentifications(Document document) {
    NodeList transactions = document.getElementsByTagNameNS("*", "DrctDbtTxInf");
    for (int i = 0; i < transactions.getLength(); i++) {
      Element debtor = child(transactions.item(i), "Dbtr");
      Element id = debtor == null ? null : child(debtor, ID);
      if (id != null) {
        debtor.removeChild(id);
      }
    }
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
    if (parent == null) {
      return null;
    }
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
