This is an example of a Servlet for generating reports based on report definitions from
the JasperReports iReportDesigner (.jrxml files).

CAVEATS
=======

Two issues were encoutered in initial development that will need to be resolved for anything
other than this initial experiment:

1. Report definitions produced by iReport include an attribute that needs to be deleted
otherwise a ClassNotFoundException will be generated indicating that a Groovy class
cannot be found. The attribute to delete is:
    language="groovy"
2. Report definitions with images are not yet supported. It is not yet apparent where
image files need to be stored to cause the report exporters to embed them in generated
report documents, or whether it is possible to cause the report definition file to be
generated with the images embedded.

SETUP
=====

1. Build and deploy Kuali Coeus using a MySQL database.

2. Create several Award documents within Coeus

3. Edit the file src/main/webapp/WEB-INF/datasource.properties in this project to include
   credentials and connection information for the Kuali Coeus database.
   
4. Build and deploy this project.

5. Visit the URL:

    http://localhost:8080/kc-jasperreports?report=report.jrxml&type=HTML
    
   You can replace HTML with PDF or XLS for other formats