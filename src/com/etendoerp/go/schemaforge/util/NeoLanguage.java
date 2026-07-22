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

package com.etendoerp.go.schemaforge.util;

import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Language;

/**
 * Shared language resolution for the NEO request boundary (ETP-4304 / ETP-4306).
 *
 * <p>Etendo GO sends the user's active GO locale on every request via the
 * {@code Accept-Language} header ({@code xx_YY}, e.g. {@code es_ES}), and
 * {@code NeoAuthenticator} applies it to the {@link OBContext} for the request.
 * The result is that {@link #current()} is the GO locale throughout the request.
 *
 * <p>Both localization tasks resolve translations in the GO locale rather than the
 * Classic session language, so they share one source of truth here instead of each
 * re-deriving and re-validating the language:
 * <ul>
 *   <li>ETP-4304 — selector values ({@code *_Trl} identifiers, {@code AD_Ref_List}).</li>
 *   <li>ETP-4306 — backend messages ({@code AD_Message}) and process/error text.</li>
 * </ul>
 *
 * <p>This class does not decide policy (which header/param wins) — that stays in
 * {@code NeoAuthenticator}. It only provides reusable, validated primitives.
 */
public final class NeoLanguage {

  private static final Logger log = LogManager.getLogger(NeoLanguage.class);

  /** Etendo language codes look like {@code es_ES}; browser values are rejected. */
  private static final String CODE_PATTERN = "[a-z]{2}_[A-Z]{2}";

  private NeoLanguage() {
  }

  /**
   * Resolve an Etendo language code to an ACTIVE {@link Language} row, or
   * {@code null} when the code is malformed, unknown, or inactive.
   *
   * <p>Extracted from {@code NeoAuthenticator.applyRequestLanguage} so the same
   * validation (well-formed {@code xx_YY} + active in {@code AD_Language}) is reused
   * by any NEO path that needs to honor a requested language. Runs the lookup in
   * admin mode and never throws — callers get {@code null} and fall back.
   *
   * @param code an Etendo language code such as {@code es_ES}, possibly {@code null}
   * @return the active {@link Language}, or {@code null} if not resolvable
   */
  public static Language resolveActive(String code) {
    if (code == null || !code.trim().matches(CODE_PATTERN)) {
      return null;
    }
    boolean adminMode = false;
    try {
      OBContext.setAdminMode(true);
      adminMode = true;
      OBCriteria<Language> crit = OBDal.getInstance().createCriteria(Language.class);
      crit.add(Restrictions.eq(Language.PROPERTY_LANGUAGE, code.trim()));
      crit.add(Restrictions.eq(Language.PROPERTY_ACTIVE, true));
      crit.setMaxResults(1);
      return (Language) crit.uniqueResult();
    } catch (Exception e) {
      log.warn("Could not resolve active language '{}': {}", code, e.getMessage());
      return null;
    } finally {
      if (adminMode) {
        OBContext.restorePreviousMode();
      }
    }
  }

  /**
   * Resolve {@code code} and apply it to the current {@link OBContext} as the request
   * language, swallowing any error so this translation-only concern never fails the
   * request. No-op when the code is malformed / unknown / inactive.
   *
   * <p>The {@code setLanguage} MUST run inside admin mode: reading the resolved
   * {@link Language} entity (e.g. its RTL flag) is not permitted under a restricted
   * NEO role, so doing it outside admin mode throws an entity-access error that would
   * abort the request. Used by {@code NeoAuthenticator} for the {@code Accept-Language}
   * header.
   *
   * @param code an Etendo language code such as {@code es_ES}
   */
  public static void applyToContext(String code) {
    boolean adminMode = false;
    try {
      OBContext.setAdminMode(true);
      adminMode = true;
      Language language = resolveActive(code);
      if (language != null) {
        OBContext.getOBContext().setLanguage(language);
      }
    } catch (Exception e) {
      log.warn("Could not apply requested language '{}': {}", code, e.getMessage());
    } finally {
      if (adminMode) {
        OBContext.restorePreviousMode();
      }
    }
  }

  /**
   * The effective language of the current NEO request — the GO locale once
   * {@code NeoAuthenticator} has applied the {@code Accept-Language} header.
   *
   * @return the current request language, or {@code null} if there is no {@link OBContext}
   */
  public static Language current() {
    OBContext ob = OBContext.getOBContext();
    return ob != null ? ob.getLanguage() : null;
  }

  /**
   * The current request language code (e.g. {@code es_ES}).
   *
   * @return the current language code, or {@code null} if there is no request language
   */
  public static String currentCode() {
    Language language = current();
    return language != null ? language.getLanguage() : null;
  }

  /**
   * Run {@code body} with the {@link OBContext} language forced to {@code lang},
   * restoring the previous language afterwards (even on exception). Use when a
   * specific path must resolve translations in a given language regardless of the
   * ambient context. A {@code null} {@code lang} runs {@code body} unchanged.
   *
   * @param lang the language to apply for the duration of {@code body}
   * @param body the work to run under {@code lang}
   * @param <T>  the result type
   * @return the value produced by {@code body}
   */
  public static <T> T withLanguage(Language lang, Supplier<T> body) {
    if (lang == null) {
      return body.get();
    }
    OBContext ob = OBContext.getOBContext();
    Language previous = ob.getLanguage();
    try {
      ob.setLanguage(lang);
      return body.get();
    } finally {
      ob.setLanguage(previous);
    }
  }
}
