/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package org.opentripplanner.api.ws;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.annotation.XmlRootElement;

import lombok.Setter;

import org.codehaus.jettison.json.JSONException;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.model.ServiceCalendar;
import org.onebusaway.gtfs.model.ServiceCalendarDate;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.Trip;
import org.opentripplanner.api.model.error.TransitError;
import org.opentripplanner.api.model.transit.AgencyList;
import org.opentripplanner.api.model.transit.CalendarData;
import org.opentripplanner.api.model.transit.ModeList;
import org.opentripplanner.api.model.transit.RouteData;
import org.opentripplanner.api.model.transit.RouteDataList;
import org.opentripplanner.api.model.transit.RouteList;
import org.opentripplanner.api.model.transit.ServiceCalendarData;
import org.opentripplanner.api.model.transit.StopList;
import org.opentripplanner.api.model.transit.StopTime;
import org.opentripplanner.api.model.transit.StopTimeList;
import org.opentripplanner.api.model.transit.TripList;
import org.opentripplanner.api.model.transit.TripMatch;
import org.opentripplanner.common.geometry.DistanceLibrary;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.ServiceDay;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.PatternHop;
import org.opentripplanner.routing.edgetype.TableTripPattern;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.services.GraphService;
import org.opentripplanner.routing.services.StreetVertexIndexService;
import org.opentripplanner.routing.services.TransitIndexService;
import org.opentripplanner.routing.transit_index.RouteSegment;
import org.opentripplanner.routing.transit_index.RouteVariant;
import org.opentripplanner.routing.transit_index.adapters.RouteType;
import org.opentripplanner.routing.transit_index.adapters.ServiceCalendarDateType;
import org.opentripplanner.routing.transit_index.adapters.ServiceCalendarType;
import org.opentripplanner.routing.transit_index.adapters.StopType;
import org.opentripplanner.routing.transit_index.adapters.TripType;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.opentripplanner.routing.vertextype.TransitStopArrive;
import org.opentripplanner.routing.vertextype.TransitStopDepart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.core.InjectParam;
import com.sun.jersey.api.spring.Autowire;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.LineString;

// NOTE - /ws/transit is the full path -- see web.xml

@Path("/transit")
@XmlRootElement
@Autowire
public class TransitIndex {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(TransitIndex.class);

    private static final double STOP_SEARCH_RADIUS = 200;

    @Setter @InjectParam 
    private GraphService graphService;

    private static final long MAX_STOP_TIME_QUERY_INTERVAL = 86400 * 2;

    /**
     * Return a list of all agency ids in the graph
     */
    @GET
    @Path("/agencyIds")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public AgencyList getAgencyIds(@QueryParam("routerId") String routerId) throws JSONException {

        Graph graph = getGraph(routerId);

        AgencyList response = new AgencyList();
        response.agencies = graph.getAgencies();
        return response;
    }

    /**

     Return data about a route, such as its names, color, variants,
     stops, and directions.

     A variant represents a particular stop pattern (ordered list of
     stops) on a particular route. For example, the N train has at
     least four different variants: express (over the Manhattan
     bridge), and local (via lower Manhattan and the tunnel) x to
     Astoria and to Coney Island.

     Variant names are machine-generated, and are guaranteed to be
     unique (among variants for a route) but not stable across graph
     builds.

     A route's stops include stops made by any variant of the route.

    */
    @GET
    @Path("/routeData")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getRouteData(@QueryParam("agency") String agency, @QueryParam("id") String id,
            @QueryParam("references") Boolean references, @QueryParam("extended") Boolean extended,
            @QueryParam("routerId") String routerId) throws JSONException {

        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }
        RouteDataList respond = new RouteDataList();

        for (String agencyId : getAgenciesIds(agency, routerId)) {
            AgencyAndId routeId = new AgencyAndId(agencyId, id);

            List<RouteVariant> variants = transitIndexService.getVariantsForRoute(routeId);

            if (variants.isEmpty())
                continue;

            RouteData response = new RouteData();
            response.id = routeId;
            response.variants = variants;
            response.directions = new ArrayList<String>(
                    transitIndexService.getDirectionsForRoute(routeId));
            response.route = new RouteType();
            for (RouteVariant variant : transitIndexService.getVariantsForRoute(routeId)) {
                Route route = variant.getRoute();
                response.route = new RouteType(route, extended);
                break;
            }

            if (references != null && references.equals(true)) {
                response.stops = new ArrayList<StopType>();
                for (org.onebusaway.gtfs.model.Stop stop : transitIndexService
                        .getStopsForRoute(routeId))
                    response.stops.add(new StopType(stop, extended));
            }

            respond.routeData.add(response);
        }

        return respond;
    }

    /**
     * Return a list of route ids
     */
    @GET
    @Path("/routes")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getRoutes(@QueryParam("agency") String agency,
            @QueryParam("extended") Boolean extended, @QueryParam("routerId") String routerId)
            throws JSONException {

        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }
        Collection<AgencyAndId> allRouteIds = transitIndexService.getAllRouteIds();
        RouteList response = makeRouteList(allRouteIds, agency, extended, routerId);
        return response;
    }

    private RouteList makeRouteList(Collection<AgencyAndId> routeIds, String agencyFilter,
            @QueryParam("extended") Boolean extended, @QueryParam("routerId") String routerId) {
        RouteList response = new RouteList();
        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        for (AgencyAndId routeId : routeIds) {
            for (RouteVariant variant : transitIndexService.getVariantsForRoute(routeId)) {
                Route route = variant.getRoute();
                if (agencyFilter != null && !agencyFilter.equals(route.getAgency().getId()))
                    continue;
                RouteType routeType = new RouteType(route, extended);
                response.routes.add(routeType);
                break;
            }
        }
        return response;
    }

    /**
     * Returns data for a single stop given an id
     */

    @GET
    @Path("/stopData")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getStopData(@QueryParam("agency") String agency, @QueryParam("id") String id,
            @QueryParam("extended") Boolean extended, @QueryParam("routerId") String routerId)
            throws JSONException {
    	
        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        StopList response = new StopList();

        AgencyAndId stopId = new AgencyAndId(agency, id);
    	
        Map<AgencyAndId, Stop> allStops = transitIndexService.getAllStops();
        for(Map.Entry<AgencyAndId, Stop> entry : allStops.entrySet()) {
        	//Stop stop = entry.getValue();
        	if(entry.getKey().equals(stopId)) {
        		response.stops.add(new StopType(entry.getValue(), extended));
        	}
        }
        
        return response;
    }

    
    /**
     * Returns data for stops matching a fragment of a name
     */

    @GET
    @Path("/stopsByName")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getStopsByName(@QueryParam("agency") String agency, @QueryParam("name") String name,
            @QueryParam("extended") Boolean extended, @QueryParam("routerId") String routerId)
            throws JSONException {
    	
        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        StopList response = new StopList();
    	
        Map<AgencyAndId, Stop> allStops = transitIndexService.getAllStops();
        for(Map.Entry<AgencyAndId, Stop> entry : allStops.entrySet()) {
        	Stop stop = entry.getValue();
        	if(entry.getKey().getAgencyId().equals(agency) && stop.getName().toLowerCase().contains(name.toLowerCase())) {
        		response.stops.add(new StopType(stop, extended));
        	}
        }

        return response;
    }
    
    /**
     * Return stops near a point. The default search radius is 200m, but this can be changed with the radius parameter (in meters)
     */
    @GET
    @Path("/stopsNearPoint")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getStopsNearPoint(@QueryParam("agency") String agency,
            @QueryParam("lat") Double lat, @QueryParam("lon") Double lon,
            @QueryParam("extended") Boolean extended, @QueryParam("routerId") String routerId,
            @QueryParam("radius") Double radius) throws JSONException {

        // default search radius.
        Double searchRadius = (radius == null) ? STOP_SEARCH_RADIUS : radius;

        Graph graph = getGraph(routerId);

        if (Double.isNaN(searchRadius) || searchRadius <= 0) {
            searchRadius = STOP_SEARCH_RADIUS;
        }

        StreetVertexIndexService streetVertexIndexService = graph.streetIndex;
        List<TransitStop> stops = streetVertexIndexService.getNearbyTransitStops(new Coordinate(
                lon, lat), searchRadius);
        TransitIndexService transitIndexService = graph.getService(TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        StopList response = new StopList();
        for (TransitStop transitStop : stops) {
            AgencyAndId stopId = transitStop.getStopId();
            if (agency != null && !agency.equals(stopId.getAgencyId()))
                continue;
            StopType stop = new StopType(transitStop.getStop(), extended);
            stop.routes = transitIndexService.getRoutesForStop(stopId);
            response.stops.add(stop);
        }

        return response;
    }

    /**
     * Return routes that a stop is served by
     */
    @GET
    @Path("/routesForStop")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getRoutesForStop(@QueryParam("agency") String agency,
            @QueryParam("id") String id, @QueryParam("extended") Boolean extended,
            @QueryParam("routerId") String routerId) throws JSONException {

        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        RouteList result = new RouteList();

        for (String string : getAgenciesIds(agency, routerId)) {
            List<AgencyAndId> routes = transitIndexService.getRoutesForStop(new AgencyAndId(string,
                    id));
            result.routes.addAll(makeRouteList(routes, null, extended, routerId).routes);
        }

        return result;
    }

    /**
     * Return stop times for a stop, in seconds since the epoch startTime and endTime are in milliseconds since epoch
     */
    @GET
    @Path("/stopTimesForStop")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getStopTimesForStop(@QueryParam("agency") String stopAgency,
            @QueryParam("id") String stopId, @QueryParam("startTime") long startTime,
            @QueryParam("endTime") Long endTime, @QueryParam("extended") Boolean extended,
            @QueryParam("references") Boolean references, @QueryParam("routeId") String routeId,
            @QueryParam("routerId") String routerId) throws JSONException {

        startTime /= 1000;

        if (endTime == null) {
            endTime = startTime + 86400;
        } else {
            endTime /= 1000;
        }

        if (endTime - startTime > MAX_STOP_TIME_QUERY_INTERVAL) {
            return new TransitError("Max stop time query interval is " + (endTime - startTime)
                    + " > " + MAX_STOP_TIME_QUERY_INTERVAL);
        }
        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        // add all departures
        HashSet<TripType> trips = new HashSet<TripType>();
        StopTimeList result = new StopTimeList();
        result.stopTimes = new ArrayList<StopTime>();

        if (references != null && references.equals(true)) {
            result.routes = new HashSet<Route>();
        }

        for (String stopAgencyId : getAgenciesIds(stopAgency, routerId)) {

            AgencyAndId stop = new AgencyAndId(stopAgencyId, stopId);
            Edge preBoardEdge = transitIndexService.getPreBoardEdge(stop);
            if (preBoardEdge == null)
                continue;
            Vertex boarding = preBoardEdge.getToVertex();

            RoutingRequest options = makeTraverseOptions(startTime, routerId);

            HashMap<Long, Edge> seen = new HashMap<Long, Edge>();
            //OUTER:
            for (Edge e : boarding.getOutgoing()) {
                // each of these edges boards a separate set of trips
                for (StopTime st : getStopTimesForBoardEdge(startTime, endTime, options, e,
                        extended)) {
                    // different parameters
                    st.phase = "departure";
                    if (extended != null && extended.equals(true)) {
                        if (routeId != null && !routeId.equals("")
                                && !st.trip.getRoute().getId().getId().equals(routeId))
                            continue;
                        if (references != null && references.equals(true))
                            result.routes.add(st.trip.getRoute());
                        result.stopTimes.add(st);
                    } else
                        result.stopTimes.add(st);
                    trips.add(st.trip);
                    if (seen.containsKey(st.time)) {
                        Edge old = seen.get(st.time);
                        System.out.println("DUP: " + old);
                        getStopTimesForBoardEdge(startTime, endTime, options, e, extended);
                        // break OUTER;
                    }
                    seen.put(st.time, e);
                }
            }

            // add the arriving stop times for cases where there are no departures
            Edge preAlightEdge = transitIndexService.getPreAlightEdge(stop);
            Vertex alighting = preAlightEdge.getFromVertex();
            for (Edge e : alighting.getIncoming()) {
                for (StopTime st : getStopTimesForAlightEdge(startTime, endTime, options, e,
                        extended)) {
                    if (!trips.contains(st.trip)) {
                        // diffrent parameters
                        st.phase = "arrival";
                        if (extended != null && extended.equals(true)) {
                            if (references != null && references.equals(true))
                                result.routes.add(st.trip.getRoute());
                            if (routeId != null && !routeId.equals("")
                                    && !st.trip.getRoute().getId().getId().equals(routeId))
                                continue;
                            result.stopTimes.add(st);
                        } else
                            result.stopTimes.add(st);
                    }
                }
            }

        }
        Collections.sort(result.stopTimes, new Comparator<StopTime>() {

            @Override
            public int compare(StopTime o1, StopTime o2) {
                if (o1.phase.equals("arrival") && o2.phase.equals("departure"))
                    return 1;
                if (o1.phase.equals("departure") && o2.phase.equals("arrival"))
                    return -1;
                return o1.time - o2.time > 0 ? 1 : -1;
            }

        });

        return result;
    }

    private RoutingRequest makeTraverseOptions(long startTime, String routerId) {
        RoutingRequest options = new RoutingRequest();
        // if (graphService.getCalendarService() != null) {
        // options.setCalendarService(graphService.getCalendarService());
        // options.setServiceDays(startTime, agencies);
        // }
        // TODO: verify correctness
        options.dateTime = startTime;
        Graph graph = getGraph(routerId);
        Collection<Vertex> vertices = graph.getVertices();
        Iterator<Vertex> it = vertices.iterator();
        options.setFromString(it.next().getLabel());
        options.setToString(it.next().getLabel());
        options.setRoutingContext(graph);
        return options;
    }

    /**
     * Return variant for a trip
     */
    @GET
    @Path("/variantForTrip")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getVariantForTrip(@QueryParam("tripAgency") String tripAgency,
            @QueryParam("tripId") String tripId, @QueryParam("routerId") String routerId)
            throws JSONException {

        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);

        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        AgencyAndId trip = new AgencyAndId(tripAgency, tripId);
        RouteVariant variant = transitIndexService.getVariantForTrip(trip);

        return variant;
    }

    /**
     * Return information about calendar for given agency
     */
    @GET
    @Path("/calendar")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getCalendar(@QueryParam("agency") String agency,
            @QueryParam("routerId") String routerId) throws JSONException {

        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);

        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        CalendarData response = new CalendarData();
        response.calendarList = new ArrayList<ServiceCalendarType>();
        response.calendarDatesList = new ArrayList<ServiceCalendarDateType>();

        for (String agencyId : getAgenciesIds(agency, routerId)) {
            List<ServiceCalendar> scList = transitIndexService.getCalendarsByAgency(agencyId);
            List<ServiceCalendarDate> scdList = transitIndexService
                    .getCalendarDatesByAgency(agencyId);

            if (scList != null)
                for (ServiceCalendar sc : scList)
                    response.calendarList.add(new ServiceCalendarType(sc));
            if (scdList != null)
                for (ServiceCalendarDate scd : scdList)
                    response.calendarDatesList.add(new ServiceCalendarDateType(scd));
        }

        return response;
    }

    /**
     * Return subsequent stop times for a trip; time is in milliseconds since epoch
     */
    @GET
    @Path("/stopTimesForTrip")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getStopTimesForTrip(@QueryParam("stopAgency") String stopAgency,
            @QueryParam("stopId") String stopId, @QueryParam("tripAgency") String tripAgency,
            @QueryParam("tripId") String tripId, @QueryParam("time") long time,
            @QueryParam("extended") Boolean extended, @QueryParam("routerId") String routerId)
            throws JSONException {

        time /= 1000;

        AgencyAndId firstStop = null;
        if (stopId != null) {
            firstStop = new AgencyAndId(stopAgency, stopId);
        }
        AgencyAndId trip = new AgencyAndId(tripAgency, tripId);

        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);

        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        RouteVariant variant = transitIndexService.getVariantForTrip(trip);
        RoutingRequest options = makeTraverseOptions(time, routerId);

        StopTimeList result = new StopTimeList();
        result.stopTimes = new ArrayList<StopTime>();
        State state = null;
        RouteSegment start = null;
        for (RouteSegment segment : variant.getSegments()) {
            // this is all segments across all patterns that match this variant
            if (segment.stop.equals(firstStop)) {
                // this might be the correct start segment, but we need to try traversing and see if we get this trip
                // TODO: verify options and state creation correctness (AMB)
                State s0 = new State(segment.board.getFromVertex(), options);
                state = segment.board.traverse(s0);
                if (state == null)
                    continue;
                if (state.getBackTrip().getId().equals(trip)) {
                    start = segment;
                    StopTime st = new StopTime();
                    st.time = state.getTimeSeconds();
                    for (org.onebusaway.gtfs.model.Stop stop : variant.getStops())
                        if (stop.getId().equals(segment.stop)) {
                            st.stop = new StopType(stop, extended);
                        }
                    result.stopTimes.add(st);
                    break;
                }
            }
        }
        if (start == null) {
            return null;
        }

        for (RouteSegment segment : variant.segmentsAfter(start)) {
            // TODO: verify options/state init correctness
            StateEditor se = state.edit(null);
            State s0 = se.makeState();
            state = segment.hopIn.traverse(s0);
            StopTime st = new StopTime();
            st.time = state.getTimeSeconds();
            if (extended) {
                for (org.onebusaway.gtfs.model.Stop stop : variant.getStops()) {
                    if (stop.getId().equals(segment.stop)) {
                        st.stop = new StopType(stop, extended);
                    }
                }
            }
            result.stopTimes.add(st);
        }

        return result;
    }

    private List<StopTime> getStopTimesForBoardEdge(long startTime, long endTime,
            RoutingRequest options, Edge e, Boolean extended) {
        List<StopTime> out = new ArrayList<StopTime>();
        State result;
        long time = startTime;
        do {
            // TODO verify options/state correctness
            State s0 = new State(e.getFromVertex(), time, options);
            result = e.traverse(s0);
            if (result == null)
                break;
            time = result.getTimeSeconds();
            if (time > endTime)
                break;
            StopTime stopTime = new StopTime();
            stopTime.time = time;
            stopTime.trip = new TripType(result.getBackTrip(), extended);
            out.add(stopTime);

            time += 1; // move to the next board time
        } while (true);
        return out;
    }

    private List<StopTime> getStopTimesForAlightEdge(long startTime, long endTime,
            RoutingRequest options, Edge e, Boolean extended) {
        List<StopTime> out = new ArrayList<StopTime>();
        State result;
        long time = endTime;
        options = options.reversedClone();
        do {
            // TODO: verify options/state correctness
            State s0 = new State(e.getToVertex(), time, options);
            result = e.traverse(s0);
            if (result == null)
                break;
            time = result.getTimeSeconds();
            if (time < startTime)
                break;
            StopTime stopTime = new StopTime();
            stopTime.time = time;
            stopTime.trip = new TripType(result.getBackTrip(), extended);
            out.add(stopTime);
            time -= 1; // move to the previous alight time
        } while (true);
        return out;
    }

    /**
     * Return a list of all available transit modes supported, if any.
     * 
     * @throws JSONException
     */
    @GET
    @Path("/modes")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object getModes(@QueryParam("routerId") String routerId) throws JSONException {
        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        ModeList modes = new ModeList();
        modes.modes = new ArrayList<TraverseMode>();
        for (TraverseMode mode : transitIndexService.getAllModes()) {
            modes.modes.add(mode);
        }
        return modes;
    }

    private Graph getGraph(String routerId) {
        return graphService.getGraph(routerId);
    }

    public Object getCalendarServiceDataForAgency(@QueryParam("agency") String agency,
            @QueryParam("routerId") String routerId) {
        TransitIndexService transitIndexService = getGraph(routerId).getService(
                TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }

        ServiceCalendarData data = new ServiceCalendarData();

        data.calendars = transitIndexService.getCalendarsByAgency(agency);
        data.calendarDates = transitIndexService.getCalendarDatesByAgency(agency);

        return data;
    }

    /**
     * Return a list of all stops that are inside a rectangle given by lat lon positions.
     */
    @GET
    @Path("/stopsInRectangle")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object stopsInRectangle(@QueryParam("agency") String agency,
            @QueryParam("leftUpLat") Double leftUpLat, @QueryParam("leftUpLon") Double leftUpLon,
            @QueryParam("rightDownLat") Double rightDownLat,
            @QueryParam("rightDownLon") Double rightDownLon, @QueryParam("extended") Boolean extended,
            @QueryParam("routerId") String routerId) throws JSONException {

        Graph graph = getGraph(routerId);
        StopList response = new StopList();

        StreetVertexIndexService streetVertexIndexService = graph.streetIndex;
        if (leftUpLat == null || leftUpLon == null || rightDownLat == null || rightDownLon == null) {
            double METERS_PER_DEGREE_LAT = 111111;
            double distance = 2000;
            for (Vertex gv : graph.getVertices()) {
                if (gv instanceof TransitStop) {
                    Coordinate c = gv.getCoordinate();
                    Envelope env = new Envelope(c);
                    double meters_per_degree_lon_here = METERS_PER_DEGREE_LAT
                            * Math.cos(Math.toRadians(c.y));
                    env.expandBy(distance / meters_per_degree_lon_here, distance
                            / METERS_PER_DEGREE_LAT);
                    StopType stop = new StopType(((TransitStop) gv).getStop(), extended);
                    response.stops.add(stop);
                }
            }
        } else {
            Coordinate cOne = new Coordinate(leftUpLon, leftUpLat);
            Coordinate cTwo = new Coordinate(rightDownLon, rightDownLat);
            List<TransitStop> stops = streetVertexIndexService.getNearbyTransitStops(cOne, cTwo);
            TransitIndexService transitIndexService = graph.getService(TransitIndexService.class);
            if (transitIndexService == null) {
                return new TransitError(
                        "No transit index found.  Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
            }

            for (TransitStop transitStop : stops) {
                AgencyAndId stopId = transitStop.getStopId();
                if (agency != null && !agency.equals(stopId.getAgencyId()))
                    continue;
                StopType stop = new StopType(transitStop.getStop(), extended);
                if (extended != null && extended.equals(true))
                    stop.routes = transitIndexService.getRoutesForStop(stopId);
                response.stops.add(stop);
            }
        }

        return response;
    }

    /**
     * Return a list of all routes that operate between start stop and end stop.
     */
    @GET
    @Path("/routesBetweenStops")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object routesBetweenStops(@QueryParam("startAgency") String startAgency,
            @QueryParam("endAgency") String endAgency,
            @QueryParam("startStopId") String startStopId,
            @QueryParam("endStopId") String endStopId, @QueryParam("extended") Boolean extended,
            @QueryParam("routerId") String routerId) throws JSONException {

        RouteList response = new RouteList();

        RouteList routeList = (RouteList) this.getRoutesForStop(startAgency, startStopId, extended,
                routerId);

        for (RouteType route : routeList.routes) {
            for (String agency : getAgenciesIds(null, routerId)) {
                if (ifRouteBetweenStops(route, agency, routerId, startStopId, endStopId, endAgency))
                    response.routes.add(route);
            }
        }

        return response;
    }

    private Boolean ifRouteBetweenStops(RouteType route, String agency, String routerId,
            String startStopId, String endStopId, String endAgency) throws JSONException {

        RouteDataList routeDataList = (RouteDataList) this.getRouteData(agency, route.getId()
                .getId(), false, false, routerId);
        for (RouteData routeData : routeDataList.routeData)
            for (RouteVariant variant : routeData.variants)
                for (String endStopAgency : getAgenciesIds(endAgency, routerId)) {
                    Boolean start = false;
                    for (Stop stop : variant.getStops()) {
                        if (stop.getId().getId().equals(startStopId))
                            start = true;
                        if (start && stop.getId().equals(new AgencyAndId(endStopAgency, endStopId))) {
                            return true;
                        }
                    }
                }
        return false;
    }

    private ArrayList<String> getAgenciesIds(String agency, String routerId) {

        Graph graph = getGraph(routerId);

        ArrayList<String> agencyList = new ArrayList<String>();
        if (agency == null || agency.equals("")) {
            for (String a : graph.getAgencyIds()) {
                agencyList.add(a);
            }
        } else {
            agencyList.add(agency);
        }
        return agencyList;
    }
    
    /**
     * Return a list of all trips for a given route nearby a certain point (used for on-board depart
     * when client does not know trip ID), sorted by a matching (=relevance) factor.
     */
    @GET
    @Path("/tripsAtPosition")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Object tripsAtPosition(@QueryParam("routeId") String routeIdStr,
            @QueryParam("latitude") Double latitude, @QueryParam("longitude") Double longitude,
            @QueryParam("maxReturnedValues") Integer maxReturnedValues,
            @QueryParam("timeSec") Long timeSec, @QueryParam("extended") Boolean extended,
            @QueryParam("routerId") String routerId) throws JSONException {

        // Specify sensible default values if not present
        if (timeSec == null)
            timeSec = System.currentTimeMillis() / 1000;
        if (maxReturnedValues == null)
            maxReturnedValues = 10;
        if (extended == null)
            extended = false;

        Graph graph = getGraph(routerId);
        TransitIndexService transitIndexService = graph.getService(TransitIndexService.class);
        if (transitIndexService == null) {
            return new TransitError(
                    "No transit index found. Add TransitIndexBuilder to your graph builder configuration and rebuild your graph.");
        }
        AgencyAndId routeId = AgencyAndId.convertFromString(routeIdStr);
        List<RouteVariant> variants = transitIndexService.getVariantsForRoute(routeId);
        // variants can't be null here
        Coordinate position = new Coordinate(longitude, latitude);
        List<ServiceDay> serviceDays = getServiceDays(graph, timeSec, routeId.getAgencyId());

        // Brute-force approach: number of variants for a route should be rather small
        TripList response = new TripList();
        for (RouteVariant variant : variants) {
            response.tripMatches.addAll(matchTripsForVariant(variant, position, timeSec,
                    serviceDays, extended));
        }
        Collections.sort(response.tripMatches, new Comparator<TripMatch>() {
            @Override
            public int compare(TripMatch o1, TripMatch o2) {
                return o1.matchFactor - o2.matchFactor < 0 ? -1 : +1;
            }
        });
        if (response.tripMatches.size() > maxReturnedValues)
            response.tripMatches = response.tripMatches.subList(0, maxReturnedValues);
        return response;
    }

    private List<TripMatch> matchTripsForVariant(RouteVariant variant, Coordinate position,
            long timeSec, List<ServiceDay> serviceDays, boolean extended) {
        List<TripMatch> matches = new ArrayList<TripMatch>();
        DistanceLibrary distanceLibrary = SphericalDistanceLibrary.getInstance();

        // Compute for each segment (distance, % inside segment, segment length) from the position
        List<Double> distToShape = new ArrayList<Double>();
        List<Double> fracOfShape = new ArrayList<Double>();
        List<Double> shapeLength = new ArrayList<Double>();
        int nHops = variant.getStops().size() - 1;
        for (int hop = 0; hop < nHops; hop++) {
            LineString geometry = variant.getGeometrySegment(hop);
            double distanceMeter = distanceLibrary.fastDistance(position, geometry);
            P2<LineString> geomPair = GeometryUtils.splitGeometryAtPoint(geometry, position);
            double shapeLen = distanceLibrary.fastLength(geometry);
            double coverLen = distanceLibrary.fastLength(geomPair.getFirst());
            double fraction = shapeLen > 0.01 ? coverLen / shapeLen : 0.0;
            distToShape.add(distanceMeter);
            fracOfShape.add(fraction);
            shapeLength.add(shapeLen);
        }

        Set<AgencyAndId> processedTrips = new HashSet<AgencyAndId>();
        for (RouteSegment segment : variant.getSegments()) {
            if (segment.hopOut != null && segment.hopOut instanceof PatternHop) {
                PatternHop patternHop = (PatternHop) segment.hopOut;
                TableTripPattern tripPattern = patternHop.getPattern();
                int serviceId = tripPattern.getServiceId();
                for (Trip trip : tripPattern.getTrips()) {
                    if (!processedTrips.contains(trip.getId())) {
                        processedTrips.add(trip.getId());
                        // Compute trip times
                        TripTimes tripTimes = tripPattern.getTripTimes(tripPattern
                                .getTripIndex(trip.getId()));
                        assert tripTimes.getNumHops() == nHops;
                        TripMatch tripMatch = matchTrip(timeSec, trip, tripTimes, serviceId,
                                distToShape, fracOfShape, shapeLength, serviceDays, extended);
                        if (tripMatch != null)
                            matches.add(tripMatch);
                    }
                }
            }
        }
        return matches;
    }

    /**
     * Match a trip and compute it's matching factor.
     * @return The TripMatch if the trip matches or null if not.
     */
    private TripMatch matchTrip(long timeSec, Trip trip, TripTimes tripTimes, int serviceId,
            List<Double> distToShape, List<Double> fracOfShape, List<Double> shapeLength,
            List<ServiceDay> serviceDays, boolean extended) {
        final int ONE_DAY = 24 * 60 * 60;
        double dXmin = Double.MAX_VALUE;
        double dTmin = Double.MAX_VALUE;
        double dLmin = Double.MAX_VALUE;
        int nHops = tripTimes.getNumHops();
        // For each hop (= variant segment), compute
        // the best one in term of distance AND time delta.
        for (ServiceDay sd : serviceDays) {
            if (!sd.serviceIdRunning(serviceId))
                continue;
            for (int hop = 0; hop < nHops - 1; hop++) {
                double dL = distToShape.get(hop);
                double fraction = fracOfShape.get(hop);
                double shapeLen = shapeLength.get(hop);
                int depTime = tripTimes.getDepartureTime(hop);
                int hopTime = tripTimes.getArrivalTime(hop) - depTime;
                // hopTime=0 usually means rounded time to the minute,
                // so let's assume a minimum value of 30 seconds/hop.
                double speedMs = hopTime < 30 ? shapeLen / 30 : shapeLen / hopTime;
                double dT = Math.abs(sd.time(depTime + (int) Math.round(hopTime * fraction))
                        - timeSec);
                // Here is magic: dX = dT . S + dL
                double dX = dT * speedMs + dL;
                if (dX < dXmin) {
                    dXmin = dX;
                    dTmin = dT;
                    dLmin = dL;
                }
            }
        }
        if (dXmin < Double.MAX_VALUE && dTmin < ONE_DAY) {
            TripMatch tripMatch = new TripMatch();
            tripMatch.trip = new TripType(trip, extended);
            tripMatch.matchDistanceMeter = dLmin;
            tripMatch.matchTimeSeconds = dTmin;
            tripMatch.matchFactor = dXmin;
            return tripMatch;
        } else {
            return null;
        }
    }

    /**
     * @return 3 service days: yesterday, today and tomorrow for an agency.
     */
    private List<ServiceDay> getServiceDays(Graph graph, long epochSec, String agencyId) {
        final long SEC_IN_DAY = 60 * 60 * 24;
        List<ServiceDay> serviceDays = new ArrayList<ServiceDay>(3);
        serviceDays.add(new ServiceDay(graph, epochSec - SEC_IN_DAY, graph.getCalendarService(),
                agencyId));
        serviceDays.add(new ServiceDay(graph, epochSec, graph.getCalendarService(), agencyId));
        serviceDays.add(new ServiceDay(graph, epochSec + SEC_IN_DAY, graph.getCalendarService(),
                agencyId));
        return serviceDays;
    }
    
}
