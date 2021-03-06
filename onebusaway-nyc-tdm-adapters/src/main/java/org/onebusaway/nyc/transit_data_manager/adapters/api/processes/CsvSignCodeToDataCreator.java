package org.onebusaway.nyc.transit_data_manager.adapters.api.processes;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.onebusaway.nyc.transit_data_manager.adapters.data.ImporterSignCodeData;
import org.onebusaway.nyc.transit_data_manager.adapters.data.SignCodeData;
import org.onebusaway.nyc.transit_data_manager.adapters.input.CSVCcAnnouncementInfoConverter;
import org.onebusaway.nyc.transit_data_manager.adapters.input.CcAnnouncementInfoConverter;

import tcip_final_3_0_5_1.CCDestinationSignMessage;

public class CsvSignCodeToDataCreator {
  private File inputFile;

  public CsvSignCodeToDataCreator(File inputFile) {
    this.inputFile = inputFile;
  }
  
  public SignCodeData generateDataObject() throws IOException {
    CcAnnouncementInfoConverter inConv = new CSVCcAnnouncementInfoConverter(
        inputFile);
    
    List<CCDestinationSignMessage> signs = inConv.getDestinationsAsList();
    
    SignCodeData data = new ImporterSignCodeData(signs);
    
    return data;
  }
}
