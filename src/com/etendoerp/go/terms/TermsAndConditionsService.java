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
import org.openbravo.dal.service.OBDal;

/**
 * The terms and conditions a new account accepts (ETP-6081).
 *
 * <p>Their text lives in {@code ETGO_TERMS}, one row per language and version, so publishing a
 * new version is a row in the ERP, not a deploy. The version in force in a language is its active
 * row with the latest publication date that is not in the future. A language with no version
 * falls back to another variant of the same language ({@code es_AR} reads {@code es_ES}), then to
 * English.
 *
 * <p>The account keeps the version it accepted and when ({@code ETGO_ACCOUNT.TERMS_VERSION} and
 * {@code TERMS_ACCEPTED_AT}). The version is the one the register screen showed: a version
 * published while someone was registering does not replace what that person actually saw. A
 * version the screen could not load, or one that does not exist, is replaced by the one in force.
 */
public class TermsAndConditionsService {

  private static final Logger log = LogManager.getLogger(TermsAndConditionsService.class);

  /** The language read when the requested one, and every variant of it, has no version. */
  public static final String FALLBACK_LANGUAGE = "en_US";

  static final String PROPERTY_TERMS_VERSION = "termsVersion";
  static final String PROPERTY_TERMS_ACCEPTED_AT = "termsAcceptedAt";

  /** {@code ETGO_TERMS.VERSION} is VARCHAR(60). */
  static final int VERSION_MAX_LENGTH = 60;

  private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[A-Za-z]{2,3}(_[A-Za-z]{2})?");

  /** One version of the terms in one language. */
  public record Terms(String version, String language, String content, Date publishedAt) {

    /**
     * What the public endpoint returns: the text and its identity, nothing else of the row.
     *
     * @return the JSON body
     * @throws JSONException never in practice
     */
    public JSONObject toJson() throws JSONException {
      return new JSONObject()
          .put("version", version)
          .put("language", language)
          .put("content", content)
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
     * @return the version in force, preferring the requested language, then a variant of it,
     *     then {@link #FALLBACK_LANGUAGE}
     */
    Optional<Terms> findCurrent(String language, String baseLanguage);

    /**
     * Tells whether a terms version exists.
     *
     * @param version the version to look up
     * @return whether an active row holds this version, in any language
     */
    boolean exists(String version);
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

  static String normalizeLanguage(String requestedLanguage) {
    String language = StringUtils.trimToEmpty(requestedLanguage).replace('-', '_');
    return LANGUAGE_PATTERN.matcher(language).matches() ? language : FALLBACK_LANGUAGE;
  }

  /** Reads {@code ETGO_TERMS} with plain SQL: the rows are System data, read without a role. */
  static final class DalStore implements Store {

    private static final String CURRENT_SQL = "select version, ad_language, content, published"
        + " from etgo_terms"
        + " where isactive = 'Y' and published <= now()"
        + " and (ad_language = :language or lower(substr(ad_language, 1, 2)) = :base"
        + " or ad_language = :fallback)"
        + " order by case when ad_language = :language then 0"
        + " when lower(substr(ad_language, 1, 2)) = :base then 1 else 2 end,"
        + " published desc, ad_language";

    private static final String EXISTS_SQL = "select 1 from etgo_terms"
        + " where isactive = 'Y' and version = :version";

    @Override
    public Optional<Terms> findCurrent(String language, String baseLanguage) {
      List<?> rows = OBDal.getInstance().getSession().createNativeQuery(CURRENT_SQL)
          .setParameter("language", language)
          .setParameter("base", baseLanguage)
          .setParameter("fallback", FALLBACK_LANGUAGE)
          .setMaxResults(1)
          .getResultList();
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      Object[] row = (Object[]) rows.get(0);
      return Optional.of(new Terms((String) row[0], (String) row[1], String.valueOf(row[2]),
          row[3] instanceof Timestamp ? new Date(((Timestamp) row[3]).getTime()) : null));
    }

    @Override
    public boolean exists(String version) {
      return !OBDal.getInstance().getSession().createNativeQuery(EXISTS_SQL)
          .setParameter("version", version)
          .setMaxResults(1)
          .getResultList()
          .isEmpty();
    }
  }
}
