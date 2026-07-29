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

package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.DocumentType;

/**
 * Auto-flags rectificative document types and their sequences for a client when a SIF (SII,
 * TicketBAI or Verifactu) configuration is saved from Etendo Go (ETP-4536).
 *
 * <p>The {@code com.etendoerp.sif.general} module requires the document types used for
 * rectificative invoices (Credit Note / Return) to carry {@code C_DocType.EM_Etsg_Isrectificative
 * = 'Y'}, and their sequences to carry {@code AD_Sequence.EM_Etsg_Isrectificative = 'Y'}, otherwise
 * completing a credit note linked to an original invoice fails with
 * {@code @ETSG_Rectificative_Type_Not_Rectificative@}. Etendo Classic lets a user set these flags
 * by hand from the Document Type window; Etendo Go exposes neither document type nor sequence
 * maintenance, so a GO-only user who configures a SIF has no way to flag them. This service closes
 * that gap: it is invoked from the SIF-config NeoHandlers' {@code afterHandle()} and flags every
 * rectificative-capable document type of the client (plus its sequences) automatically.
 *
 * <h2>Ordering (mandatory)</h2>
 * The {@code ETSG_CHECK_RECTIF_DOC_TYPE} trigger rejects flagging a document type whose sequence is
 * not already flagged. So per document type the sequence is flagged FIRST and the document type
 * SECOND. Because the trigger raises {@code @ETSG_Rectificative_DocType_Without_Seq@} when a
 * document type would be flagged with no rectificative sequence available, this service
 * <b>pre-validates in Java</b> (skipping and warning instead of relying on the trigger) — a trigger
 * error here would otherwise poison the shared transaction and roll back the just-saved SIF config.
 *
 * <h2>Sequence selection</h2>
 * <ul>
 *   <li>Doc-no controlled document type with an assigned {@code DocNoSequence_ID}
 *       ({@link DocumentType#isSequencedDocument()} + {@link DocumentType#getDocumentSequence()}):
 *       flag that single sequence (matches the trigger's doc-no-controlled branch, which compares
 *       the document type flag against its {@code DocNoSequence} flag).</li>
 *   <li>Otherwise: flag every active sequence linked via {@code AD_Sequence.C_DocType_ID}.</li>
 *   <li>No sequence at all: the document type is skipped and reported as a warning (a rectificative
 *       document type without a sequence cannot exist — creating sequences is out of scope).</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * Values already at {@code 'Y'} are left untouched (the {@code UPDATE}s filter them out), so
 * re-saving a configuration or configuring a second SIF is a no-op.
 *
 * <h2>Module independence</h2>
 * The {@code EM_Etsg_Isrectificative} columns belong to {@code com.etendoerp.sif.general}. To avoid
 * a compile-time dependency from {@code com.etendoerp.go} on that module, the flags are read/written
 * via native SQL (never via a generated DAL extension) — the same approach used by
 * {@link VerifactuConfigReadyHandler}. When the columns are absent (SIF General not installed) the
 * service is a no-op.
 */
public class RectificativeDocTypeFlagService {

  private static final Logger log = LogManager.getLogger(RectificativeDocTypeFlagService.class);

  /**
   * All invoice-category document types (sales/purchase invoices and their credit notes/returns)
   * are backed by this table, so filtering on it complements the {@code documentCategory} check
   * without a hardcoded document-type-name list.
   */
  private static final String INVOICE_TABLE_NAME = "C_Invoice";
  private static final String TABLE_ALIAS = "tbl";

  // Document categories that identify rectificative-capable document types. Mirrors
  // SalesInvoiceHeaderHandler / PurchaseInvoiceHeaderHandler#classifyDocType (NC/DEV subtypes):
  // ARC/APC -> Credit Note (NC); ARI_RM -> sales Return (DEV); API + isReturn -> purchase Return.
  private static final String CATEGORY_SALES_CREDIT = "ARC";
  private static final String CATEGORY_PURCHASE_CREDIT = "APC";
  private static final String CATEGORY_SALES_RETURN = "ARI_RM";
  private static final String CATEGORY_PURCHASE_INVOICE = "API";

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /** Response field the skipped-document-type warnings are surfaced under (TC-07). */
  private static final String WARNINGS_FIELD = "warnings";

  private static final String FLAG_SEQUENCE_SQL =
      "UPDATE ad_sequence SET em_etsg_isrectificative = 'Y', updated = now() "
          + "WHERE ad_sequence_id = :id AND COALESCE(em_etsg_isrectificative, 'N') <> 'Y'";
  private static final String FLAG_DOCTYPE_SQL =
      "UPDATE c_doctype SET em_etsg_isrectificative = 'Y', updated = now() "
          + "WHERE c_doctype_id = :id AND COALESCE(em_etsg_isrectificative, 'N') <> 'Y'";

  /** Cached presence of the SIF General {@code em_etsg_isrectificative} columns. */
  private static volatile Boolean rectificativeColumnsPresent;

  /**
   * Outcome of a flagging run: counters plus the per-document-type warnings that support can act
   * on (document types skipped because no sequence was available to flag).
   */
  public static final class Result {
    private final List<String> warnings = new ArrayList<>();
    private int flaggedDocTypes;
    private int flaggedSequences;

    public List<String> getWarnings() {
      return warnings;
    }

    public int getFlaggedDocTypes() {
      return flaggedDocTypes;
    }

    public int getFlaggedSequences() {
      return flaggedSequences;
    }
  }

  /**
   * Flags every rectificative-capable ({@code NC}/{@code DEV}) active document type of {@code
   * client} and its sequences. Idempotent and safe to call repeatedly. Never throws for business
   * reasons — a document type that cannot be flagged is reported as a warning in the result.
   *
   * @param client the client whose document types are flagged; {@code null} yields an empty result
   * @return a {@link Result} with counters and warnings (never {@code null})
   */
  public Result flagForClient(Client client) {
    Result result = new Result();
    if (client == null || !isRectificativeColumnsPresent()) {
      return result;
    }
    List<DocumentType> targets = findRectificativeDocTypes(client);
    for (DocumentType docType : targets) {
      flagDocumentType(docType, result);
    }
    OBDal.getInstance().flush();
    return result;
  }

  /**
   * Orchestration entry point for the SIF-config NeoHandlers' {@code afterHandle()}. On a
   * successful create/update it flags the client's rectificative document types and sequences
   * (client-wide, so it does not depend on the saved record's id), running under admin mode and
   * swallowing any failure so this secondary side effect never fails the parent request.
   *
   * @param context the current NEO request context
   * @return an augmented {@link NeoResponse} carrying skipped-document-type warnings when any were
   *     produced, or {@code null} to keep the original CRUD response untouched
   */
  public NeoResponse applyAfterConfigSave(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD
        || !isWriteMethod(context.getHttpMethod())) {
      return null;
    }
    Client client = context.getObContext() == null ? null : context.getObContext().getCurrentClient();
    Result result;
    try {
      OBContext.setAdminMode(true);
      try {
        result = flagForClient(client);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("RectificativeDocTypeFlagService.applyAfterConfigSave error: {}", e.getMessage(), e);
      return null;
    }
    if (!result.getWarnings().isEmpty()) {
      log.warn("Rectificative flagging produced {} warning(s): {}",
          result.getWarnings().size(), result.getWarnings());
    }
    return augmentWithWarnings(context, result);
  }

  private static boolean isWriteMethod(String method) {
    return METHOD_POST.equalsIgnoreCase(method)
        || METHOD_PUT.equalsIgnoreCase(method)
        || METHOD_PATCH.equalsIgnoreCase(method);
  }

  /**
   * Returns a copy of the parent CRUD response with the skipped-document-type warnings appended
   * under {@code warnings}, or {@code null} when there are no warnings or no response to augment
   * (so the original response is kept unchanged).
   */
  private NeoResponse augmentWithWarnings(NeoContext context, Result result) {
    if (result.getWarnings().isEmpty()) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = new JSONObject(previous.getBody().toString());
      JSONArray warnings = new JSONArray();
      for (String warning : result.getWarnings()) {
        warnings.put(warning);
      }
      body.put(WARNINGS_FIELD, warnings);
      return new NeoResponse(previous.getHttpStatus(), body);
    } catch (Exception e) {
      log.warn("Could not append rectificative warnings to the response: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Finds every active invoice {@link DocumentType} of {@code client} (backed by the
   * {@link #INVOICE_TABLE_NAME} table) whose subtype is a Credit Note or a Return.
   */
  @SuppressWarnings("unchecked")
  private List<DocumentType> findRectificativeDocTypes(Client client) {
    OBCriteria<DocumentType> crit = OBDal.getInstance().createCriteria(DocumentType.class);
    crit.add(Restrictions.eq(DocumentType.PROPERTY_CLIENT + ".id", client.getId()));
    crit.add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true));
    crit.createAlias(DocumentType.PROPERTY_TABLE, TABLE_ALIAS);
    crit.add(Restrictions.eq(TABLE_ALIAS + "." + Table.PROPERTY_DBTABLENAME, INVOICE_TABLE_NAME));

    List<DocumentType> rectificative = new ArrayList<>();
    for (DocumentType dt : crit.list()) {
      if (isRectificativeSubtype(dt)) {
        rectificative.add(dt);
      }
    }
    return rectificative;
  }

  /**
   * True when the document type is a Credit Note or a Return, i.e. one that can carry the
   * rectificative flag. Standard (FAC) invoice document types return false and are left untouched.
   */
  private boolean isRectificativeSubtype(DocumentType dt) {
    String category = dt.getDocumentCategory();
    if (CATEGORY_SALES_CREDIT.equals(category) || CATEGORY_PURCHASE_CREDIT.equals(category)) {
      return true;
    }
    if (CATEGORY_SALES_RETURN.equals(category)) {
      return true;
    }
    return CATEGORY_PURCHASE_INVOICE.equals(category) && Boolean.TRUE.equals(dt.isReturn());
  }

  /**
   * Flags a single document type: its sequence(s) first, then the document type itself. Skips and
   * warns when no sequence is available to flag (see class Javadoc). All updates are idempotent.
   */
  private void flagDocumentType(DocumentType docType, Result result) {
    List<String> sequenceIds = resolveSequencesToFlag(docType);
    if (sequenceIds.isEmpty()) {
      result.warnings.add(String.format(
          "Document type '%s' (%s) could not be flagged rectificative: no sequence available to flag",
          docType.getName(), docType.getId()));
      return;
    }
    for (String sequenceId : sequenceIds) {
      result.flaggedSequences += flag(FLAG_SEQUENCE_SQL, sequenceId);
    }
    result.flaggedDocTypes += flag(FLAG_DOCTYPE_SQL, docType.getId());
  }

  /**
   * Resolves the sequence(s) that must be flagged for {@code docType}: its assigned
   * {@code DocNoSequence} when doc-no controlled, otherwise the active sequences linked via
   * {@code AD_Sequence.C_DocType_ID}.
   */
  @SuppressWarnings("unchecked")
  private List<String> resolveSequencesToFlag(DocumentType docType) {
    if (Boolean.TRUE.equals(docType.isSequencedDocument()) && docType.getDocumentSequence() != null) {
      List<String> ids = new ArrayList<>(1);
      ids.add(docType.getDocumentSequence().getId());
      return ids;
    }
    OBCriteria<Sequence> crit = OBDal.getInstance().createCriteria(Sequence.class);
    crit.add(Restrictions.eq(Sequence.PROPERTY_DOCUMENTTYPE + ".id", docType.getId()));
    crit.add(Restrictions.eq(Sequence.PROPERTY_ACTIVE, true));
    List<String> ids = new ArrayList<>();
    for (Sequence seq : crit.list()) {
      ids.add(seq.getId());
    }
    return ids;
  }

  /**
   * Runs one idempotent flag {@code UPDATE} (already-{@code 'Y'} rows are filtered out by the SQL)
   * and returns the number of rows actually changed.
   */
  private int flag(String sql, String id) {
    return OBDal.getInstance().getSession()
        .createNativeQuery(sql)
        .setParameter("id", id)
        .executeUpdate();
  }

  /**
   * Whether the SIF General {@code em_etsg_isrectificative} columns exist (checked once, cached).
   * When absent the module is not installed and the service is a no-op. Mirrors
   * {@code AbstractInvoiceHeaderHandler#isRectificativeColumnPresent()}.
   */
  private static boolean isRectificativeColumnsPresent() {
    Boolean present = rectificativeColumnsPresent;
    if (present == null) {
      synchronized (RectificativeDocTypeFlagService.class) {
        present = rectificativeColumnsPresent;
        if (present == null) {
          present = queryColumnsPresent();
          rectificativeColumnsPresent = present;
        }
      }
    }
    return present;
  }

  @SuppressWarnings("java:S2077")
  private static boolean queryColumnsPresent() {
    String sql = "SELECT 1 FROM information_schema.columns"
        + " WHERE (table_name = 'c_doctype' OR table_name = 'ad_sequence')"
        + " AND column_name = 'em_etsg_isrectificative'"
        + " GROUP BY table_name HAVING count(*) >= 1";
    try {
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql);
           ResultSet rs = ps.executeQuery()) {
        // Two rows (one per table) means the column exists on both; require at least the two.
        int tables = 0;
        while (rs.next()) {
          tables++;
        }
        return tables >= 2;
      }
    } catch (Exception e) {
      log.warn("Could not check for em_etsg_isrectificative columns: {}", e.getMessage());
      return false;
    }
  }

  /** Test hook: force or reset (null) the cached column-presence check. */
  static void setRectificativeColumnsPresentForTests(Boolean value) {
    rectificativeColumnsPresent = value;
  }
}
