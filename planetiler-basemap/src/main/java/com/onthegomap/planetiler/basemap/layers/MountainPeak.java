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

import static com.onthegomap.planetiler.basemap.util.Utils.elevationTags;
import static com.onthegomap.planetiler.basemap.util.Utils.nullIfEmpty;
import static com.onthegomap.planetiler.basemap.util.Utils.nullIfInt;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.basemap.BasemapProfile;
import com.onthegomap.planetiler.basemap.generated.OpenMapTilesSchema;
import com.onthegomap.planetiler.basemap.generated.Tables;
import com.onthegomap.planetiler.basemap.util.LanguageUtils;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.geo.GeometryType;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Parse;
import com.onthegomap.planetiler.util.Translations;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defines the logic for generating map elements for mountain peak label points in the {@code mountain_peak} layer from
 * source features.
 * <p>
 * This class is ported to Java from
 * <a href="https://github.com/openmaptiles/openmaptiles/tree/master/layers/mountain_peak">OpenMapTiles mountain_peak
 * sql files</a>.
 */
public class MountainPeak implements
  BasemapProfile.NaturalEarthProcessor,
  OpenMapTilesSchema.MountainPeak,
  Tables.OsmPeakPoint.Handler,
  Tables.OsmMountainLinestring.Handler,
  BasemapProfile.FeaturePostProcessor {

  static final double POINT_BUFFER_SIZE = 8.0;

  /*
   * Mountain peaks come from OpenStreetMap data and are ranked by importance (based on if they
   * have a name or wikipedia page) then by elevation.  Uses the "label grid" feature to limit
   * label density by only taking the top 5 most important mountain peaks within each 100x100px
   * square.
   */
  private static final Logger LOGGER = LoggerFactory.getLogger(MountainPeak.class);

  private final Translations translations;
  private final Stats stats;
  private final PlanetilerConfig config;
  // keep track of areas that prefer feet to meters to set customary_ft=1 (just U.S.)
  private PreparedGeometry unitedStates = null;
  // private final AtomicBoolean loggedNoUS = new AtomicBoolean(false);

  private double RADIUS_DISTANCE_PX;
  private double RADIUS_VERY_CLOSE_DISTANCE_PX;
  private double MAX_RANK;

  public MountainPeak(Translations translations, PlanetilerConfig config, Stats stats) {
    this.translations = translations;
    this.stats = stats;
    this.config = config;

    RADIUS_DISTANCE_PX = config.arguments().getDouble(
      "mountain_peak_radius",
      "mountain_peak layer: radius for clustering in meters",
      30
    );
    RADIUS_VERY_CLOSE_DISTANCE_PX = config.arguments().getDouble(
      "mountain_peak_radius_close",
      "mountain_peak layer: close radius for clustering in meters",
      8
    );
    MAX_RANK = config.arguments().getDouble(
      "mountain_peak_max_rank",
      "mountain_peak layer: close radius for clustering in meters",
      2
    );
  }

  @Override
  public void processNaturalEarth(String table, SourceFeature feature, FeatureCollector features) {
    if ("ne_10m_admin_0_countries".equals(table) && feature.hasTag("iso_a2", "US")) {
      // multiple threads call this method concurrently, US polygon *should* only be found
      // once, but just to be safe synchronize updates to that field
      synchronized (this) {
        try {
          Geometry boundary = feature.polygon();
          unitedStates = PreparedGeometryFactory.prepare(boundary);
        } catch (GeometryException e) {
          LOGGER.error("Failed to get United States Polygon for mountain_peak layer: " + e);
        }
      }
    }
  }

  @Override
  public void process(Tables.OsmPeakPoint element, FeatureCollector features) {
    Double meters = Parse.meters(element.ele());
    if (element.source().hasTag("name") || (meters != null && Math.abs(meters) < 10_000)) {
      var natural = element.source().getTag("natural");
      var metersInt = meters != null ? meters.intValue() : 0;
      var metersThousandRounded = Math.round(metersInt / 1000);
      var minzoom = Math.max(6, 10 - metersThousandRounded);
      features.point(LAYER_NAME)
        .setAttr(Fields.CLASS, natural)
        // .setAttr("wikipedia", nullIfEmpty(element.wikipedia()))
        // .setAttr("wikidata", nullIfEmpty((String)element.source().getTag("wikidata")))
        .setAttr("summitcross", nullIfInt(element.summitcross() ? 1 : 0, 0))
        .putAttrs(LanguageUtils.getNames(element.source().tags(), translations))
        .putAttrs(elevationTags(meters))
        .setSortKeyDescending(
          metersInt + ("peak".equals(natural) ? 10_000 : 0) + (nullIfEmpty(element.name()) != null ? 10_000 : 0)
        )
        .setMinZoom(minzoom)
        .setBufferPixels(BUFFER_SIZE);

      // if (peakInAreaUsingFeet(element)) {
      //   feature.setAttr(Fields.CUSTOMARY_FT, 1);
      // }
    }
  }

  @Override
  public void process(Tables.OsmMountainLinestring element, FeatureCollector features) {
    features.line(LAYER_NAME)
      .setAttr(Fields.CLASS, element.source().getTag("natural"))
      .putAttrs(LanguageUtils.getNames(element.source().tags(), translations))
      .setMinZoom(13)
      .setBufferPixels(BUFFER_SIZE);
  }

  /** Returns true if {@code element} is a point in an area where feet are used insead of meters (the US). */
  // private boolean peakInAreaUsingFeet(Tables.OsmPeakPoint element) {
  //   if (unitedStates == null) {
  //     if (!loggedNoUS.get() && loggedNoUS.compareAndSet(false, true)) {
  //       LOGGER.warn("No US polygon for inferring mountain_peak customary_ft tag");
  //     }
  //   } else {
  //     try {
  //       Geometry wayGeometry = element.source().worldGeometry();
  //       return unitedStates.intersects(wayGeometry);
  //     } catch (GeometryException e) {
  //       e.log(stats, "omt_mountain_peak_us_test",
  //         "Unable to test mountain_peak against US polygon: " + element.source().id());
  //     }
  //   }
  //   return false;
  // }

  private record GeomWithData<T> (Coordinate coord, T data) {}

  public <T> List<T> filter(Predicate<T> criteria, List<T> list) {
    return list.stream().filter(criteria).collect(Collectors.<T>toList());
  }

  public <T> List<T> getPointsWithin(Point point, double threshold, List<T> items) {
    List<T> result = new ArrayList<>(items.size());
    Coordinate coord = point.getCoordinate();
    // then post-filter by circular radius
    for (Object item : items) {
      if (item instanceof GeomWithData<?> value) {
        double distance = value.coord.distance(coord);
        if (distance <= threshold) {
          result.add((T) item);
        }
      }
    }
    return result;
  }

  @Override
  public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items) {
    // LongIntMap groupCounts = Hppc.newLongIntHashMap();
    // List<VectorTile.Feature> newItems = filter(feature -> feature.geometry().geomType() != GeometryType.POINT, items);

    // var cluster =
    //   new DBScanCluster(filter(feature -> feature.geometry().geomType() == GeometryType.POINT && insideTileBuffer(feature), items), eps,  3, zoom);
    // var result = cluster.dbScanCluster();
    // for (VectorTile.Feature feature : result.noisePoints()) {
    //   if (!feature.attrs().containsKey(Fields.RANK)) {
    //     feature.attrs().put(Fields.RANK, 1);
    //   }
    //   newItems.add(feature);
    // }
    // for (List<VectorTile.Feature> features : result.clusters()) {
    //   features.sort(Comparator.comparingDouble(f -> {
    //     Long meters = (Long) (f.attrs().get("ele"));
    //     return 400_000 -((meters != null ? meters.longValue() : 0) +
    //       ("peak".equals(f.attrs().get(Fields.CLASS)) ? 10_000 : 0) +
    //       (nullIfEmpty((String) f.attrs().get("name")) != null ? 10_000 : 0));
    //   }));
    //   int index = 1;
    //   for (VectorTile.Feature feature : features.subList(0,3)) {
    //     if (!feature.attrs().containsKey(Fields.RANK)) {
    //       feature.attrs().put(Fields.RANK, index++);
    //     }
    //     newItems.add(feature);
    //   }
    // }

    List<GeomWithData> peaks = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      VectorTile.Feature feature = items.get(i);
      if (!insideTileBuffer(feature)) {
        items.set(i, null);
      } else if (feature.geometry().geomType() == GeometryType.POINT) {
        try {
          var geometry = feature.geometry().decode();
          // closePeaks used to define ranks
          var closePeaks = getPointsWithin(geometry.getCentroid(), RADIUS_DISTANCE_PX, peaks);
          // veryClosePeaks allow to remove very close points in tile which will almost never be drawn
          // because of ranking and overlaping
          var veryClosePeaks = getPointsWithin(geometry.getCentroid(), RADIUS_VERY_CLOSE_DISTANCE_PX, closePeaks);
          var count = closePeaks.size();
          if (veryClosePeaks.size() > 0 || count >= MAX_RANK) {
            items.set(i, null);
          } else {
            feature.attrs().put(Fields.RANK, count + 1);
            peaks.add(new GeomWithData<>(geometry.getCoordinate(), feature));
          }
        } catch (GeometryException e) {
          e.printStackTrace();
        }
      }
    }
    return items;
  }

  private static boolean insideTileBuffer(double xOrY) {
    return xOrY >= -POINT_BUFFER_SIZE && xOrY <= 256 + POINT_BUFFER_SIZE;
  }

  private boolean insideTileBuffer(VectorTile.Feature feature) {
    try {
      Geometry geom = feature.geometry().decode();
      return !(geom instanceof Point point) || (insideTileBuffer(point.getX()) && insideTileBuffer(point.getY()));
    } catch (GeometryException e) {
      e.log(stats, "mountain_peak_decode_point", "Error decoding mountain peak point: " + feature.attrs());
      return false;
    }
  }
}
