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

import java.util.Collections;
import java.util.List;

/**
 * One page of a REST list read whose ordering and paging an entity customization took over
 * ({@link NeoHandler#listPage}), for orderings core's query builder cannot express (ETP-5977 POC: sort
 * Product by an attribute value, which lives in a child table).
 *
 * <p>The customization decides which records are on the page and in what order; NEO still reads the
 * rows themselves through core, so field filtering and identifiers are the ones of the normal list.
 */
public final class NeoListPage {

  private final List<String> ids;
  private final long totalRows;

  /**
   * @param ids       the record ids of the page, in the order to return them
   * @param totalRows the exact total of the filtered list, or {@code -1} when it was not computed
   */
  public NeoListPage(List<String> ids, long totalRows) {
    this.ids = Collections.unmodifiableList(ids);
    this.totalRows = totalRows;
  }

  public List<String> getIds() {
    return ids;
  }

  public long getTotalRows() {
    return totalRows;
  }
}
