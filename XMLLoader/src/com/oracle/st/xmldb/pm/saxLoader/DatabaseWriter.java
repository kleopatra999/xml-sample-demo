
/* ================================================  
 *    
 * Copyright (c) 2015 Oracle and/or its affiliates.  All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ================================================ 
 */

package com.oracle.st.xmldb.pm.saxLoader;

import com.oracle.st.xmldb.pm.common.baseApp.BaseApplication;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import java.text.SimpleDateFormat;

import java.util.Calendar;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.Locale;


import java.util.StringTokenizer;

import java.util.Vector;

import javax.xml.transform.Result;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleResultSet;

import oracle.xdb.XMLType;
import oracle.xml.binxml.BinXMLException;
import oracle.xml.parser.v2.XMLDocument;

import oracle.xml.parser.v2.XMLElement;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Text;


public class DatabaseWriter extends Thread
{
  public static final boolean DEBUG = false;
  
  private Locale locale = new Locale(Locale.ENGLISH.toString(), "US");
  private static String XML_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ss.S";
  private SimpleDateFormat sdf = new SimpleDateFormat(XML_FORMAT_STRING);
     
  public static final String ERROR_HANDLER = "%ERROR%";   
  public static final String ERROR_REPORT = "DatabaseWriterError"; 
  public static final String COLUMNS_ELEMENT = "Columns";
  public static final String XPATH_COLUMN_MAPPINGS_ELEMENT = "ColumnMappings";
  public static final String EXCEPTION_ELEMENT = "Exception";
  public static final String COLUMN_VALUES_ELEMENT = "ColumnValues";
  public static final String VALUE_ELEMENT = "Value";
  
  private XMLLoader processor;
  private Connection connection;
  private String errorTable;
  private int maxSQLErrors;
  private int maxSQLInserts;

  private Hashtable xpathToTableMappings       = null;
  private Hashtable tableColumnMappings        = null; 
  private Hashtable tableColumnXPathMappings   = null; 
  
  private boolean clientSideEncoding = false;
  private boolean logServerStats     = false;
  
  private int bufferSize;
  private int commitCharge;

  private int taskCount = 0;
  private int batchCount = 0;
  private int successCount = 0;
  private int errorCount = 0;
  
  private double byteCount;

  private Calendar startTime;
  private Calendar endTime;

  private Hashtable statementCache;
  private String elementXPath;
  private Hashtable columnValues;
  
  private long bytesSentByServer = 0;
  private long bytesReadByServer = 0;
  private long networkRoundtrips = 0;
  
  private long dbCPU = 0;
  private long clientCPU = 0;
  
  public DatabaseWriter(XMLLoader processor) {
     this.processor = processor;
  }

   protected void setParameters(Connection dbConnection, String errorTable, int commitCharge, int maxSQLErrors, int maxSQLInserts, int bufferSize, boolean clientSideEncoding, boolean logServerStats) 
   
   throws BinXMLException, SQLException {
      this.connection = dbConnection;
      this.commitCharge = commitCharge;
      this.bufferSize = bufferSize;
      this.errorTable = errorTable;
      this.maxSQLErrors = maxSQLErrors;
      this.maxSQLInserts = maxSQLInserts;
      this.clientSideEncoding = clientSideEncoding;
      this.logServerStats = logServerStats;
   }
      
   protected void setXPathToTableMappings(Hashtable mappings) {
       this.xpathToTableMappings = mappings;
   }

   protected void setTableColumnMappings(Hashtable mappings) {
       this.tableColumnMappings = mappings;
   }

    protected void setTableColumnXPathMappings(Hashtable mappings) {
        this.tableColumnXPathMappings = mappings;
    }

    private CallableStatement prepareSQLStatement(String sqlText)
    throws SQLException {
        CallableStatement sqlStatement = null; 
        try {
           sqlStatement = getConnection().prepareCall(sqlText);
        }
        catch (SQLException SQLe) {
          this.processor.logThread(SQLe);      
          if (sqlStatement != null) {
            sqlStatement.close();
          }
        }
        return sqlStatement;
      }

      private void initializeStatementCache() 
      throws SQLException {
        
          this.statementCache = new Hashtable();

          String statementText = "insert into " + errorTable + " values ( :OBJECT_VALUE )";
          CallableStatement statement = prepareSQLStatement(statementText);
          this.statementCache.put(DatabaseWriter.ERROR_HANDLER, statement);;
          
          Enumeration tableNames = this.tableColumnMappings.keys();
          while (tableNames.hasMoreElements()) {
              String tableName = (String) tableNames.nextElement();
              String columnList = (String) this.tableColumnMappings.get(tableName);
              statementText   = "insert into \"" + tableName  + "\" (" + columnList + ")";
              String bindList = ":B_" + columnList.replace(",",",:B_");
              bindList = bindList.replace("\"","");
              statementText = statementText + " values (" + bindList + ")";
              this.processor.logThread("Table " + tableName + ". Statement = " + statementText);
              statement = prepareSQLStatement(statementText);
              this.statementCache.put(tableName, statement);
          }

      }

    protected void setPath(String xpath) {
        this.elementXPath = xpath;
    }

    protected void setColumnValues(Hashtable columnValues) {
        this.columnValues = columnValues;
    }

   private void closeInsertStatements() throws SQLException {
       Enumeration e = this.statementCache.keys();
       while (e.hasMoreElements()) {
          String elementName  = (String) e.nextElement();
          CallableStatement s = (CallableStatement) statementCache.remove(elementName);
          s.close();
       }
   }

   private void updateByteCount(long bytes) {
       this.byteCount = this.byteCount + bytes;
   }
   private void setStartTime() {
      this.startTime = new GregorianCalendar(this.locale);
   }

  private void setEndTime() {
      this.endTime = new GregorianCalendar(this.locale);
  }

  private Connection getConnection() {
    return this.connection;
  }
  
  private XMLDocument makeErrorReport(String tableName, String columns, Hashtable xpathToColumnMappings,Exception err) throws IOException {
    XMLDocument doc = new XMLDocument();
    Element root = doc.createElementNS("", DatabaseWriter.ERROR_REPORT);
    doc.appendChild(root);
    Element e = doc.createElementNS("",XMLLoader.TABLE_ELEMENT);
    root.appendChild(e);
    if (tableName != null) {
      Text t = doc.createTextNode(tableName);
      e.appendChild(t);
    }
    e = doc.createElementNS("",DatabaseWriter.COLUMNS_ELEMENT);
    root.appendChild(e);
    if (columns != null) {
      Text t = doc.createTextNode(columns);
      e.appendChild(t);
    }

    e = (XMLElement) doc.createElementNS("",DatabaseWriter.EXCEPTION_ELEMENT);
    root.appendChild(e);
      if (err != null) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        err.printStackTrace(pw);
        pw.flush();
        Text t = doc.createTextNode(sw.toString());
        sw.close();
        e.appendChild(t);
      }

    XMLElement mappings = (XMLElement) doc.createElementNS("",DatabaseWriter.XPATH_COLUMN_MAPPINGS_ELEMENT);
    root.appendChild(mappings);
    if (xpathToColumnMappings != null) {
      Enumeration keys = xpathToColumnMappings.keys();
      while (keys.hasMoreElements()) {
        String key = (String ) keys.nextElement();                                
        e = doc.createElementNS("",XMLLoader.COLUMN_ELEMENT);
        mappings.appendChild(e);
        Attr a = doc.createAttributeNS("", XMLLoader.NAME_ATTRIBUTE);
        a.setNodeValue(key);
        e.setAttributeNode(a);
        a = doc.createAttributeNS("", XMLLoader.PATH_ATTRIBUTE);
        a.setNodeValue((String)xpathToColumnMappings.get(key));
        e.setAttributeNode(a);
      }
    }
    
      XMLElement values = (XMLElement) doc.createElementNS("",DatabaseWriter.COLUMN_VALUES_ELEMENT);
      root.appendChild(values);
      if (this.columnValues != null) {
        Enumeration keys = columnValues.keys();
        while (keys.hasMoreElements()) {
          String key = (String ) keys.nextElement();                                
          e = doc.createElementNS("",DatabaseWriter.VALUE_ELEMENT);
          values.appendChild(e);
          Attr a = doc.createAttributeNS("", XMLLoader.NAME_ATTRIBUTE);
          a.setNodeValue(key);
          e.setAttributeNode(a);
          Object value = columnValues.get(key);
          if (value instanceof String) {
            Text t = doc.createTextNode((String) value);
            e.appendChild(t);
          }
            if (value instanceof Document) {
              XMLDocument olddoc = (XMLDocument) value;
              Element newDocRoot = (Element) doc.importNode(olddoc.getDocumentElement(),true);
              e.appendChild(newDocRoot);
              olddoc.freeNode();
            }
        }
      }
      
    return doc;
  }
    private InputStream makeInputStream(Document doc)
    throws IOException, TransformerConfigurationException, TransformerException {

        ByteArrayOutputStream BAOS = new ByteArrayOutputStream();
        Source source = new DOMSource(doc); 
        Result result = new StreamResult(BAOS);
        TransformerFactory.newInstance().newTransformer().transform(source, result); 
        byte[] bytes = BAOS.toByteArray();
        updateByteCount(bytes.length);
        return new ByteArrayInputStream(bytes);
    }

    private void doCommit() throws SQLException {
        getConnection().commit();
        this.batchCount = 0;
    }
       
    
    private void logError(String tableName,String columns,Hashtable columnXPathMappings,Exception e) throws SQLException, IOException {
        doCommit();
        this.errorCount++;
        XMLDocument errorReport = makeErrorReport(tableName,columns,columnXPathMappings,e);
        this.processor.logThread(errorReport);
        CallableStatement s = (CallableStatement) this.statementCache.get(DatabaseWriter.ERROR_HANDLER);
        XMLType xml = new XMLType(this.getConnection(), errorReport);
        s.setObject(":OBJECT_VALUE",xml);
        s.execute();
        xml.close();
        doCommit();
    }
 
    protected void insertValues(String path, Hashtable columnValues) 
    throws Exception  {
                
        String tableName = null;
        CallableStatement statement = null;
        String columns = null;
        Hashtable columnXPathMappings = null;
        try {
            this.taskCount++;
            this.batchCount++;
            
            tableName = (String) this.xpathToTableMappings.get(path);
            statement = (CallableStatement) this.statementCache.get(tableName);
            columns = (String) this.tableColumnMappings.get(tableName);

            columnXPathMappings = (Hashtable) this.tableColumnXPathMappings.get(tableName);
            Vector xmlTypeCache = new Vector();
            
            StringTokenizer t = new StringTokenizer(columns,",");
            while (t.hasMoreTokens()) {                            
               String columnName =  t.nextToken().replace("\"","");
               String xpathExpression = (String) columnXPathMappings.get(columnName);
               columnName = "B_" + columnName;
               Object value = columnValues.get(xpathExpression);
               if ((value instanceof XMLDocument) || (value instanceof InputStream)) {
                  XMLType xml;
                  if (value instanceof XMLDocument) {
                    if (this.clientSideEncoding) { 
                      xml = new XMLType(getConnection(),makeInputStream((XMLDocument)value));     
                    }
                    else {
                      xml = new XMLType(getConnection(),(XMLDocument) value);     
                    }
//                    xml = new XMLType(getConnection(),(XMLDocument) value);     
                  }
                  else {
                    xml = new XMLType(getConnection(),(InputStream) value);     
                  }
                  if (this.clientSideEncoding) { 
                    xml.setPicklePreference(XMLType.XMLTYPE_PICKLE_AS_BINXML);            
                  }
                  statement.setObject(columnName, xml);
                  xmlTypeCache.add(xml);
               }
               else {
                 statement.setString(columnName,(String) value);
               }
            }
            statement.execute();
            Enumeration en = xmlTypeCache.elements();
            while (en.hasMoreElements()) {
                XMLType xml = (XMLType) en.nextElement();
                xml.close();
            }
            this.successCount++;
        }
        catch (SQLException SQLe) {
          logError(tableName,columns,columnXPathMappings,SQLe); 
          if (this.errorCount > this.maxSQLErrors) {
            Exception e = new Exception("Max SQL Errors Exceeded");
            throw e;
          }
        }
        if (this.batchCount == this.commitCharge) {
          this.processor.logThread("Inserted " + this.successCount + " records.");
          doCommit();
        }
        columnValues.clear();
    }
    
    private void getServerStats() 
    throws SQLException {

        String dbVersion = null;     
        OraclePreparedStatement ops = (OraclePreparedStatement) getConnection().prepareCall("select VERSION from V$INSTANCE");
        OracleResultSet rs = (OracleResultSet) ops.executeQuery();
        while (rs.next() ) {
            dbVersion = rs.getString(1);
        }
        rs.close();
        ops.close();
        
        OraclePreparedStatement getStats = (OraclePreparedStatement) getConnection().prepareCall("select\n" +
                                                                                                    "(select VALUE from V$MYSTAT where statistic#=:1) BYTES_SENT,\n"   +
                                                                                                    "(select VALUE from V$MYSTAT where statistic#=:2) BYTES_READ,\n"   +
                                                                                                    "(select VALUE from V$MYSTAT where statistic#=:3) ROUND_TRIPS,\n" +
                                                                                                    "(select VALUE from v$sess_time_model where stat_name = 'DB CPU' and SID =  sys_context('USERENV','SID')) DB_CPU\n" +
                                                                                                    "from dual");

        if (dbVersion.equals("11.2.0.3.0")) {
          getStats.setInt(1,BaseApplication.BYTES_SENT_BY_SERVER_11_2_0_3_0);
          getStats.setInt(2,BaseApplication.BYTES_READ_BY_SERVER_11_2_0_3_0);
          getStats.setInt(3,BaseApplication.NETWORK_ROUNDTRIPS_11_2_0_3_0);
        }
        else if (dbVersion.equals("12.1.0.1.0")) {
          getStats.setInt(1,BaseApplication.BYTES_SENT_BY_SERVER_12_1_0_1_0);
          getStats.setInt(2,BaseApplication.BYTES_READ_BY_SERVER_12_1_0_1_0);
          getStats.setInt(3,BaseApplication.NETWORK_ROUNDTRIPS_12_1_0_1_0);
        }
        else if (dbVersion.equals("12.1.0.2.0")) {
          getStats.setInt(1,BaseApplication.BYTES_SENT_BY_SERVER_12_1_0_2_0);
          getStats.setInt(2,BaseApplication.BYTES_READ_BY_SERVER_12_1_0_2_0);
          getStats.setInt(3,BaseApplication.NETWORK_ROUNDTRIPS_12_1_0_2_0);
        }
               
        rs = (OracleResultSet) getStats.executeQuery();
        while (rs.next()) {
             this.bytesSentByServer = this.bytesSentByServer + rs.getNUMBER(1).longValue();
             this.bytesReadByServer = this.bytesReadByServer + rs.getNUMBER(2).longValue();
             this.networkRoundtrips = this.networkRoundtrips + rs.getNUMBER(3).longValue();
             this.dbCPU             = dbCPU                  + rs.getNUMBER(4).longValue();
        }
        rs.close();
        getStats.close();
        
        ThreadMXBean bean = ManagementFactory.getThreadMXBean( );
        this.clientCPU = bean.isCurrentThreadCpuTimeSupported( ) ?  bean.getCurrentThreadCpuTime( ) : 0L;   

    }
    
    private void waitForDocument() 
    throws Exception {
      while (!this.processor.isProcessingComplete()) {
        synchronized(this) {
          if (this.columnValues != null) {
            insertValues(this.elementXPath,this.columnValues);
            this.columnValues = null;
            if ((this.maxSQLInserts > 0) && ((this.successCount % this.maxSQLInserts) == 0)) {
              this.processor.logThread("Initiating Connection Refresh. " + this.successCount + " Records Processed.");
              doCommit();
              this.getServerStats();
              this.connection.close();
              this.connection = this.processor.connectionManager.getNewConnection();
              this.connection.setAutoCommit(false);
              this.initializeStatementCache();
              this.processor.logThread("Connection Refresh Completed.");
            }
            this.processor.addToWriterQueue(this);
          }
          try {
            this.wait();
          }
          catch (InterruptedException ioe) {
          }
        }
      }
      if (this.columnValues != null) {
        insertValues(this.elementXPath,this.columnValues);
      }
    }

    public void run(){
      boolean normalCompletion = false;
      Exception fatalError = null;
 
      try {
        setStartTime();   
        initializeStatementCache();
        this.processor.logThread("Started.");
          
        try {
           waitForDocument();
           doCommit();
           closeInsertStatements();
           normalCompletion = true;
           this.processor.logThread("Completed.");
        }
        catch (Exception e) {
          fatalError = e;
          this.processor.logThread("Aborted. ",fatalError);
          getConnection().rollback();
        }
        finally {
          if (!getConnection().isClosed()) {
            if (this.logServerStats) {
              getServerStats();
            }
            getConnection().rollback();
            getConnection().close();
          }
        }
      }
      catch (Exception e) {
        fatalError = e;
        this.processor.logThread("Aborted. Unhandled ",fatalError);
      }
      finally {
        setEndTime();
        this.processor.removeDatabaseWriter(this,normalCompletion);
        this.processor.recordStatistics(new WriterStatistics(Thread.currentThread().getName(),
                                                             this.startTime,this.endTime,
                                                             this.taskCount,this.successCount,this.errorCount,this.byteCount,
                                                             fatalError,
                                                             this.bytesReadByServer,
                                                             this.bytesSentByServer,
                                                             this.networkRoundtrips,
                                                             this.dbCPU,this.clientCPU)); 
      }
    }

    
}
