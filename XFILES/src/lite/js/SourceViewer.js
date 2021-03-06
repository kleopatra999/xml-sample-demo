
/* ================================================  
 * Oracle XFiles Demonstration.  
 *    
 * Copyright (c) 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ================================================
 */

function onPageLoaded() {
  viewSource();
}

function viewSource() {

  var contentType = resource.selectNodes('/res:Resource/res:ContentType',xfilesNamespaces).item(0).firstChild.nodeValue;
  var displayName = resource.selectNodes('/res:Resource/res:DisplayName',xfilesNamespaces).item(0).firstChild.nodeValue;
  if (( contentType == "text/xml") || ( contentType ==  "application/vnd.oracle-csx")) {
    try {
      contentXML = loadXMLDocument(resourceURL);
      prettyPrintXML(contentXML,document.getElementById('sourcearea'));
    }
    catch (e) {
      if (e.status == "AccessDenied") {
        showUserErrorMessage("Unable to access : " + e.target + ". Access Denied.");
      }
      else {
				error = new xfilesException('SourceViewer.viewSource',12, resourceURL, e);
    		throw error;
      } 
    }
  }
  else {
    try {
      resourceContent = getDocumentContent(resourceURL);
      document.getElementById("sourcearea").value = resourceContent;
      if (displayName.substring(displayName.lastIndexOf(".")+1).toUpperCase() == "SQL") {
      	document.getElementById("executeQuery").style.display="block";
      }
    }
    catch (e) {
      if (e.status == "AccessDenied") {
        showUserErrorMessage("Unable to access : " + e.target + ". Access Denied.");
      }
      else {
				error = new xfilesException('SourceViewer.viewSource',12, resourceURL, e);
    		throw error;
      } 
    }
  }
}                                                                                                                 

 