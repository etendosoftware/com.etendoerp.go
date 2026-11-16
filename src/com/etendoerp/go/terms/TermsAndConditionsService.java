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

package com.etendoerp.go.terms;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.service.OBDal;

/**
 * The terms and conditions a new account accepts (ETP-6081).
 *
 * <p>Each version is a row of {@code ETGO_TERMS} with its text as a Word document ({@code .docx})
 * attached in Classic (ETP-6244), so publishing a new version is a row in the ERP, not a deploy.
 * A row without a document is never shown. A row with no language is shown in every language; one
 * with a language, only in that language.
 *
 * <p>The version in force in a language is, among its active rows with a document and a
 * publication date that is not in the future, the latest published of the rows of that language
 * and the rows of every language (on the same date, the one of that language). A language with no
 * such row falls back to another variant of the same language ({@code es_AR} reads {@code es_ES}),
 * then to English.
 *
 * <p>The account keeps the version it accepted and when ({@code ETGO_ACCOUNT.TERMS_VERSION} and
 * {@code TERMS_ACCEPTED_AT}). The version is the one the register screen showed: a version
 * published while someone was registering does not replace what that person actually saw. A
 * version the screen could not load, or one that does not exist, is replaced by the one in force.
 *
 * <p>The public body names the version; the register screen downloads its document from {@code
 * /sws/go/terms/document} and shows it converted to HTML.
 *
 * <p>The terms dialog also asks for consent to promotional mail ({@code
 * ETGO_ACCOUNT.MARKETING_CONSENT}). It is a choice of its own, not part of accepting the terms: it
 * can be refused without refusing the sign-up.
 */
public class TermsAndConditionsService {

  private static final Logger log = LogManager.getLogger(TermsAndConditionsService.class);

  /** The language read when the requested one, and every variant of it, has no version. */
  public static final String FALLBACK_LANGUAGE = "en_US";

  static final String PROPERTY_TERMS_VERSION = "termsVersion";
  static final String PROPERTY_TERMS_ACCEPTED_AT = "termsAcceptedAt";
  static final String PROPERTY_MARKETING_CONSENT = "marketingConsent";

  /** The JSON key and query parameter holding a version label. */
  static final String KEY_VERSION = "version";
  /** The JSON key and query parameter holding a language. */
  static final String KEY_LANGUAGE = "language";

  /** {@code ETGO_TERMS.VERSION} is VARCHAR(60). */
  static final int VERSION_MAX_LENGTH = 60;

  private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[A-Za-z]{2,3}(_[A-Za-z]{2})?");

  /** The file extension of the only document the register screen can show. */
  static final String DOCUMENT_EXTENSION = ".docx";

  /** The media type of a Word document. */
  public static final String DOCUMENT_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  /**
   * One version of the terms, as a row of {@code ETGO_TERMS} with a document.
   *
   * @param version     the version label, the same in every language of one release
   * @param language    the language of the row, e.g. {@code es_ES}, or {@code null} when the row
   *     is shown in every language
   * @param publishedAt from when the version is in force
   */
  public record Terms(String version, String language, Date publishedAt) {

    /**
     * What the public endpoint returns: the identity of the row, enough to download its document.
     *
     * @return the JSON body
     * @throws JSONException never in practice
     */
    public JSONObject toJson() throws JSONException {
      return new JSONObject()
          .put(KEY_VERSION, version)
          .put(KEY_LANGUAGE, language == null ? JSONObject.NULL : language)
          .put("publishedAt", publishedAt == null ? JSONObject.NULL : publishedAt.getTime());
    }
  }

  /** Where the versions are read from. */
  interface Store {
    /**
     * Finds the terms version currently in force for a language.
     *
     * @param language     the requested language, e.g. {@code es_ES}
     * @param baseLanguage its first two letters, e.g. {@code es}
     * @return the version in force: the latest of the requested language and of every language,
     *     then a variant of it, then {@link #FALLBACK_LANGUAGE}; only rows with a document count
     */
    Optional<Terms> findCurrent(String language, String baseLanguage);

    /**
     * Tells whether a terms version exists.
     *
     * @param version the version to look up
     * @return whether an active, published row with a document holds this version, in any
     *     language
     */
    boolean exists(String version);

    /**
     * Finds the Word document attached to one version in one language.
     *
     * @param language the exact language of the row, as {@link Terms#language()} returned it;
     *     empty for a row of every language
     * @param version  the version label
     * @return the attachment ({@code C_File}) id, or empty when the row is missing, inactive,
     *     not yet published, or has no {@code .docx} attached
     */
    default Optional<String> findDocumentId(String language, String version) {
      return Optional.empty();
    }

    /**
     * Reads the bytes of an attachment.
     *
     * @param attachmentId the {@code C_File} id {@link #findDocumentId} returned
     * @return the file content
     */
    default byte[] download(String attachmentId) {
      throw new UnsupportedOperationException("This store holds no documents");
    }
  }

  private final Store store;

  /** The service reading {@code ETGO_TERMS} through the current DAL session. */
  public TermsAndConditionsService() {
    this(new DalStore());
  }

  TermsAndConditionsService(Store store) {
    this.store = store;
  }

  /**
   * The version in force for a language.
   *
   * @param requestedLanguage the UI language, e.g. {@code es_ES}; blank or malformed reads English
   * @return the version, or empty when none is published at all
   */
  public Optional<Terms> findCurrent(String requestedLanguage) {
    String language = normalizeLanguage(requestedLanguage);
    return store.findCurrent(language, language.substring(0, 2).toLowerCase());
  }

  /**
   * The Word document of one version, as attached in Classic.
   *
   * @param language the language the public body returned for the version; blank for a version
   *     of every language
   * @param version  the version the public body returned
   * @return the {@code .docx} content, or empty when the parameters are malformed, the version is
   *     not in force in that language, or it has no document
   */
  public Optional<byte[]> findDocument(String language, String version) {
    String cleanVersion = StringUtils.trimToNull(version);
    String cleanLanguage = StringUtils.trimToEmpty(language);
    if (cleanVersion == null || cleanVersion.length() > VERSION_MAX_LENGTH
        || !(cleanLanguage.isEmpty() || LANGUAGE_PATTERN.matcher(cleanLanguage).matches())) {
      return Optional.empty();
    }
    return store.findDocumentId(cleanLanguage, cleanVersion).map(store::download);
  }

  /**
   * The version to record on a new account.
   *
   * @param sentVersion the version the register screen showed, or {@code null}
   * @param language    the language the account registered in, to find the one in force
   * @return the sent version when it exists, otherwise the one in force, or {@code null} when no
   *     version is published at all or the versions cannot be read. Never throws: a sign-up is
   *     never refused because the terms table could not be read; the acceptance date is still
   *     recorded.
   */
  public String resolveAcceptedVersion(String sentVersion, String language) {
    try {
      String version = StringUtils.trimToNull(sentVersion);
      if (version != null && version.length() <= VERSION_MAX_LENGTH && store.exists(version)) {
        return version;
      }
      return findCurrent(language).map(Terms::version).orElse(null);
    } catch (RuntimeException e) {
      log.warn("Could not resolve the accepted terms version; the account records none", e);
      return null;
    }
  }

  /**
   * Stamps the acceptance on an account that is being created; the caller saves it.
   *
   * @param account    the new account
   * @param version    the accepted version, possibly {@code null} when none is published
   * @param acceptedAt when it was accepted
   */
  public static void recordAcceptance(BaseOBObject account, String version, Date acceptedAt) {
    account.set(PROPERTY_TERMS_ACCEPTED_AT, acceptedAt);
    account.set(PROPERTY_TERMS_VERSION, version);
  }

  /**
   * Stamps on an account that is being created whether it agreed to receive promotional mail; the
   * caller saves it.
   *
   * @param account the new account
   * @param consent whether the sign-up left the consent ticked
   */
  public static void recordMarketingConsent(BaseOBObject account, boolean consent) {
    account.set(PROPERTY_MARKETING_CONSENT, consent);
  }

  static String normalizeLanguage(String requestedLanguage) {
    String language = StringUtils.trimToEmpty(requestedLanguage).replace('-', '_');
    return LANGUAGE_PATTERN.matcher(language).matches() ? language : FALLBACK_LANGUAGE;
  }

  /** Reads {@code ETGO_TERMS} with plain SQL: the rows are System data, read without a role. */
  static final class DalStore implements Store {

    /** The {@code .docx} attachments of a terms row, newest first. */
    private static final String DOCUMENT_OF_ROW = "select f.c_file_id from c_file f"
        + " join ad_table tb on tb.ad_table_id = f.ad_table_id"
        + " where lower(tb.tablename) = 'etgo_terms' and f.ad_record_id = t.etgo_terms_id"
        + " and f.isactive = 'Y' and lower(f.name) like '%" + DOCUMENT_EXTENSION + "'"
        + " order by f.updated desc";

    /** An active, published row with a document; a row without one has nothing to show. */
    private static final String SHOWN_ROW = "t.isactive = 'Y' and t.published <= now()"
        + " and exists(" + DOCUMENT_OF_ROW + ")";

    private static final String CURRENT_SQL = "select t.version, t.ad_language, t.published"
        + " from etgo_terms t"
        + " where " + SHOWN_ROW
        + " and (t.ad_language is null or t.ad_language = :language"
        + " or lower(substr(t.ad_language, 1, 2)) = :base or t.ad_language = :fallback)"
        + " order by case when t.ad_language is null or t.ad_language = :language then 0"
        + " when lower(substr(t.ad_language, 1, 2)) = :base then 1 else 2 end,"
        + " t.published desc, t.ad_language nulls last";

    private static final String DOCUMENT_SQL = "select (" + DOCUMENT_OF_ROW + " limit 1)"
        + " from etgo_terms t"
        + " where " + SHOWN_ROW
        + " and coalesce(t.ad_language, '') = :language and t.version = :version";

    private static final String EXISTS_SQL = "select 1 from etgo_terms t"
        + " where " + SHOWN_ROW + " and t.version = :version";

    @Override
    public Optional<Terms> findCurrent(String language, String baseLanguage) {
      List<?> rows = OBDal.getInstance().getSession().createNativeQuery(CURRENT_SQL)
          .setParameter(KEY_LANGUAGE, language)
          .setParameter("base", baseLanguage)
          .setParameter("fallback", FALLBACK_LANGUAGE)
          .setMaxResults(1)
          .getResultList();
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      Object[] row = (Object[]) rows.get(0);
      return Optional.of(new Terms((String) row[0], (String) row[1],
          row[2] instanceof Timestamp ? new Date(((Timestamp) row[2]).getTime()) : null));
    }

    @Override
    public Optional<String> findDocumentId(String language, String version) {
      List<?> rows = OBDal.getInstance().getSession().createNativeQuery(DOCUMENT_SQL)
          .setParameter(KEY_LANGUAGE, language)
          .setParameter(KEY_VERSION, version)
          .setMaxResults(1)
          .getResultList();
      return rows.isEmpty() || rows.get(0) == null ? Optional.empty()
          : Optional.of(String.valueOf(rows.get(0)));
    }

    @Override
    public byte[] download(String attachmentId) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class)
          .download(attachmentId, buffer);
      return buffer.toByteArray();
    }

    @Override
    public boolean exists(String version) {
      return !OBDal.getInstance().getSession().createNativeQuery(EXISTS_SQL)
          .setParameter(KEY_VERSION, version)
          .setMaxResults(1)
          .getResultList()
          .isEmpty();
    }
  }
}
