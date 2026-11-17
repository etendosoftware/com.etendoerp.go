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

package com.etendoerp.go.schemaforge;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;

import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;

/**
 * Shared fixtures for the property-path tests (ETP-6148): a mocked DAL model shaped like a
 * remittance line, and a way to build {@link NeoPropertyPaths} from outside this package (its
 * builder is package-private; the MCP tests live in {@code com.etendoerp.go.mcp}).
 *
 * <p>The model, with no real entity behind it:</p>
 * <pre>
 *   REM_RemittanceLine
 *     paymentScheduleDetail -&gt; FIN_Payment_ScheduleDetail   (reference)
 *     amount                  BigDecimal                       (value)
 *     cancels                 one-to-many                      (collection)
 *   FIN_Payment_ScheduleDetail
 *     businessPartner       -&gt; BusinessPartner              (reference, column BP-COL)
 *     amount                  BigDecimal                       (value, column AMT-COL)
 *   BusinessPartner
 *     name                    String
 *     creditUsed              BigDecimal, computed column
 * </pre>
 *
 * <p>Not a test class: a fixture shared by {@code NeoPropertyPathsTest}, {@code NeoFieldFilterTest},
 * {@code NeoCrudHandlerTest} and the MCP tests, so they all describe the same model.</p>
 */
public final class NeoPropertyPathsTestSupport {

  public static final String ROOT = "REM_RemittanceLine";
  /** API key of a reference path. */
  public static final String KEY_PARTNER = "scheduleBusinessPartner";
  public static final String PATH_PARTNER = "paymentScheduleDetail.businessPartner";
  /** API key of a value path. */
  public static final String KEY_AMOUNT = "scheduleAmount";
  public static final String PATH_AMOUNT = "paymentScheduleDetail.amount";
  /** API key of a path ending on a computed column. */
  public static final String KEY_CREDIT = "partnerCreditUsed";
  public static final String PATH_CREDIT = "paymentScheduleDetail.businessPartner.creditUsed";

  public static final String COLUMN_PARTNER = "BP-COL";
  public static final String COLUMN_AMOUNT = "AMT-COL";

  private NeoPropertyPathsTestSupport() {
  }

  /** @return the root entity of the model above */
  public static Entity remittanceLineModel() {
    Entity partner = entity("BusinessPartner");
    Property name = property("name", true, null, false, false, String.class, null);
    Property creditUsed = property("creditUsed", true, null, false, true, BigDecimal.class, null);
    declare(partner, name, creditUsed);

    Entity schedule = entity("FIN_Payment_ScheduleDetail");
    Property businessPartner = property("businessPartner", false, partner, false, false, null,
        COLUMN_PARTNER);
    Property scheduleAmount = property("amount", true, null, false, false, BigDecimal.class,
        COLUMN_AMOUNT);
    declare(schedule, businessPartner, scheduleAmount);

    Entity line = entity(ROOT);
    Property paymentScheduleDetail = property("paymentScheduleDetail", false, schedule, false,
        false, null, null);
    Property amount = property("amount", true, null, false, false, BigDecimal.class, null);
    Property cancels = property("cancels", false, schedule, true, false, null, null);
    declare(line, paymentScheduleDetail, amount, cancels);
    return line;
  }

  /**
   * @param root          the entity the paths start from
   * @param keysAndPaths  API key, path, API key, path…
   * @return the property paths, built and validated as {@code NeoFieldFilter} builds them
   */
  public static NeoPropertyPaths paths(Entity root, String... keysAndPaths) {
    NeoPropertyPaths.Builder builder = NeoPropertyPaths.builder(root);
    for (int i = 0; i + 1 < keysAndPaths.length; i += 2) {
      builder.add(keysAndPaths[i], keysAndPaths[i + 1]);
    }
    return builder.build(Collections.emptyList());
  }

  /** @return the three paths of the model: partner, amount and the computed credit */
  public static NeoPropertyPaths allPaths() {
    return paths(remittanceLineModel(), KEY_PARTNER, PATH_PARTNER, KEY_AMOUNT, PATH_AMOUNT,
        KEY_CREDIT, PATH_CREDIT);
  }

  private static Entity entity(String name) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(name);
    return entity;
  }

  private static void declare(Entity entity, Property... properties) {
    for (Property property : properties) {
      when(entity.hasProperty(property.getName())).thenReturn(true);
      when(entity.getProperty(property.getName())).thenReturn(property);
    }
  }

  private static Property property(String name, boolean primitive, Entity target,
      boolean oneToMany, boolean computed, Class<?> type, String columnId) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(name);
    when(property.isPrimitive()).thenReturn(primitive);
    when(property.getTargetEntity()).thenReturn(target);
    when(property.isOneToMany()).thenReturn(oneToMany);
    when(property.isComputedColumn()).thenReturn(computed);
    when(property.getColumnId()).thenReturn(columnId);
    if (type != null) {
      doReturn(type).when(property).getPrimitiveObjectType();
    }
    return property;
  }
}
