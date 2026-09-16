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

/** What one aggregation run did, for the process result message and the log. */
public final class UsageAggregationResult {

  private int daysProcessed;
  private int resourcesProcessed;
  private int rowsWritten;
  private int resourcesFailed;

  public void addDay() {
    daysProcessed++;
  }

  public void addResource() {
    resourcesProcessed++;
  }

  public void addRows(int rows) {
    rowsWritten += rows;
  }

  public void addFailure() {
    resourcesFailed++;
  }

  public int getDaysProcessed() {
    return daysProcessed;
  }

  public int getResourcesProcessed() {
    return resourcesProcessed;
  }

  public int getRowsWritten() {
    return rowsWritten;
  }

  public int getResourcesFailed() {
    return resourcesFailed;
  }

  @Override
  public String toString() {
    return "Processed " + daysProcessed + " day(s) over " + resourcesProcessed
        + " resource-day(s), wrote " + rowsWritten + " usage row(s)"
        + (resourcesFailed > 0 ? ", " + resourcesFailed + " resource-day(s) failed" : "");
  }
}
