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

import static com.onthegomap.planetiler.basemap.util.Utils.coalesce;
import static com.onthegomap.planetiler.basemap.util.Utils.nullIfEmpty;
import static com.onthegomap.planetiler.basemap.util.Utils.nullIfLong;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.basemap.BasemapProfile;
import com.onthegomap.planetiler.basemap.generated.OpenMapTilesSchema;
import com.onthegomap.planetiler.basemap.generated.Tables;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.GeoUtils;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmReader;
import com.onthegomap.planetiler.reader.osm.OsmRelationInfo;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Parse;
import com.onthegomap.planetiler.util.Translations;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Route implements
    OpenMapTilesSchema.Route,
    Tables.OsmRouteLinestring.Handler,
    BasemapProfile.FeaturePostProcessor,
    BasemapProfile.OsmRelationPreprocessor,
    BasemapProfile.OsmAllProcessor {

    class RouteRelationData {
        Double computedDistance = 0.0;
        Envelope envelope = new Envelope();
        long id;

        RouteRelationData(
        ) {}
    }
    /*
     * Generates the shape for roads, trails, ferries, railways with detailed
     * attributes for rendering, but not any names.  The transportation_name
     * layer includes names, but less detailed attributes.
     */

    private static final Logger LOGGER = LoggerFactory.getLogger(Route.class);

    private final Stats stats;
    private final PlanetilerConfig config;
    private final HashMap<Long, RouteRelationData> routeRelationDatas = new HashMap<>();

    public Route(Translations translations, PlanetilerConfig config, Stats stats) {
        this.config = config;
        this.stats = stats;
    }

    private Integer getNetworkType(String network) {
        return switch (coalesce(network, "")) {
            case "iwn", "icn" -> 1;
            case "nwn", "ncn" -> 2;
            case "rwn", "rcn" -> 3;
            default -> 4;
        };
    }

    @Override
    public List<OsmRelationInfo> preprocessOsmRelation(OsmElement.Relation relation) {
        if (relation.hasTag("type", "route", "superroute") && relation.hasTag("route", "bicycle", "hiking", "foot")) {
            String network = relation.getString("network");
            String type = relation.getString("type");
            Integer networkType = getNetworkType(network);
            String name =
                coalesce(nullIfEmpty(relation.getString("name")), nullIfEmpty(relation.getString("alt_name")));
            String ref = coalesce(nullIfEmpty(relation.getString("ref")), nullIfEmpty(relation.getString("osmc:ref")));
            // if (ref == null && name != null) {
            //     //TRIM(BOTH '-–' from REGEXP_REPLACE(SPLIT_PART(SPLIT_PART(name, ':', 1), ',', 1), '([^A-Z0-9\:\,\-–]+)|(\M-\m)', '', 'g'))
            //     ref = (name.split(":")[0].split(",")[0]).replaceAll("([^A-Z0-9\\:\\,\\-–]+)|(\\M-\\m)", "").replaceAll("--", "");
            // }
            // if (relation.hasTag("type", "superroute")) {
            //     LOGGER.warn("superroute: " + name + " " + ref + " " + networkType + " " + relation.id());
            // }
            return List.of(new RouteRelation(
                type,
                name,
                relation.getString("route"),
                ref,
                networkType,
                Parse.meters(relation.getString("ascent")),
                Parse.meters(relation.getString("descent")),
                Parse.meters(relation.getString("distance")),
                relation.getString("osmc:symbol"),
                relation.id()));
        }
        return null;
    }

    @Override
    public void processAllOsm(SourceFeature feature, FeatureCollector features) {
        List<OsmReader.RelationMember<RouteRelation>> routes = feature.relationInfo(RouteRelation.class);
        if (routes != null && !routes.isEmpty() && feature.canBeLine()) {
            for (var route : routes) {
                var relation = route.relation();
                // if (relation.type.equals("superroute")) {
                //     LOGGER.warn("processAllOsm superroute: " + relation.name + " " + relation.id());
                // }
                long relId = relation.id();
                RouteRelationData routeRelationData;
                if (!routeRelationDatas.containsKey(relId)) {
                    routeRelationData = new RouteRelationData();
                    routeRelationDatas.put(relId, routeRelationData);
                } else {
                    routeRelationData = routeRelationDatas.get(relId);

                }
                try {
                    if (relation.distance == null) {
                        routeRelationData.computedDistance += feature.length() * 40075 / 2.0;
                    }
                    routeRelationData.envelope.expandToInclude(feature.envelope());
                } catch (GeometryException e) {
                    e.log(stats, "route_decode", "Unable to get route length for " + feature.id());
                }
                String name = relation.name();
                int networkType = relation.networkType();
                int minzoom = getMinzoom(networkType, name != null);
                // if (relation.type.equals("superroute")) {
                // if (networkType == 1) {
                //     LOGGER.warn("processAllOsm route: " + name);
                // }
                features.line(LAYER_NAME)
                    .setBufferPixels(BUFFER_SIZE)
                    .setAttr("ref", relation.ref())
                    .setAttr("osmid", relId)
                    .setAttr("network", networkType)
                    .setAttr("ascent", relation.ascent() != null ? nullIfLong(Math.round(relation.ascent()), 0) : null)
                    .setAttr("descent",
                        relation.descent() != null ? nullIfLong(Math.round(relation.descent()), 0) : null)
                    .setMinZoom(minzoom)
                    .setAttr("distance",
                        relation.distance() != null ? nullIfLong(Math.round(relation.distance()), 0) : null)
                    .setAttr("symbol", nullIfEmpty(relation.symbol()))
                    .setAttr(Fields.CLASS, relation.route())
                    .setAttr("name", name)
                    .setSortKey(feature.getWayZorder())
                    .setMinPixelSize(0);
            }
        }
    }


    List<RouteRelation> getRouteRelations(Tables.OsmRouteLinestring element) {
        // String ref = element.ref();
        List<OsmReader.RelationMember<RouteRelation>> relations = element.source().relationInfo(RouteRelation.class);
        List<RouteRelation> result = new ArrayList<>(relations.size() + 1);
        for (var relationMember : relations) {
            var relation = relationMember.relation();
            // avoid duplicates - list should be very small and usually only one
            if (!result.contains(relation)) {
                result.add(relation);
            }
        }
        return result;
    }

    RouteRelation getRouteRelation(Tables.OsmRouteLinestring element) {
        List<RouteRelation> all = getRouteRelations(element);
        return all.isEmpty() ? null : all.get(0);
    }

    @Override
    public void process(Tables.OsmRouteLinestring element, FeatureCollector features) {
        // List<RouteRelation> relations = getRouteRelations(element);
        // List<OsmReader.RelationMember<RouteRelation>> routes = feature.relationInfo(RouteRelation.class);
        // if (routes != null && !routes.isEmpty() && feature.canBeLine()) {
        // for (var relation : relations) {
        //     // var relation = route.relation();
        //     // if (relation.type.equals("superroute")) {
        //     //     LOGGER.warn("processAllOsm superroute: " + relation.name + " " + relation.id());
        //     // }
        //     long relId = relation.id();
        //     RouteRelationData routeRelationData;
        //     if (!routeRelationDatas.containsKey(relId)) {
        //         routeRelationData = new RouteRelationData();
        //         routeRelationDatas.put(relId, routeRelationData);
        //     } else {
        //         routeRelationData = routeRelationDatas.get(relId);

        //     }
        //     try {
        //         if (relation.distance == null) {
        //             routeRelationData.computedDistance += element.source().length() * 40075 / 2.0;
        //         }
        //         routeRelationData.envelope.expandToInclude(element.source().envelope());
        //     } catch (GeometryException e) {
        //         e.log(stats, "route_decode", "Unable to get route length for " + element.source().id());
        //     }
        //     String name = relation.name();
        //     int networkType = relation.networkType();
        //     int minzoom = getMinzoom(networkType, name != null);
        //     // if (relation.type.equals("superroute")) {
        //     // if (networkType == 1) {
        //     //     LOGGER.warn("processAllOsm route: " + name);
        //     // }
        //     features.line(LAYER_NAME)
        //         .setBufferPixels(BUFFER_SIZE)
        //         .setAttr("ref", relation.ref())
        //         .setAttr("osmid", relId)
        //         .setAttr("network", networkType)
        //         .setAttr("ascent", relation.ascent() != null ? nullIfLong(Math.round(relation.ascent()), 0) : null)
        //         .setAttr("descent",
        //             relation.descent() != null ? nullIfLong(Math.round(relation.descent()), 0) : null)
        //         .setMinZoom(minzoom)
        //         .setAttr("distance",
        //             relation.distance() != null ? nullIfLong(Math.round(relation.distance()), 0) : null)
        //         .setAttr("symbol", nullIfEmpty(relation.symbol()))
        //         .setAttr(Fields.CLASS, relation.route())
        //         .setAttr("name", name)
        //         .setSortKey(element.zOrder())
        //         .setMinPixelSize(0);
        //     // }
        // }
        // String network = element.source().getString("network");
        // String ref = element.source().getString("ref");
        // String name = coalesce(nullIfEmpty(element.source().getString("name")),
        //     nullIfEmpty(element.source().getString("alt_name")));
        // Integer networkType = getNetworkType(network);
        // int minzoom = getMinzoom(networkType, name != null);
        // LOGGER.warn("process route without relation: " + name + " " + element.source().id());
        // features.line(LAYER_NAME)
        //     .setBufferPixels(BUFFER_SIZE)
        //     .setAttr(Fields.CLASS, element.route())
        //     .setAttr("ref", ref)
        //     .setAttr("name", name)
        //     .setMinZoom(minzoom)
        //     // .setPixelToleranceFactor(0.8)
        //     // details only at higher zoom levels so that named rivers can be merged more aggressively
        //     // at lower zoom levels, we'll merge linestrings and limit length/clip afterwards
        //     .setBufferPixelOverrides(MIN_PIXEL_LENGTHS)
        //     .setMinPixelSizeBelowZoom(11, 0);
    }

    int getMinzoom(Integer networkType, boolean hasName) {
        int minzoom = switch (networkType) {
            case 1 -> hasName ? 5 : 6;
            case 2 -> 8;
            case 3 -> 9;
            default -> 10;
        };

        return minzoom;
    }


    @Override
    public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items) {

        for (int i = 0; i < items.size(); i++) {
            var attrs = items.get(i).attrs();
            Long relId = (Long) attrs.get("osmid");
            var routeRelationData = routeRelationDatas.get(relId);
            if (routeRelationData != null) {
                var latLngBounds = GeoUtils.toLatLonBoundsBounds(routeRelationData.envelope);
                DecimalFormat df = new DecimalFormat("#.000");
                String extent = df.format(latLngBounds.getMinX()) + "," +
                    df.format(latLngBounds.getMinY()) + "," + df.format(latLngBounds.getMaxX()) +
                    "," + df.format(latLngBounds.getMaxY());
                attrs.put("extent", extent);
                if (attrs.get("distance") == null) {
                    attrs.put("distance", nullIfLong(Math.round(routeRelationData.computedDistance), 0));
                }
            }
        }
        double minLength = config.minFeatureSize(zoom) / 2.0;
        double tolerance = config.tolerance(zoom) * 0.5;
        // double tolerance = zoom== 7 ? 100 : config.tolerance(zoom);
        // if (zoom==6) {
        //     tolerance = 2000;
        //     LOGGER.warn("route merging " + zoom + " " + tolerance + " " + items.size());

        // }
        items = FeatureMerge.mergeLineStrings(items, attrs -> 0.0, tolerance, BUFFER_SIZE);
        // if (zoom==6) {
        //     LOGGER.warn("route merging done " + zoom + " " + tolerance + " " + items.size() + " " + items.get(0).attrs().get("name"));

        // }
        return items;
    }

    /** Information extracted from route relations to use when processing ways in that relation. */
    record RouteRelation(
        String type,
        String name,
        String route,
        String ref,
        Integer networkType,
        Double ascent,
        Double descent,
        Double distance,
        String symbol,
        @Override long id
    ) implements OsmRelationInfo {}
}
