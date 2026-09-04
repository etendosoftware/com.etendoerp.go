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

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.DynamicOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * DAL-backed store for the readable per-document email send history
 * ({@code ETGO_Email_Send_Log}).
 *
 * <p>Accessed through {@link DynamicOBObject} rather than a typed entity, exactly like
 * {@link DalEmailSafetyStore}: entities in this module are generated at build time
 * ({@code src-gen} is gitignored and there are no {@code .hbm.xml} files), so no typed class is
 * committed for the table.</p>
 *
 * <p><b>No admin mode, and no forced client 0.</b> {@code ETGO_Email_Send_Log} is a
 * Client/Organization table (AD access level 3), which is the whole point of it: the row must
 * carry the real tenant so the read endpoint's readable-client filter is a genuine access rule.
 * Letting DAL fill {@code AD_Client_ID}/{@code AD_Org_ID}/{@code CreatedBy} from the caller's own
 * session also makes {@code CreatedBy} the actual sender, which closes the long-standing
 * null-{@code userId} gap in the audit ledger for free — {@code ETGO_Email_Safety} needs
 * {@link OBContext#setAdminMode()} only because its rows are owned by client 0.</p>
 *
 * <p>Every {@code VARCHAR} value is truncated to its declared column width before being set. A
 * value overflowing its column would fail the {@code INSERT}, and this row shares the
 * transaction with the anti-abuse audit row written right after it — a history row is never
 * worth losing the audit row (or the send) over.</p>
 */
public class DalEmailSendLogStore implements EmailSendLogStore {

  private static final Logger log = LogManager.getLogger(DalEmailSendLogStore.class);

  static final String ENTITY_EMAIL_SEND_LOG = "ETGO_Email_Send_Log";

  private static final String PROP_ACTIVE = "active";
  private static final String PROP_CLIENT = "client";
  private static final String PROP_CONTRACT_NAME = "contractName";
  private static final String PROP_DOWNLOAD_LINK = "downloadLink";
  private static final String PROP_EMAIL_LANGUAGE = "emailLanguage";
  private static final String PROP_ERROR_MESSAGE = "errorMessage";
  private static final String PROP_IDEMPOTENCY_KEY = "idempotencyKey";
  private static final String PROP_MESSAGE_BODY = "messageBody";
  private static final String PROP_ORGANIZATION = "organization";
  private static final String PROP_RECIPIENTS_CC = "recipientsCC";
  private static final String PROP_RECIPIENTS_TO = "recipientsTo";
  private static final String PROP_RECORD_ID = "recordID";
  private static final String PROP_SENT_AT = "sentAt";
  private static final String PROP_SPEC_NAME = "specName";
  private static final String PROP_STATUS = "status";
  private static final String PROP_SUBJECT = "subject";
  private static final String PROP_TABLE = "table";

  private static final int LEN_CONTRACT_NAME = 80;
  private static final int LEN_SPEC_NAME = 80;
  private static final int LEN_RECORD_ID = 32;
  private static final int LEN_STATUS = 40;
  private static final int LEN_ERROR_MESSAGE = 2000;
  private static final int LEN_RECIPIENTS = 2000;
  private static final int LEN_SUBJECT = 400;
  private static final int LEN_DOWNLOAD_LINK = 1000;
  private static final int LEN_EMAIL_LANGUAGE = 10;
  private static final int LEN_MESSAGE_BODY = 4000;
  private static final int LEN_IDEMPOTENCY_KEY = 255;

  private static final String RECIPIENT_SEPARATOR = ", ";

  private final Supplier<BaseOBObject> recordSupplier;

  /**
   * Creates a DAL-backed store writing real {@code ETGO_Email_Send_Log} rows.
   */
  public DalEmailSendLogStore() {
    this(DalEmailSendLogStore::newSendLogRecord);
  }

  DalEmailSendLogStore(Supplier<BaseOBObject> recordSupplier) {
    this.recordSupplier = Objects.requireNonNull(recordSupplier,
        "Email send log record supplier cannot be null");
  }

  @Override
  public void record(EmailSendHistoryRecord historyRecord) {
    Objects.requireNonNull(historyRecord, "Email send history record cannot be null");
    BaseOBObject entry = recordSupplier.get();
    entry.set(PROP_CLIENT, OBContext.getOBContext().getCurrentClient());
    entry.set(PROP_ORGANIZATION, OBContext.getOBContext().getCurrentOrganization());
    entry.set(PROP_ACTIVE, Boolean.TRUE);
    entry.set(PROP_CONTRACT_NAME, truncate(historyRecord.getContractName(), LEN_CONTRACT_NAME));
    entry.set(PROP_SPEC_NAME, truncate(historyRecord.getSpecName(), LEN_SPEC_NAME));
    entry.set(PROP_TABLE, resolveTable(historyRecord.getSpecName()));
    entry.set(PROP_RECORD_ID, truncate(historyRecord.getRecordId(), LEN_RECORD_ID));
    entry.set(PROP_SENT_AT, new Date(historyRecord.getSentAtMillis()));
    entry.set(PROP_STATUS, truncate(historyRecord.getStatus(), LEN_STATUS));
    entry.set(PROP_ERROR_MESSAGE, truncate(historyRecord.getErrorMessage(), LEN_ERROR_MESSAGE));
    entry.set(PROP_RECIPIENTS_TO, truncate(join(historyRecord.getRecipientsTo()), LEN_RECIPIENTS));
    entry.set(PROP_RECIPIENTS_CC, truncate(join(historyRecord.getRecipientsCc()), LEN_RECIPIENTS));
    entry.set(PROP_SUBJECT, truncate(historyRecord.getSubject(), LEN_SUBJECT));
    // MESSAGE_BODY is a CLOB and is stored whole: the rendered email layout is routinely larger
    // than the nominal size the model XML declares, exactly as ETGO_Email_Safety.PAYLOAD is.
    entry.set(PROP_MESSAGE_BODY, truncate(historyRecord.getMessageBody(), LEN_MESSAGE_BODY));
    entry.set(PROP_DOWNLOAD_LINK, truncate(historyRecord.getDownloadLink(), LEN_DOWNLOAD_LINK));
    entry.set(PROP_EMAIL_LANGUAGE, truncate(historyRecord.getLanguage(), LEN_EMAIL_LANGUAGE));
    entry.set(PROP_IDEMPOTENCY_KEY,
        truncate(historyRecord.getIdempotencyKey(), LEN_IDEMPOTENCY_KEY));
    OBDal.getInstance().save(entry);
  }

  /**
   * Resolves the document's own {@code AD_Table} from the NEO spec name, through
   * {@code ETGO_SF_SPEC -> AD_Window -> first tab -> AD_Table}. Best effort: the column is
   * nullable and a spec that is not published (or a window with no tab) simply leaves it unset
   * rather than failing the send.
   */
  private Table resolveTable(String specName) {
    String normalized = StringUtils.trimToNull(specName);
    if (normalized == null) {
      return null;
    }
    try {
      SFSpec spec = findSpec(normalized);
      Window window = spec == null ? null : spec.getADWindow();
      if (window == null) {
        return null;
      }
      Tab headerTab = findHeaderTab(window);
      return headerTab == null ? null : headerTab.getTable();
    } catch (Exception e) {
      log.debug("Could not resolve AD_Table for spec [{}]: {}", normalized, e.getMessage());
      return null;
    }
  }

  private static SFSpec findSpec(String specName) {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.ilike(SFSpec.PROPERTY_NAME, specName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    List<SFSpec> specs = criteria.list();
    return specs.isEmpty() ? null : specs.get(0);
  }

  private static Tab findHeaderTab(Window window) {
    OBCriteria<Tab> criteria = OBDal.getInstance().createCriteria(Tab.class);
    criteria.add(Restrictions.eq(Tab.PROPERTY_WINDOW, window));
    criteria.add(Restrictions.eq(Tab.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(Tab.PROPERTY_TABLEVEL, true);
    criteria.addOrderBy(Tab.PROPERTY_SEQUENCENUMBER, true);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    List<Tab> tabs = criteria.list();
    return tabs.isEmpty() ? null : tabs.get(0);
  }

  private static String join(List<String> addresses) {
    return addresses == null || addresses.isEmpty() ? null
        : StringUtils.join(addresses, RECIPIENT_SEPARATOR);
  }

  private static String truncate(String value, int maxLength) {
    String normalized = StringUtils.trimToNull(value);
    if (normalized == null || normalized.length() <= maxLength) {
      return normalized;
    }
    log.debug("Truncating email send history value to {} characters", maxLength);
    return normalized.substring(0, maxLength);
  }

  private static BaseOBObject newSendLogRecord() {
    DynamicOBObject sendLogRecord = new DynamicOBObject();
    sendLogRecord.setEntityName(ENTITY_EMAIL_SEND_LOG);
    return sendLogRecord;
  }
}
