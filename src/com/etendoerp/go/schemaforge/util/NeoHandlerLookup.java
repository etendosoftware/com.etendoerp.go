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

package com.etendoerp.go.schemaforge.util;

import java.util.Set;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.weld.WeldUtils;

import com.etendoerp.go.schemaforge.NeoHandler;

/**
 * Resolves a {@link NeoHandler} CDI bean from the {@code Java_Qualifier} declared on an
 * {@code ETGO_SF_ENTITY} row.
 *
 * <p>Extracted from {@code McpHookExecutor} (ETP-4793 / IMP-19) so code outside the {@code mcp}
 * package can ask the handler itself what it supports, instead of inferring it from the
 * configuration. {@link NeoReportCallability} needs exactly that: it decides whether a report
 * spec is callable, and the honest answer lives on the handler
 * ({@code NeoHandler#reportParameters()}), not in the presence of a qualifier string.</p>
 *
 * <p>Matching is on {@link Bean#getName()} — the CDI-standard way to read the {@code @Named}
 * value. Reading the annotation off {@code handler.getClass()} instead silently misses any
 * normal-scoped bean, because Weld's client proxy is a subclass and {@code @Named} is not
 * {@code @Inherited}.</p>
 */
public final class NeoHandlerLookup {

  private static final Logger log = LogManager.getLogger(NeoHandlerLookup.class);

  private NeoHandlerLookup() {
  }

  /**
   * Resolve the deployed {@link NeoHandler} whose {@code @Named} value equals the qualifier.
   *
   * @param qualifier the {@code Java_Qualifier} to match; blank or {@code null} yields
   *                  {@code null}
   * @return the matching handler, or {@code null} when none is deployed
   */
  public static NeoHandler byQualifier(String qualifier) {
    if (StringUtils.isBlank(qualifier)) {
      return null;
    }
    BeanManager bm = WeldUtils.getStaticInstanceBeanManager();
    Set<Bean<?>> beans = bm.getBeans(NeoHandler.class, WeldUtils.ANY_LITERAL);
    for (Bean<?> bean : beans) {
      if (qualifier.equals(bean.getName())) {
        return (NeoHandler) bm.getReference(bean, NeoHandler.class,
            bm.createCreationalContext(bean));
      }
    }
    return null;
  }

  /**
   * Same as {@link #byQualifier(String)} but never propagates a lookup failure.
   *
   * <p>For callers on a discovery path — building the tool catalog, answering
   * {@code neo_discover} — where a CDI environment that is absent or not yet started must
   * degrade to "no handler" rather than fail the whole listing.</p>
   *
   * @param qualifier the {@code Java_Qualifier} to match
   * @return the matching handler, or {@code null} when none is deployed or the lookup failed
   */
  public static NeoHandler byQualifierQuietly(String qualifier) {
    try {
      return byQualifier(qualifier);
    } catch (Exception e) {
      log.debug("NeoHandler lookup failed for qualifier '{}': {}", qualifier, e.getMessage());
      return null;
    }
  }
}
