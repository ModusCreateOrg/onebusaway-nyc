package org.onebusaway.nyc.transit_data_manager.siri;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.apache.commons.lang.StringUtils;
import org.onebusaway.transit_data.model.service_alerts.ServiceAlertBean;

@XmlRootElement
public class SituationExchangeResults {
  public static final String ADDED = "added";
  @XmlElement
  String status = "OK";
  @XmlElement
  private
  List<DeliveryResult> delivery = new ArrayList<DeliveryResult>();

  void countPtSituationElementResult(DeliveryResult deliveryResult, ServiceAlertBean serviceAlertBean, String status) {
    countPtSituationElementResult(deliveryResult, serviceAlertBean.getId(), status);
  }

  public void countPtSituationElementResult(DeliveryResult deliveryResult,
      String serviceAlertId, String status) {
    PtSituationElementResult ptSituationElementResult = new PtSituationElementResult();
    ptSituationElementResult.id = serviceAlertId;
    ptSituationElementResult.result = status;
    deliveryResult.getPtSituationElement().add(ptSituationElementResult);
  }
  
  @Override
  public String toString() {
    List<String> s = new ArrayList<String>();
    s.add("status=" + status);
    for (DeliveryResult d: getDelivery()) {
      for (PtSituationElementResult p: d.getPtSituationElement()) {
        s.add("id=" + p.id + " result=" + p.result);
      }
    }
    return StringUtils.join(s,  "\n");
  }

  public List<DeliveryResult> getDelivery() {
    return delivery;
  }

}

class PtSituationElementResult {
  @XmlElement
  String id;
  @XmlElement
  String result;
}

class DeliveryResult {
  private
  List<PtSituationElementResult> ptSituationElement = new ArrayList<PtSituationElementResult>();

  public List<PtSituationElementResult> getPtSituationElement() {
    return ptSituationElement;
  }

  @XmlElement
  public void setPtSituationElement(List<PtSituationElementResult> ptSituationElement) {
    this.ptSituationElement = ptSituationElement;
  }
}
