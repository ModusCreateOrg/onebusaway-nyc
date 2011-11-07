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
package org.onebusaway.nyc.presentation.model.search;

import org.onebusaway.nyc.presentation.impl.WebappSupportLibrary;
import org.onebusaway.nyc.presentation.service.search.SearchResult;
import org.onebusaway.transit_data.model.StopBean;

import java.util.List;

public class StopResult implements SearchResult {

  private StopBean stopBean;

  private List<RouteResult> routesAvailable;

  public StopResult() {}

  public void setStopBean(StopBean stopBean) {
    this.stopBean = stopBean;
  }
  
  public void setRoutesAvailable(List<RouteResult> routesAvailable) {
    this.routesAvailable = routesAvailable;
  }
  
  @Override
  public String getType() {
    return "stopResult";
  }
  
  @Override
  public String getName() {
    return stopBean.getName();
  }

  public String getStopId() {
    return stopBean.getId();
  }

  public String getStopIdWithoutAgency() {
    WebappSupportLibrary webappIdParser = new WebappSupportLibrary();
    return webappIdParser.parseIdWithoutAgency(getStopId());
  }

  public Double getLatitude() {
    return stopBean.getLat();
  }

  public Double getLongitude() {
    return stopBean.getLon();
  }

  public String getStopDirection() {
    return stopBean.getDirection();
  }
  
  public List<RouteResult> getRoutesAvailable() {
    return routesAvailable;
  }
}