/**
 * Copyright (c) 2011 Metropolitan Transportation Authority
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.onebusaway.nyc.vehicle_tracking.impl.inference;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.ObjectUtils;
import org.onebusaway.geospatial.services.SphericalGeometryLibrary;
import org.onebusaway.nyc.transit_data.services.ConfigurationService;
import org.onebusaway.nyc.transit_data_federation.services.nyc.DestinationSignCodeService;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.BlocksFromObservationServiceImpl.BestBlockObservationStates;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.ObservationCache.EObservationCacheKey;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockState;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockStateObservation;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.JourneyState;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.MotionState;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState;
import org.onebusaway.nyc.vehicle_tracking.model.NycRawLocationRecord;
import org.onebusaway.realtime.api.EVehiclePhase;
import org.onebusaway.transit_data_federation.services.blocks.BlockInstance;
import org.onebusaway.transit_data_federation.services.blocks.ScheduledBlockLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// TODO FIXME update for runs!
@Component
public class BlockStateTransitionModel {

  private static Logger _log = LoggerFactory.getLogger(BlockStateTransitionModel.class);

  private DestinationSignCodeService _destinationSignCodeService;

  private BlocksFromObservationService _blocksFromObservationService;

  private BlockStateSamplingStrategy _blockStateSamplingStrategy;

  private ObservationCache _observationCache;

  private VehicleStateLibrary _vehicleStateLibrary;

  @Autowired
  private ConfigurationService _configurationService;
  /****
   * Parameters
   ****/

  /**
   * Given a potential distance to travel in physical / street-network space, a
   * fudge factor that determines how far ahead we will look along the block of
   * trips in distance to travel
   */
  private double _blockDistanceTravelScale = 1.5;

  /****
   * Setters
   ****/

  @Autowired
  public void setDestinationSignCodeService(
      DestinationSignCodeService destinationSignCodeService) {
    _destinationSignCodeService = destinationSignCodeService;
  }

  @Autowired
  public void setBlocksFromObservationService(
      BlocksFromObservationService blocksFromObservationService) {
    _blocksFromObservationService = blocksFromObservationService;
  }

  @Autowired
  public void setBlockStateSamplingStrategy(
      BlockStateSamplingStrategy blockStateSamplingStrategy) {
    _blockStateSamplingStrategy = blockStateSamplingStrategy;
  }

  @Autowired
  public void setObservationCache(ObservationCache observationCache) {
    _observationCache = observationCache;
  }

  @Autowired
  public void setVehicleStateLibrary(VehicleStateLibrary vehicleStateLibrary) {
    _vehicleStateLibrary = vehicleStateLibrary;
  }

  public void setBlockDistanceTravelScale(double blockDistanceTravelScale) {
    _blockDistanceTravelScale = blockDistanceTravelScale;
  }

  /****
   * Block State Methods
   ****/

  /**
   * This method takes a parent VehicleState, parentState, and a potential
   * JourneyState, journeyState, with which a new potential
   * BlockStateObservation is sampled/determined. This is where we consider a
   * block, allow a block change, or move along a block we're already tracking.
   * 
   * @param parentState
   * @param motionState
   * @param journeyState
   * @param obs
   * @return BlockStateObservation
   */
  public Set<BlockStateObservation> transitionBlockState(
      VehicleState parentState, MotionState motionState,
      JourneyState journeyState, Observation obs) {

    BlockStateObservation parentBlockState = parentState.getBlockStateObservation();
    Set<BlockStateObservation> potentialTransStates = new HashSet<BlockStateObservation>();

    EVehiclePhase parentPhase = parentState.getJourneyState().getPhase();

    boolean allowBlockChange = allowBlockTransition(parentState, motionState,
        journeyState, obs);

    if (!allowBlockChange) {
      if (parentBlockState != null) {
        if (EVehiclePhase.isActiveDuringBlock(journeyState.getPhase())) {
          potentialTransStates.add(advanceAlongBlock(parentPhase,
              parentBlockState, obs));
        } else {
          potentialTransStates.add(parentBlockState);
        }
      }
    }

    /**
     * If this state has no transitions, consider block changes. Otherwise, pick
     * the best transition.
     */
    if (allowBlockChange || potentialTransStates.isEmpty()) {

      /**
       * We're now considering that the driver may have been re-assigned.
       */
      potentialTransStates.addAll(_blocksFromObservationService.determinePotentialBlockStatesForObservation(
          obs, true));
      
      if (!EVehiclePhase.isActiveDuringBlock(journeyState.getPhase()))
        potentialTransStates.add(null);
      
      // TODO we used to do this. what about now?
      // if (EVehiclePhase.isLayover(parentPhase)
      // || _vehicleStateLibrary.isAtPotentialTerminal(obs.getRecord(),
      // updatedBlockState.getBlockInstance())) {
      // updatedBlockState = _blocksFromObservationService.advanceLayoverState(
      // obs, updatedBlockState);
    }

    return potentialTransStates;
  }

  public BestBlockObservationStates getClosestBlockStates(
      BlockState blockState, Observation obs) {

    Map<BlockInstance, BestBlockObservationStates> states = _observationCache.getValueForObservation(
        obs, EObservationCacheKey.CLOSEST_BLOCK_LOCATION);

    if (states == null) {
      states = new ConcurrentHashMap<BlockInstance, BestBlockObservationStates>();
      _observationCache.putValueForObservation(obs,
          EObservationCacheKey.CLOSEST_BLOCK_LOCATION, states);
    }

    BlockInstance blockInstance = blockState.getBlockInstance();

    BestBlockObservationStates closestState = states.get(blockInstance);

    if (closestState == null) {

      closestState = getClosestBlockStateUncached(blockState, obs);

      states.put(blockInstance, closestState);
    }

    return closestState;
  }

  /****
   * Private Methods
   ****/

  private boolean allowBlockTransition(VehicleState parentState,
      MotionState motionState, JourneyState journeyState, Observation obs) {

    Observation prevObs = obs.getPreviousObservation();
    BlockStateObservation parentBlockState = parentState.getBlockStateObservation();

    if (parentBlockState == null && !obs.isOutOfService()
        && journeyState.getPhase() == EVehiclePhase.DEADHEAD_BEFORE)
      return true;

    /**
     * If we're off block
     */
    if (parentBlockState != null
        && _vehicleStateLibrary.isOffBlock(obs.getPreviousObservation(),
          parentBlockState.getBlockState()))
      return true;

    /**
     * Have we just transitioned out of a terminal?
     */
    if (prevObs != null) {
      /**
       * If we were assigned a block, then use the block's terminals, otherwise,
       * all terminals.
       */
      if (parentBlockState == null) {
        // if (prevObs.isAtTerminal() && !obs.isAtTerminal())
        // return true;
      } else if (parentBlockState != null) {
        boolean wasAtBlockTerminal = _vehicleStateLibrary.isAtPotentialTerminal(
            prevObs.getRecord(),
            parentBlockState.getBlockState().getBlockInstance());
        boolean isAtBlockTerminal = _vehicleStateLibrary.isAtPotentialTerminal(
            obs.getRecord(),
            parentBlockState.getBlockState().getBlockInstance());

        if (wasAtBlockTerminal && !isAtBlockTerminal) {
          return true;
        }
      }
    }

    NycRawLocationRecord record = obs.getRecord();
    String dsc = record.getDestinationSignCode();

    boolean unknownDSC = _destinationSignCodeService.isUnknownDestinationSignCode(dsc);

    /**
     * If we have an unknown DSC, we assume it's a typo, so we assume our
     * current block will work for now. What about "0000"? 0000 is no longer
     * considered an unknown DSC, so it should pass by the unknown DSC check,
     * and to the next check, where we only allow a block change if we've
     * changed from a good DSC.
     */
    if (unknownDSC)
      return false;

    /**
     * If the destination sign code has changed, we allow a block transition
     */
    if (hasDestinationSignCodeChangedBetweenObservations(obs))
      return true;

    return false;
  }

  /**
   * This method searches for the best block location within a given distance
   * from the previous block location. As well, transitions to and from layover
   * states are considered here.
   * 
   * @param phase
   * @param BlockStateObservation
   * @param obs
   * @return
   */
  private BlockStateObservation advanceAlongBlock(EVehiclePhase phase,
      BlockStateObservation blockState, Observation obs) {

//    Observation prevObs = obs.getPreviousObservation();
//
//    double distanceToTravel = 300
//        + SphericalGeometryLibrary.distance(prevObs.getLocation(),
//            obs.getLocation()) * _blockDistanceTravelScale;

//    BlockStateObservation updatedBlockState = _blocksFromObservationService.advanceState(
//        obs, blockState, 0, Double.POSITIVE_INFINITY).getBestLocation();
    BlockStateObservation updatedBlockState = _blocksFromObservationService.bestStates(
        obs, blockState).getBestLocation();

    BlockStateObservation state = updatedBlockState;
    if (updatedBlockState != null
        && (EVehiclePhase.isLayover(phase) || _vehicleStateLibrary.isAtPotentialTerminal(
            obs.getRecord(), blockState.getBlockState().getBlockInstance()))) {
//      for (BlockStateObservation state : updatedBlockStates.getAllStates()) {
        state = _blocksFromObservationService.advanceLayoverState(obs, updatedBlockState);
//      }
    }

    return state;
  }

  /**
   * Returns closest block state, spatially, to the passed
   * BlockStateObservation.
   * 
   * @param blockStateObservation
   * @param obs
   * @return
   */
  private BestBlockObservationStates getClosestBlockStateUncached(
      BlockState blockState, Observation obs) {

    double distanceToTravel = 400;

    Observation prevObs = obs.getPreviousObservation();

    if (prevObs != null) {
      distanceToTravel = Math.max(
          distanceToTravel,
          SphericalGeometryLibrary.distance(obs.getLocation(),
              prevObs.getLocation())
              * _blockDistanceTravelScale);
    }

    BestBlockObservationStates advancedStates = _blocksFromObservationService.advanceState(
        obs, blockState, -distanceToTravel, distanceToTravel);

    if (advancedStates == null)
      return null;

    BestBlockObservationStates closestStates;
    ScheduledBlockLocation blockLocation = advancedStates.getBestLocation().getBlockState().getBlockLocation();
    double d = SphericalGeometryLibrary.distance(blockLocation.getLocation(),
        obs.getLocation());

    // TODO don't know about this...
    if (d > 400) {
      BlockStateObservation blockStateObservation = new BlockStateObservation(
          blockState, obs);
      closestStates = _blocksFromObservationService.bestStates(obs,
          blockStateObservation);
    } else {
      closestStates = advancedStates;
    }

    return closestStates;
  }

  public static boolean hasDestinationSignCodeChangedBetweenObservations(
      Observation obs) {

    String previouslyObservedDsc = null;
    NycRawLocationRecord previousRecord = obs.getPreviousRecord();
    if (previousRecord != null)
      previouslyObservedDsc = previousRecord.getDestinationSignCode();

    NycRawLocationRecord record = obs.getRecord();
    String observedDsc = record.getDestinationSignCode();

    return !ObjectUtils.equals(previouslyObservedDsc, observedDsc);
  }

  public static class BlockStateResult {

    public BlockStateResult(BlockStateObservation BlockStateObservation,
        boolean outOfService) {
      this.BlockStateObservation = BlockStateObservation;
      this.outOfService = outOfService;
    }

    public BlockStateObservation BlockStateObservation;
    public boolean outOfService;
  }

}
