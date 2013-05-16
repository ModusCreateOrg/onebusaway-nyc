package org.onebusaway.nyc.transit_data_federation.impl.predictions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.nyc.transit_data.services.NycTransitDataService;
import org.onebusaway.nyc.transit_data_federation.impl.queue.TimeQueueListenerTask;
import org.onebusaway.nyc.transit_data_federation.services.predictions.PredictionGenerationService;
import org.onebusaway.realtime.api.TimepointPredictionRecord;
import org.onebusaway.transit_data.model.VehicleStatusBean;
import org.onebusaway.transit_data.model.blocks.BlockInstanceBean;
import org.onebusaway.transit_data.model.blocks.BlockTripBean;
import org.onebusaway.transit_data.model.trips.TripStatusBean;
import org.onebusaway.transit_data_federation.services.AgencyAndIdLibrary;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;


public class QueueBasedPredictionGenerationServiceImpl extends
		TimeQueueListenerTask implements PredictionGenerationService {

  @Autowired
  private NycTransitDataService _transitDataService;
  
	private ConcurrentMap<String, List<TimepointPredictionRecord>> cache 
		= new ConcurrentHashMap<String, List<TimepointPredictionRecord>>();
	
	@Override
	public List<TimepointPredictionRecord> getPredictionsForVehicle(
			AgencyAndId vehicleId) {
		return cache.get(vehicleId.toString());
	}

	@Override
	protected void processResult(FeedMessage message) {
	  // convert FeedMessage to TimepointPredictionRecord
	  for (GtfsRealtime.FeedEntity entity : message.getEntityList()) {
	    List<TimepointPredictionRecord> predictionRecords = new ArrayList<TimepointPredictionRecord>();  
	    TripUpdate tu = entity.getTripUpdate();
	    String vehicleId = entity.getVehicle().getVehicle().getId();

	    for (StopTimeUpdate stu : tu.getStopTimeUpdateList()) {
	      TimepointPredictionRecord tpr = new TimepointPredictionRecord();
	      tpr.setTimepointId(AgencyAndIdLibrary.convertFromString(stu.getStopId()));
	      tpr.setTimepointPredictedTime(stu.getArrival().getTime() * 1000);
	      Long scheduledTime = lookupScheduledTime(vehicleId, entity.getTripUpdate().getTrip().getTripId(), stu.getStopSequence());
	      
	      if (scheduledTime != null) {
	        tpr.setTimepointScheduledTime(scheduledTime);
	        predictionRecords.add(tpr);
	      }
	    }
	    // place in cache
	    this.cache.put(vehicleId, predictionRecords);
	  }
	  
	}

	
	private Long lookupScheduledTime(String vehicleId, String tripId, int stopSequence) {

	  VehicleStatusBean vehicleStatus = _transitDataService.getVehicleForAgency(vehicleId, System.currentTimeMillis());
	  
	  if(vehicleStatus == null)
      return null;

    TripStatusBean tripStatus = vehicleStatus.getTripStatus();    
    if(tripStatus == null)
      return null;
    
    BlockInstanceBean blockInstance = _transitDataService.getBlockInstance(tripStatus.getActiveTrip().getBlockId(), tripStatus.getServiceDate());
    if(blockInstance == null)
      return null;
    
    // we need to match the given trip to the active trip
    List<BlockTripBean> blockTrips = blockInstance.getBlockConfiguration().getTrips();
    boolean foundActiveTrip = false;
    for (BlockTripBean blockTrip : blockTrips) {
      if (!foundActiveTrip) {
        if(tripId.equals(blockTrip.getTrip().getId())) {
          if (blockTrip.getBlockStopTimes().size() > stopSequence) {
            return tripStatus.getServiceDate() + blockTrip.getBlockStopTimes().get(stopSequence).getStopTime().getArrivalTime() * 1000l;
          }
          foundActiveTrip = true;
        }
      }
      
    }
    return null;
  }

}
