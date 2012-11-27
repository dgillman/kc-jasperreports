package com.rsmart.kuali.coeus.reporting.jasperreports;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExporterParameter;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.JRHtmlExporterParameter;
import net.sf.jasperreports.engine.export.JRXhtmlExporter;
import net.sf.jasperreports.engine.export.JRXlsExporter;
import net.sf.jasperreports.engine.export.JRXlsExporterParameter;
import net.sf.jasperreports.j2ee.servlets.ImageServlet;

public class ReportingServlet extends HttpServlet {

  private static final Logger LOG = LoggerFactory.getLogger(ReportingServlet.class);


  // Names of the HTTP params that may come with a GET request
  private static final String HTTP_PARAM_TYPE = "type";
  private static final String HTTP_PARAM_REPORT = "report";

  // Identifies the Spring bean containing the path to the report definition files
  // relative to the root directory of the ServletContext (eg. directory above WEB-INF)
  private static final String SPRING_REPORT_PATH  = "reportPath";
  // Identifies the Spring bean containing the path for compiled report files
  // relative to the root directory of the ServletContext (eg. directory above WEB-INF)
  private static final String SPRING_COMPILED_REPORT_PATH = "compiledReportPath";
  // Identifies the Spring bean that is the DB DataSource
  private static final String SPRING_DATASOURCE_NAME = "dataSource";
  
  // MIME types that may be returned based on selected OutputType
  private static final String MIMETYPE_PDF = "application/pdf";
  private static final String MIMETYPE_HTML = "text/html";
  private static final String MIMETYPE_XLS = "application/vnd.ms-excel";

  enum OutputType {
    HTML, PDF, XLS
  }

  private WebApplicationContext applicationContext = null;
  private DataSource datasource = null;
  private String reportPath = "";
  private String compiledReportPath = "";

  private static final String terminateInSeparatorChar(final String path) {
    // add a '/' character if it is needed
    if (path != null) {
      final String tmp = path.trim();
      final int len = tmp.length();
      
      if (len > 0 && path.charAt(len-1) != File.separatorChar) {
        return tmp + File.separatorChar;
      } else {
        return path;
      }
    }
    
    return null;
  }
  
  /**
   * Converts the relative path for reports to an absolute path.
   * 
   * @param reportFile
   * @return
   */
  protected final String getAbsolutePath(final String filePath) {
    return getServletContext().getRealPath(filePath);
  }
  
  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);

    // Get the Spring application context
    applicationContext = WebApplicationContextUtils.getWebApplicationContext(config.getServletContext());
    
    // Get the DataSource
    datasource = (DataSource) applicationContext.getBean(SPRING_DATASOURCE_NAME);
    
    // directory containing the report definitions
    final String reportDirPath = applicationContext.getBean(SPRING_REPORT_PATH, String.class);

    reportPath = terminateInSeparatorChar(reportDirPath);

    LOG.debug("Path for report definitions: {}", reportPath);
    
    // check that the reports directory exists and has appropriate permissions
    final File reportDir = new File(config.getServletContext().getRealPath(reportPath));
    
    if (!(reportDir != null && reportDir.isDirectory() && reportDir.canRead())) {
      LOG.error("Cannot read report directory: {}", reportDir.getAbsolutePath());
      throw new IllegalStateException ("Spring bean '" + 
         SPRING_REPORT_PATH + "' resolves to " + reportDir.getAbsolutePath() + " which is not a readable directory");
    }

    final String compiledReportDirPath;
  
    if (applicationContext.containsBean(SPRING_COMPILED_REPORT_PATH))
    {
      compiledReportDirPath = applicationContext.getBean(SPRING_COMPILED_REPORT_PATH, String.class);
    } else {
      compiledReportDirPath = reportDirPath;
    }
    
    compiledReportPath = terminateInSeparatorChar(compiledReportDirPath);

    LOG.debug("Path for compiled report definitions: {}", compiledReportPath);

    // check that the compiled reports directory exists and has appropriate permissions
    final File compiledReportDir = new File(config.getServletContext().getRealPath(compiledReportPath));
    
    if (!compiledReportDir.exists()) {
      LOG.info("creating directory for compiled reports: {}", compiledReportPath);
      try {
        compiledReportDir.mkdir();
      } catch (Exception e) {
        LOG.error ("Could not create compiled report directory {}", compiledReportPath);
        throw new IllegalStateException (e);
      }
    }
    
    if (!(compiledReportDir != null && compiledReportDir.isDirectory() && compiledReportDir.canWrite())) {
      LOG.error("Cannot write to compiled report directory: {}", compiledReportDir.getAbsolutePath());
      throw new IllegalStateException ("Spring bean '" + 
         SPRING_COMPILED_REPORT_PATH + "' resolves to " + compiledReportDir.getAbsolutePath() + 
         " which is not a writable directory");
    }

    LOG.info("ReportingServlet initialized");
    
  }
  
  /**
   * Returns a database connection.
   * 
   * @return DB connection
   * @throws SQLException
   */
  protected final Connection getConnection() throws SQLException {
    return datasource.getConnection();
  }

  protected final String compiledFilePath (final String reportFile) {
    int extStart = reportFile.lastIndexOf('.');
    final String filename;
    
    if (extStart > -1)
    {
      filename = reportFile.substring(0, extStart);
    } else {
      filename = reportFile;
    }
    
    return getAbsolutePath(compiledReportPath + filename + ".jasper");
  }
  
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException {
    
    /* 
     * process the params from the request
     *   'type' - use to select output type (PDF, HTML, XLS)
     *   'report' - report definition file
     */
    final String outputType = request.getParameter(HTTP_PARAM_TYPE);
    final String reportFile = request.getParameter(HTTP_PARAM_REPORT);
    OutputType type = null;
    
    LOG.debug("request for report: {}, with format: {}", new Object[] { reportFile, outputType });
    
    if (outputType == null) {
      //default to HTML
      type = OutputType.HTML;
    } else {
      type = OutputType.valueOf(outputType);
    }
    
    if (reportFile == null || reportFile.trim().length() < 1) {
      LOG.error ("'report' argument is missing or invalid");
      response.sendError(400, "'report' argument is missing or invalid");
      return;
    }
    
    final String source = getAbsolutePath(reportPath + reportFile);
    final String destination = compiledFilePath(reportFile);
    final File compiledFile = new File(destination);
    
    if (!compiledFile.exists()) {      
      try {
        JasperCompileManager.compileReportToFile(source, destination);
      } catch (JRException e) {
        LOG.error ("failed to compile report file {} to {}: {}", 
          new Object[] { reportFile, destination, e.getMessage(), e });
        response.sendError(500, "Failed to compile report: " + e.getMessage());
        return;
      }
    }
    
    JasperPrint jasperPrint = null;
    
    try {
      jasperPrint = JasperFillManager.fillReport(destination, new HashMap<String, Object>(), getConnection());
    } catch (Exception e) {
      LOG.error("Failed to process report {}", destination);
      response.sendError(500, "Failed to process report: " + e.getMessage());
      return;
    }
    
    String exportName = reportFile.substring(0, reportFile.lastIndexOf('.')) 
        + '-' + (new Date()).getTime();
    
    switch (type) {
      case PDF: {
        exportName += ".pdf";
        response.setHeader("Content-Disposition", "inline; filename=\"" + exportName + "\"");
        response.setContentType(MIMETYPE_PDF);
        try {
          JasperExportManager.exportReportToPdfStream(jasperPrint, response.getOutputStream());
        } catch (JRException e) {
          LOG.error("Failed to export report to PDF", e);
          response.sendError(500, "Failed to export report to PDF");
          return;
        }
        break;
      }
      case XLS: {
        exportName += ".xls";
        response.setHeader("Content-Disposition", "inline; filename=\"" + exportName + "\"");
        JRXlsExporter exporterXLS = new JRXlsExporter(); 
        exporterXLS.setParameter(JRXlsExporterParameter.JASPER_PRINT, jasperPrint); 
        exporterXLS.setParameter(JRXlsExporterParameter.OUTPUT_STREAM, response.getOutputStream()); 
        exporterXLS.setParameter(JRXlsExporterParameter.IS_ONE_PAGE_PER_SHEET, Boolean.TRUE); 
        exporterXLS.setParameter(JRXlsExporterParameter.IS_DETECT_CELL_TYPE, Boolean.TRUE); 
        exporterXLS.setParameter(JRXlsExporterParameter.IS_WHITE_PAGE_BACKGROUND, Boolean.FALSE); 
        exporterXLS.setParameter(JRXlsExporterParameter.IS_REMOVE_EMPTY_SPACE_BETWEEN_ROWS, Boolean.TRUE); 
        try {
          exporterXLS.exportReport();
        } catch (JRException e) {
          LOG.error("Failed to export report to XLS", e);
          response.sendError(500, "Failed to export report to XLS");
          return;
        } 
        break;
      }
      default:
      case HTML: {
        response.setContentType(MIMETYPE_HTML);
        
        JRXhtmlExporter exporter = new JRXhtmlExporter();
        
        request.getSession().setAttribute(ImageServlet.DEFAULT_JASPER_PRINT_SESSION_ATTRIBUTE, jasperPrint);
        
        exporter.setParameter(JRExporterParameter.JASPER_PRINT, jasperPrint);
        exporter.setParameter(JRExporterParameter.OUTPUT_WRITER, response.getWriter());
        exporter.setParameter(JRHtmlExporterParameter.IMAGES_URI, "image?image=");
        
        try {
          exporter.exportReport();
        } catch (JRException e) {
          LOG.error("Failed to generate HTML report", e);
          response.sendError(500, "Failed to generate HTML report");
          return;
        }
        break;
      }
    }
  }

}
