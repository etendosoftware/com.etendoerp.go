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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.geography.Region;

/**
 * Unit tests for {@link NeoAddressHelper}.
 */
class NeoAddressHelperTest {

  @Nested
  @DisplayName("formatCityLine")
  class FormatCityLine {

    @Test
    @DisplayName("Returns null for null location")
    void nullLocation() {
      assertNull(NeoAddressHelper.formatCityLine(null));
    }

    @Test
    @DisplayName("Returns null when all components are blank")
    void allBlank() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn(null);
      when(loc.getCityName()).thenReturn(null);
      when(loc.getRegion()).thenReturn(null);

      assertNull(NeoAddressHelper.formatCityLine(loc));
    }

    @Test
    @DisplayName("Returns postal only")
    void postalOnly() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn("08009");
      when(loc.getCityName()).thenReturn(null);
      when(loc.getRegion()).thenReturn(null);

      assertEquals("08009", NeoAddressHelper.formatCityLine(loc));
    }

    @Test
    @DisplayName("Returns city only")
    void cityOnly() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn(null);
      when(loc.getCityName()).thenReturn("Barcelona");
      when(loc.getRegion()).thenReturn(null);

      assertEquals("Barcelona", NeoAddressHelper.formatCityLine(loc));
    }

    @Test
    @DisplayName("Returns region only in parentheses")
    void regionOnly() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn(null);
      when(loc.getCityName()).thenReturn(null);
      Region region = mock(Region.class);
      when(region.getName()).thenReturn("CATALUÑA");
      when(loc.getRegion()).thenReturn(region);

      assertEquals("(CATALUÑA)", NeoAddressHelper.formatCityLine(loc));
    }

    @Test
    @DisplayName("Formats postal + city")
    void postalAndCity() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn("08009");
      when(loc.getCityName()).thenReturn("Barcelona");
      when(loc.getRegion()).thenReturn(null);

      assertEquals("08009 - Barcelona", NeoAddressHelper.formatCityLine(loc));
    }

    @Test
    @DisplayName("Formats city + region")
    void cityAndRegion() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn(null);
      when(loc.getCityName()).thenReturn("Barcelona");
      Region region = mock(Region.class);
      when(region.getName()).thenReturn("BARCELONA");
      when(loc.getRegion()).thenReturn(region);

      assertEquals("Barcelona (BARCELONA)", NeoAddressHelper.formatCityLine(loc));
    }

    @Test
    @DisplayName("Formats full: postal + city + region")
    void fullFormat() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn("08009");
      when(loc.getCityName()).thenReturn("Barcelona");
      Region region = mock(Region.class);
      when(region.getName()).thenReturn("BARCELONA");
      when(loc.getRegion()).thenReturn(region);

      assertEquals("08009 - Barcelona (BARCELONA)", NeoAddressHelper.formatCityLine(loc));
    }

    @Test
    @DisplayName("Formats postal + region (no city)")
    void postalAndRegion() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn("28001");
      when(loc.getCityName()).thenReturn(null);
      Region region = mock(Region.class);
      when(region.getName()).thenReturn("MADRID");
      when(loc.getRegion()).thenReturn(region);

      assertEquals("28001 - (MADRID)", NeoAddressHelper.formatCityLine(loc));
    }

    @Test
    @DisplayName("Handles empty strings as blank")
    void emptyStrings() {
      Location loc = mock(Location.class);
      when(loc.getPostalCode()).thenReturn("");
      when(loc.getCityName()).thenReturn("  ");
      when(loc.getRegion()).thenReturn(null);

      assertNull(NeoAddressHelper.formatCityLine(loc));
    }
  }
}