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
package com.etendoerp.go.onboarding;

import java.util.HashMap;
import java.util.Map;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Language;

/**
 * Builds the default, DAL-backed {@link OnboardingDatasetNormalizer.EntityResolver} and
 * {@link OnboardingDatasetNormalizer.ReferenceIdResolver} implementations used when the caller
 * does not supply its own (e.g. for tests).
 */
final class OnboardingDefaultResolvers {

  private OnboardingDefaultResolvers() {
    // Utility class.
  }

  static OnboardingDatasetNormalizer.EntityResolver modelProviderEntityResolver() {
    return tableName -> ModelProvider.getInstance().getEntityByTableName(tableName);
  }

  /**
   * DAL-backed reference resolver. Language codes are resolved to their installed
   * {@code AD_Language} DAL id (cached per build); all other references pass through unchanged.
   */
  static OnboardingDatasetNormalizer.ReferenceIdResolver dalReferenceIdResolver() {
    Map<String, String> languageIdByCode = new HashMap<>();
    return (targetEntityName, rawValue) -> {
      if (!Language.ENTITY_NAME.equals(targetEntityName)) {
        return rawValue;
      }
      return languageIdByCode.computeIfAbsent(rawValue,
          OnboardingDefaultResolvers::resolveInstalledLanguageId);
    };
  }

  private static String resolveInstalledLanguageId(String languageCode) {
    OBCriteria<Language> criteria = OBDal.getInstance().createCriteria(Language.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Language.PROPERTY_LANGUAGE, languageCode));
    criteria.setMaxResults(1);
    Language language = (Language) criteria.uniqueResult();
    if (language == null) {
      throw new OBException("Onboarding dataset references language '" + languageCode
          + "' which is not installed in this Etendo instance");
    }
    return language.getId();
  }
}
