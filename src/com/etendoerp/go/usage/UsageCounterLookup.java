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

package com.etendoerp.go.usage;

import java.util.Set;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.weld.WeldUtils;

/**
 * Resolves the {@link UsageResourceCounter} whose {@code @Named} value equals a catalog
 * row's {@code Strategy_Qualifier}.
 *
 * <p>Matching is on {@link Bean#getName()}, the CDI-standard way to read {@code @Named},
 * mirroring {@code NeoHandlerLookup}. Reading the annotation off the concrete class instead
 * would silently miss any normal-scoped bean, because Weld's client proxy is a subclass and
 * {@code @Named} is not {@code @Inherited}.
 */
public final class UsageCounterLookup {

  private static final Logger log = LogManager.getLogger(UsageCounterLookup.class);

  private UsageCounterLookup() {
  }

  /**
   * Resolves the deployed counter bean carrying the given CDI qualifier.
   *
   * @param qualifier the {@code Strategy_Qualifier} to match; blank yields null
   * @return the matching counter, or null when none is deployed
   */
  public static UsageResourceCounter byQualifier(String qualifier) {
    if (StringUtils.isBlank(qualifier)) {
      return null;
    }
    BeanManager bm = WeldUtils.getStaticInstanceBeanManager();
    Set<Bean<?>> beans = bm.getBeans(UsageResourceCounter.class, WeldUtils.ANY_LITERAL);
    for (Bean<?> bean : beans) {
      if (qualifier.equals(bean.getName())) {
        return (UsageResourceCounter) bm.getReference(bean, UsageResourceCounter.class,
            bm.createCreationalContext(bean));
      }
    }
    return null;
  }

  /**
   * Whether a counter is deployed for the qualifier, without instantiating it. Used by
   * save-time validation so a typo in {@code Strategy_Qualifier} is caught when the catalog
   * row is saved rather than at 02:00 by a job that silently counts nothing.
   *
   * @param qualifier the {@code Strategy_Qualifier} to look for
   * @return true when some deployed bean carries {@code @Named(qualifier)}
   */
  public static boolean isDeployed(String qualifier) {
    if (StringUtils.isBlank(qualifier)) {
      return false;
    }
    BeanManager bm = WeldUtils.getStaticInstanceBeanManager();
    for (Bean<?> bean : bm.getBeans(UsageResourceCounter.class, WeldUtils.ANY_LITERAL)) {
      if (qualifier.equals(bean.getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Lists the qualifiers actually deployed.
   *
   * @return every deployed qualifier, for an error message that lists the real options
   */
  public static String deployedQualifiers() {
    try {
      BeanManager bm = WeldUtils.getStaticInstanceBeanManager();
      StringBuilder sb = new StringBuilder();
      for (Bean<?> bean : bm.getBeans(UsageResourceCounter.class, WeldUtils.ANY_LITERAL)) {
        if (bean.getName() != null) {
          if (sb.length() > 0) {
            sb.append(", ");
          }
          sb.append(bean.getName());
        }
      }
      return sb.length() == 0 ? "(none deployed)" : sb.toString();
    } catch (Exception e) {
      log.debug("Could not list deployed usage counters: {}", e.getMessage());
      return "(unavailable)";
    }
  }
}
