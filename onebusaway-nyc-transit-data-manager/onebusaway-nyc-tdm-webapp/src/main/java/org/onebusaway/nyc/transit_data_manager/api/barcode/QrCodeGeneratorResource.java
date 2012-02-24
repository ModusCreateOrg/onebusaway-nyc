package org.onebusaway.nyc.transit_data_manager.api.barcode;

import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.onebusaway.nyc.transit_data_manager.barcode.BarcodeContentsConverter;
import org.onebusaway.nyc.transit_data_manager.barcode.BarcodeContentsConverterImpl;
import org.onebusaway.nyc.transit_data_manager.barcode.BarcodeImageType;
import org.onebusaway.nyc.transit_data_manager.barcode.GoogleChartBarcodeGenerator;
import org.onebusaway.nyc.transit_data_manager.barcode.QRErrorCorrectionLevel;
import org.onebusaway.nyc.transit_data_manager.barcode.QrCodeGenerator;
import org.onebusaway.nyc.transit_data_manager.barcode.model.MtaBarcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import au.com.bytecode.opencsv.CSVReader;

@Path("/barcode")
@Component
public class QrCodeGeneratorResource {

  public QrCodeGeneratorResource() {
    super();

    setContentConv(new BarcodeContentsConverterImpl());
    setBarcodeGen(new GoogleChartBarcodeGenerator());
    barcodeGen.setEcLevel(QRErrorCorrectionLevel.Q);
  }

  private static String REPLACE_STR = "__REPLACE__";
  private static String STOP_COLUMN_NAME = "STOP_ID";
  
  private static Logger _log = LoggerFactory.getLogger(QrCodeGeneratorResource.class);

  @Autowired
  private String urlToEmbedStopIdReplace;

  private static String ZIP_FILE_PREFIX = "QrCodes";
  private static String ZIP_FILE_SUFFIX = ".zip";

  private QrCodeGenerator barcodeGen;
  private BarcodeContentsConverter contentConv;

  public void setUrlToEmbedStopIdReplace (String replaceUrl) {
    this.urlToEmbedStopIdReplace = replaceUrl;
  }
  
  public void setBarcodeGen(QrCodeGenerator barcodeGen) {
    this.barcodeGen = barcodeGen;
  }

  public void setContentConv(BarcodeContentsConverter contentConv) {
    this.contentConv = contentConv;
  }

  @Path("/getByStopId/{stopId}")
  @GET
  public Response getQrCodeForStopUrlById(@PathParam("stopId") int stopId,
      @DefaultValue("99") @QueryParam("img-dimension") final int imgDimension,
      @DefaultValue("BMP") @QueryParam("img-type") String imgFormatName,
      @DefaultValue("4") @QueryParam("margin-rows") final int quietZoneRows) {

    _log.info("Starting getQrCodeForStopUrlById.");

    final BarcodeImageType imageType = parseImgFormatName(imgFormatName);

    final String barcodeContents = generateBusStopContentsForStopId(String.valueOf(stopId));

    //boolean contentsFitBarcodeVersion = contentConv.fitsV2QrCode(
        //QRErrorCorrectionLevel.Q, barcodeContents);
    
    boolean contentsFitBarcodeVersion = true;

    if (!contentsFitBarcodeVersion) {
      throw new WebApplicationException(new IllegalArgumentException(
          "Input stop id too long to fit V2 Qr Code"),
          Response.Status.BAD_REQUEST);
    }

    Response response = null;

    if (!"".equals(barcodeContents) && contentsFitBarcodeVersion) {
      StreamingOutput output = new StreamingOutput() {

        @Override
        public void write(OutputStream os) throws IOException,
            WebApplicationException {

          genBarcodeWriteToOutputStream(barcodeContents, imgDimension,
              quietZoneRows, imageType, os);

          if (os != null)
            os.close();
        }
      };

      response = Response.ok(output, imageType.getMimeType()).header(
          "Content-Disposition",
          "attachment; filename=\"" + String.valueOf(stopId) + "."
              + imageType.getFormatName() + "\"").build();

    } else {
      response = Response.serverError().build();
    }

    _log.info("Returning Response in getQrCodeForStopUrlById.");
    return response;
  }

  @Path("/batchGen")
  @Consumes({"text/comma-separated-values", "text/csv"})
  @POST
  public Response batchGenerateBarcodes(
      @DefaultValue("99") @QueryParam("img-dimension") int imgDimension,
      @DefaultValue("BMP") @QueryParam("img-type") String imgFormatName,
      @DefaultValue("4") @QueryParam("margin-rows") int quietZoneRows,
      InputStream inputFileStream) {

    _log.info("batchGenerateBarcodes Started.");

    BarcodeImageType imageType = parseImgFormatName(imgFormatName);

    List<MtaBarcode> urlsToEncode;
    try {
      urlsToEncode = parseListResultBarcodesFromInputCsv(inputFileStream);
    } catch (IOException e) {
      throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
    } finally {
      if (inputFileStream != null)
        try {
          inputFileStream.close();
        } catch (IOException e) {
          throw new WebApplicationException(e,
              Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    Response response = null;

    final File resultZipFile;
    try {
      resultZipFile = generateBarcodeZipFileFromUrlList(urlsToEncode,
          imgDimension, imageType, quietZoneRows);
    } catch (IOException e1) {
      _log.warn("Error generating zip file.");
      throw new WebApplicationException(e1,
          Response.Status.INTERNAL_SERVER_ERROR);
    };

    long zipFileLength = resultZipFile.length();

    StreamingOutput output = new StreamingOutput() {

      @Override
      public void write(OutputStream os) throws IOException,
          WebApplicationException {

        FileChannel inChannel = null;
        WritableByteChannel outChannel = null;

        try {
          inChannel = new FileInputStream(resultZipFile).getChannel();
          outChannel = Channels.newChannel(os);

          inChannel.transferTo(0, inChannel.size(), outChannel);
        } finally {
          if (outChannel != null)
            outChannel.close();
          if (inChannel != null)
            inChannel.close();
        }

      }
    };

    response = Response.ok(output, "application/zip").header(
        "Content-disposition",
        "attachment; filename=" + ZIP_FILE_PREFIX + ZIP_FILE_SUFFIX).header(
        "Content-Length", zipFileLength).build();

    _log.info("Returning Response in batchGenerateBarcodes.");

    return response;
  }

  private List<MtaBarcode> parseListResultBarcodesFromInputCsv(
      InputStream inputCsvFileStream) throws IOException {
    int stopColumnIdx = -1;

    // First make sure we have a STOP_COLUMN_NAME column.
    // Take note of its index.
    List<String[]> allLines = null;
    String[] headerLine;
    List<String[]> dataLines = null;

    CSVReader reader = null;
    try {
      reader = new CSVReader(new InputStreamReader(inputCsvFileStream));

      allLines = reader.readAll();
      headerLine = allLines.get(0);
      _log.info("found " + allLines.size()
          + " total lines in input. header is " + Arrays.toString(headerLine));

      dataLines = allLines.subList(1, allLines.size());
    } catch (IOException e) {
      e.printStackTrace();
      throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
    } finally {
      if (reader != null)
        reader.close();
    }

    for (int i = 0; i < headerLine.length; i++) {
      if (STOP_COLUMN_NAME.equalsIgnoreCase(headerLine[i])) {
        if (stopColumnIdx == -1) {
          stopColumnIdx = i;
        } else { // multiple columns with the same column name?
          throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }
      }
    }

    if (stopColumnIdx == -1) { // We didn't find the column
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }

    /*
     * Now that we have the header sorted out, create a barcode for each line
     * and add it to the zipfile.
     */

    List<MtaBarcode> urlsToEncode = new ArrayList<MtaBarcode>();

    Iterator<String[]> linesIt = dataLines.iterator();
    String[] line;
    while (linesIt.hasNext()) {
      line = linesIt.next();

      _log.debug("adding stopnum " + line[stopColumnIdx]
          + "to the list of items to encode.");

      String stopIdStr = line[stopColumnIdx];

      String barcodeContents = generateBusStopContentsForStopId(stopIdStr);

//      boolean contentsFitBarcodeVersion = contentConv.fitsV2QrCode(
//          QRErrorCorrectionLevel.Q, barcodeContents);
      
      boolean contentsFitBarcodeVersion = true;

      if (!"".equals(barcodeContents) && contentsFitBarcodeVersion) {

        MtaBarcode barcode = new MtaBarcode();

        barcode.setContents(barcodeContents);
        barcode.setStopIdStr(stopIdStr);

        if (!urlsToEncode.contains(barcode)) {
          urlsToEncode.add(barcode);
        }
      }
    }

    return urlsToEncode;
  }

  protected File generateBarcodeZipFileFromUrlList(List<MtaBarcode> bcList,
      int imageDimension, BarcodeImageType imgType, int quietZoneRows)
      throws IOException {

    File tempFile = getTempFile(ZIP_FILE_PREFIX, ZIP_FILE_SUFFIX);

    ZipOutputStream zipOutput = null;

    try {
      zipOutput = new ZipOutputStream(new FileOutputStream(tempFile));

      for (MtaBarcode bc : bcList) {
        // RenderedImage responseImg = barcodeGen.generateCode(bc.getContents(),
        // imageDimension,
        // imageDimension, quietZoneRows);

        String imgFileName = String.valueOf(bc.getStopIdStr()) + "."
            + imgType.getFormatName();

        ZipEntry zipEntry = new ZipEntry(imgFileName);

        zipOutput.putNextEntry(zipEntry);

        _log.debug("writing " + imgFileName + " to tempfile.");

        genBarcodeWriteToOutputStream(bc.getContents(), imageDimension,
            quietZoneRows, imgType, zipOutput);
      }
    } finally {
      if (zipOutput != null)
        zipOutput.close();
    }

    return tempFile;
  }

  private void genBarcodeWriteToOutputStream(String barcodeContents,
      int imageDimension, int quietZoneRows, BarcodeImageType imgType,
      OutputStream outputStream) throws IOException {
    RenderedImage img = barcodeGen.generateCode(barcodeContents,
        imageDimension, imageDimension, quietZoneRows);

    ImageIO.write(img, imgType.getFormatName(), outputStream);
  }

  private File getTempFile(String prefix, String suffix) throws IOException {
    // Create the zip file & output stream.
    File tempFile;

    try {
      tempFile = File.createTempFile(prefix, suffix);
    } catch (IOException e) {
      try {
        tempFile = File.createTempFile(prefix + "_B", suffix);
      } catch (IOException e1) {
        throw new IOException("Could not create temporary file", e);
      }
    }

    _log.info("Generated temp file " + tempFile.getPath());

    return tempFile;
  }

  private String getStopUrl(String stopId) {
    return getEmbeddedStopIdUrl(stopId);
  }

  private String generateBusStopContentsForStopId(String stopId) {
    String barcodeContents = contentConv.contentsForUrl(getStopUrl(stopId));

    return barcodeContents;
  }

  private BarcodeImageType parseImgFormatName(String imgFormatName) {
    BarcodeImageType imageType;

    // determine the image type parameter.
    if ("PNG".equalsIgnoreCase(imgFormatName)) {
      imageType = BarcodeImageType.PNG;
    } else if ("BMP".equalsIgnoreCase(imgFormatName)) {
      imageType = BarcodeImageType.BMP;
    } else {
      imageType = BarcodeImageType.BMP;
    }

    return imageType;
  }
  
  /**
   * This method takes a stopId and returns a string representing the
   * URL to embed in barcodes for that stop Id. It assumes the 
   * property urlToEmbedStopIdReplace in this class contains a replacement
   * string, '__REPLACE__'
   * @param stopId The stop Id to substitute for __REPLACE__.
   * @return A string with the stop id url for embedding into barcodes. Does
   * not capitalize it or anything which may be needed for good barcodes.
   */
  private String getEmbeddedStopIdUrl(String stopIdStr) {
    String stopIdUrl = urlToEmbedStopIdReplace.replaceAll(REPLACE_STR, stopIdStr);
    
    return stopIdUrl;
  }
}