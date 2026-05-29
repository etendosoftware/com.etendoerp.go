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

package com.etendoerp.go.schemaforge.email;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.data.Account;

final class DalEmailContractDataResolver implements EmailContractDataResolver {

  private static final Logger log = LogManager.getLogger(DalEmailContractDataResolver.class);

  private static final String PROP_DOCUMENT_DOWNLOAD_BASE_URL =
      "etendo.go.email.documentDownloadBaseUrl";
  private static final String ENV_DOCUMENT_DOWNLOAD_BASE_URL =
      "ETGO_EMAIL_DOCUMENT_DOWNLOAD_BASE_URL";

  @Override
  public Optional<EmailContactRecord> findAccountContact(String accountId) {
    String normalizedId = StringUtils.trimToNull(accountId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    Account account = OBDal.getInstance().get(Account.class, normalizedId);
    if (account == null || !Boolean.TRUE.equals(account.isActive())
        || !isReadableClient(account.getClient().getId())) {
      return Optional.empty();
    }
    return Optional.of(new EmailContactRecord(account.getName(), account.getEmail()));
  }

  @Override
  public Optional<EmailContactRecord> findUserContact(String userId) {
    String normalizedId = StringUtils.trimToNull(userId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    User user = OBDal.getInstance().get(User.class, normalizedId);
    if (user == null || !Boolean.TRUE.equals(user.isActive())
        || !isReadableClient(user.getClient().getId())) {
      return Optional.empty();
    }
    return Optional.of(new EmailContactRecord(user.getName(), user.getEmail()));
  }

  @Override
  public Optional<EmailDocumentRecord> findSalesInvoice(String invoiceId) {
    String normalizedId = StringUtils.trimToNull(invoiceId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    Invoice invoice = OBDal.getInstance().get(Invoice.class, normalizedId);
    if (invoice == null || !Boolean.TRUE.equals(invoice.isActive())
        || !isReadableClient(invoice.getClient().getId())) {
      return Optional.empty();
    }
    BusinessPartner businessPartner = invoice.getBusinessPartner();
    String recipientEmail = resolveBusinessPartnerEmail(businessPartner);
    String recipientName = businessPartner == null ? null : businessPartner.getName();
    return Optional.of(new EmailDocumentRecord(recipientName, recipientEmail, invoice.getDocumentNo(),
        formatAmount(invoice.getGrandTotalAmount(), invoice.getCurrency()),
        buildDocumentDownloadLink("sales-invoice", invoice.getId())));
  }

  @Override
  public Optional<EmailDocumentRecord> findSalesOrder(String orderId) {
    String normalizedId = StringUtils.trimToNull(orderId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    Order order = OBDal.getInstance().get(Order.class, normalizedId);
    if (order == null || !Boolean.TRUE.equals(order.isActive())
        || !Boolean.TRUE.equals(order.isSalesTransaction())
        || !isReadableClient(order.getClient().getId())) {
      return Optional.empty();
    }
    BusinessPartner businessPartner = order.getBusinessPartner();
    String recipientEmail = resolveBusinessPartnerEmail(businessPartner);
    String recipientName = businessPartner == null ? null : businessPartner.getName();
    return Optional.of(new EmailDocumentRecord(recipientName, recipientEmail, order.getDocumentNo(),
        formatAmount(order.getGrandTotalAmount(), order.getCurrency()),
        buildDocumentDownloadLink("sales-order", order.getId())));
  }

  private static String resolveBusinessPartnerEmail(BusinessPartner businessPartner) {
    if (businessPartner == null) {
      return null;
    }
    String email = StringUtils.trimToNull(businessPartner.getEtgoEmail());
    if (email != null) {
      return email;
    }
    for (User user : businessPartner.getADUserList()) {
      if (Boolean.TRUE.equals(user.isActive()) && StringUtils.isNotBlank(user.getEmail())) {
        return user.getEmail();
      }
    }
    return null;
  }

  private static String formatAmount(BigDecimal amount, Currency currency) {
    String value = amount == null ? "0" : amount.toPlainString();
    String isoCode = currency == null ? null : StringUtils.trimToNull(currency.getISOCode());
    return isoCode == null ? value : value + " " + isoCode;
  }

  private static String buildDocumentDownloadLink(String documentType, String recordId) {
    String baseUrl = readConfig(PROP_DOCUMENT_DOWNLOAD_BASE_URL, ENV_DOCUMENT_DOWNLOAD_BASE_URL);
    if (baseUrl == null) {
      return null;
    }
    return StringUtils.removeEnd(baseUrl, "/") + "/" + encode(documentType) + "/"
        + encode(recordId);
  }

  private static String readConfig(String propertyName, String envName) {
    String systemValue = StringUtils.trimToNull(System.getProperty(propertyName));
    if (systemValue != null) {
      return systemValue;
    }
    String envValue = StringUtils.trimToNull(System.getenv(envName));
    return envValue != null ? envValue : readOpenbravoProperty(propertyName);
  }

  private static String readOpenbravoProperty(String propertyName) {
    try {
      return StringUtils.trimToNull(org.openbravo.base.session.OBPropertiesProvider.getInstance()
          .getOpenbravoProperties().getProperty(propertyName));
    } catch (Exception e) {
      log.debug("Could not read Openbravo property {}: {}", propertyName, e.getMessage(), e);
      return null;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static boolean isReadableClient(String clientId) {
    if (StringUtils.isBlank(clientId) || "0".equals(clientId)) {
      return true;
    }
    try {
      OBContext context = OBContext.getOBContext();
      if (clientId.equals(context.getCurrentClient().getId())) {
        return true;
      }
      for (String readableClient : context.getReadableClients()) {
        if (clientId.equals(readableClient)) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      log.debug("Could not resolve readable clients for email contract: {}", e.getMessage(), e);
      return false;
    }
  }
}
