package org.opentripplanner.routing.vertextype;

import org.onebusaway.gtfs.model.AgencyAndId;
import org.opentripplanner.routing.edgetype.TripPattern;
import org.opentripplanner.routing.graph.Graph;

public abstract class OnboardVertex extends TransitVertex {

    private static final long serialVersionUID = 1L;

    // remember to have optimizetransit builder change these
    //private final TripPattern tripPattern;
    
    public OnboardVertex(Graph g, String label, double x, double y, AgencyAndId stopId, TripPattern tripPattern) {
        super(g, label, x, y, stopId);
        //this.tripPattern = tripPattern;
    }

//    public TripPattern getTripPattern() {
//        return tripPattern;
//    }
    
}