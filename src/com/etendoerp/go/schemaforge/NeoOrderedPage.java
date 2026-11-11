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
 * One page of a REST list in a server-computed order: the record ids of the page in the order to
 * serve, and the size of the whole filtered set. See {@link NeoHandler#orderedPage}.
 */
public final class NeoOrderedPage {

  private final List<String> ids;
  private final int totalRows;

  /**
   * @param ids the record ids of the page, in the order to serve
   * @param totalRows number of records of the whole filtered set
   */
  public NeoOrderedPage(List<String> ids, int totalRows) {
    this.ids = ids == null ? Collections.emptyList() : Collections.unmodifiableList(ids);
    this.totalRows = totalRows;
  }

  public List<String> getIds() {
    return ids;
  }

  public int getTotalRows() {
    return totalRows;
  }
}
