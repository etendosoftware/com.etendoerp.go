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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract;
import com.etendoerp.go.schemaforge.email.DefaultEmailContractRegistry;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractRegistry;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * Which documents the MCP can send by email, derived from the registered contracts (ETP-5715).
 *
 * <p><b>No list of specs lives here.</b> A document can be sent when the email framework has a
 * document-send contract for its spec — the same registry the application's send button goes
 * through — so a window gains the tool when its provider registers a contract and never before. A
 * purchase invoice has none (the application offers no send there) and is therefore not offered. The
 * contract of a spec is the document-send contract whose {@code getSpecName()} is that spec, which is
 * the contract-name convention the send modal uses ({@code <window>-send}) read in the other
 * direction.</p>
 */
final class McpDocumentEmailContracts {

  private static final Logger log = LogManager.getLogger(McpDocumentEmailContracts.class);
  private static final AtomicBoolean EMPTY_WARNED = new AtomicBoolean(false);

  private McpDocumentEmailContracts() {
  }

  /**
   * The runtime registry: the contracts every provider in the module registers.
   *
   * <p>Built once per call path by the caller and passed on: a lookup is a Weld query plus the
   * construction of every contract, so it is never repeated inside one request. It is not cached
   * across requests on purpose: the providers are CDI beans and a hot reload or a failed lookup
   * must not be remembered. When the lookup yields nothing (Weld failed, or no provider) the tool
   * fails closed, offering no document, and that is logged once at warn level, because the cause
   * is otherwise swallowed by the registry.</p>
   */
  static EmailContractRegistry registry() {
    EmailContractRegistry registry = DefaultEmailContractRegistry.createDefault();
    if (!registry.contracts().isEmpty()) {
      // Recovered: a later outage must warn again.
      EMPTY_WARNED.set(false);
    } else if (EMPTY_WARNED.compareAndSet(false, true)) {
      log.warn("No email contract is registered: etendo_send_document_email offers no document. "
          + "The CDI lookup of the EmailContractProvider beans may have failed.");
    }
    return registry;
  }

  /** The email service the application's own send button runs through. */
  static TransactionalEmailService service() {
    return new TransactionalEmailService();
  }

  /**
   * The document-send contract of a spec.
   *
   * @param specName the window spec, may be {@code null}
   * @param registry the registry of this call
   * @return its contract, or empty when the application offers no send for that document
   */
  static Optional<DefaultDocumentSendEmailContract> forSpec(String specName,
      EmailContractRegistry registry) {
    if (StringUtils.isBlank(specName)) {
      return Optional.empty();
    }
    for (DefaultDocumentSendEmailContract contract : documentContracts(registry.contracts())) {
      if (specName.equals(contract.getSpecName())) {
        return Optional.of(contract);
      }
    }
    return Optional.empty();
  }

  /**
   * Keeps the specs the caller can address that have a document-send contract, in the caller's order.
   *
   * @param specNames the window specs the caller can address
   * @return the subset the tool offers; an empty list builds no registry at all
   */
  static List<String> supportedSpecs(Collection<String> specNames) {
    if (specNames.isEmpty()) {
      return new ArrayList<>();
    }
    List<DefaultDocumentSendEmailContract> contracts = documentContracts(registry().contracts());
    List<String> offered = new ArrayList<>();
    for (String specName : specNames) {
      for (DefaultDocumentSendEmailContract contract : contracts) {
        if (specName.equals(contract.getSpecName())) {
          offered.add(specName);
          break;
        }
      }
    }
    return offered;
  }

  /**
   * Every spec with a contract, for the "not available, these are" answer.
   *
   * @param registry the registry of this call
   * @return the specs, in registration order
   */
  static List<String> allSpecs(EmailContractRegistry registry) {
    List<String> specs = new ArrayList<>();
    for (DefaultDocumentSendEmailContract contract : documentContracts(registry.contracts())) {
      if (contract.getSpecName() != null) {
        specs.add(contract.getSpecName());
      }
    }
    return specs;
  }

  private static List<DefaultDocumentSendEmailContract> documentContracts(
      Collection<EmailContract> all) {
    List<DefaultDocumentSendEmailContract> documents = new ArrayList<>();
    for (EmailContract contract : all) {
      if (contract instanceof DefaultDocumentSendEmailContract) {
        documents.add((DefaultDocumentSendEmailContract) contract);
      }
    }
    return documents;
  }
}
