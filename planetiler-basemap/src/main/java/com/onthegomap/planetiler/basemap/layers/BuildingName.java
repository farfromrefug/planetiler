/*
Copyright (c) 2021, MapTiler.com & OpenMapTiles contributors.
All rights reserved.

Code license: BSD 3-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Design license: CC-BY 4.0

See https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md for details on usage
*/
package com.onthegomap.planetiler.basemap.layers;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.basemap.generated.OpenMapTilesSchema;
import com.onthegomap.planetiler.basemap.generated.Tables;
import com.onthegomap.planetiler.basemap.util.LanguageUtils;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Translations;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildingName implements
  OpenMapTilesSchema.BuildingName,
  Tables.OsmBuildingPolygon.Handler {
  private static final Logger LOGGER = LoggerFactory.getLogger(BuildingName.class);
  private static final Set<String> FILTERED_BUILDINGS =
    Set.of("industrial", "commercial", "government", "civic", "hospital", "public", "military", "retail", "apartments", "office");
  /*
  * Generate building names from OSM data. 
  */

  private final Translations translations;

  public BuildingName(Translations translations, PlanetilerConfig config, Stats stats) {
    this.translations = translations;
    LOGGER.info("BuildingName");
  }

  @Override
  public void process(Tables.OsmBuildingPolygon element, FeatureCollector features) {
    if (element.source().hasTag("name") &&
      !element.source().hasTag("amenity") &&
      !element.source().hasTag("shop") &&
      !element.source().hasTag("tourism") &&
      !element.source().hasTag("leisure") &&
      !element.source().hasTag("aerialway") &&
      (element.source().hasTag("building") && FILTERED_BUILDINGS.contains(element.source().getString("building")))) {
      var names = LanguageUtils.getNames(element.source().tags(), translations);
      features.pointOnSurface(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
        .putAttrs(names)
        .setMinZoom(14);
    }
  }
}
