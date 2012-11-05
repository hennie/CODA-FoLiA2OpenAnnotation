/*
 * navis2oac - Simple converter from navis formatted files to Open Annotation
 * RDF/XML format. Complies to OAC phase II beta spec.
 */

package folia2oac;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 *
 * @author hennieb
 */
public class FoLiA2OAC {

    private SesameStore sesameStore;
    private List<EntityRecord> foliaEntityRecords;
    private TreeSet<FoliaToken> foliaTokens;
    private Map<String,FoliaToken> tokenMap;
    private String foliaFileName;
    private String sourceURLString;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        new FoLiA2OAC().startConversion(args);
    }

    public void startConversion(String[] args) {
        foliaEntityRecords = new ArrayList();
        foliaTokens = new TreeSet<FoliaToken>();
        tokenMap = new HashMap<String,FoliaToken>();
        sesameStore = new SesameStore();

        // Process arguments:
        // In this converter it is assumed that 'frog' has already generated a
        // FoliA file for the source text. Both are passed to the converter, because
        // the converter needs the original source to determine character offsets that
        // are not available from Folia
        //
        // TODO: in web service setup: input is a URL that can resolve to either
        // a text file or an OAS published Annotation Body. In the latter case, the
        // service extracts the source text from the RDF, sends it to frog to generate
        // the FoLiA document.
        List<String> arguments = processArgs(args);
        foliaFileName = arguments.get(0);
        sourceURLString = arguments.get(1);
            
        parseInputFile(foliaFileName, sourceURLString);

//        deriveImplicitInformation();

        // create triples and add them to the RDF store
        addTriplesToStore();

        // export OA graph to RDF/XML file (or output format?)
        sesameStore.exportToRDFXML(null);
    }

    public List<String> processArgs(String[] args) {
        List<String> arguments = new ArrayList<String>();
        arguments.addAll(Arrays.asList(args));

        return arguments;
    }

    public void parseInputFile(String foliaFileName, String sourceURLString) {
        System.out.println("processing: " + foliaFileName);

        Document doc = null;
        DocumentBuilder builder = null;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); // never forget this!

        try {
            builder = factory.newDocumentBuilder();

        } catch (ParserConfigurationException ex) {
            Logger.getLogger(FoLiA2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }

        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xpath = xPathFactory.newXPath();
        xpath.setNamespaceContext(new FoliaNamespaceContext());

        // read and parse FoLiA input file
        try {
            doc = builder.parse(foliaFileName);

        } catch (SAXException ex) {
            Logger.getLogger(FoLiA2OAC.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(FoLiA2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }

        determineFoliaTokens(doc, sourceURLString, xpath);
        extractInfoFromXML(doc, xpath);        
    }

    public void extractInfoFromXML(Document doc, XPath xpath) {
        try {
            // retrieve entities of 'frog-ner-nl' set
            XPathExpression expr = xpath.compile("//folia:entity[@set='http://ilk.uvt.nl/folia/sets/frog-ner-nl']");
            Object result = expr.evaluate(doc, XPathConstants.NODESET);
            NodeList nodes = (NodeList) result;

            for (int i = 0; i < nodes.getLength(); i++) {
                EntityRecord er = new EntityRecord();
                Node n = nodes.item(i);

                // get value of 'class' attribute
                String entityClass = n.getAttributes().getNamedItem("class").getNodeValue();
                er.entityClass = entityClass;

                // get id's of tokens that the entity refers to
                NodeList wrefList = (NodeList) n.getChildNodes();
                List<String> wrefIDs = new ArrayList<String>();

                for (int j= 0; j < wrefList.getLength(); j++) {
                    Node wref = wrefList.item(j);

                    if (wref.getNodeType() == Node.ELEMENT_NODE && wref.hasAttributes()) {
                        String idString = wref.getAttributes().getNamedItem("id").getNodeValue();
                        wrefIDs.add(idString);
                    }
                }
                er.tokenIDs = wrefIDs;

                // determine dcterms:creator
                expr = xpath.compile("//folia:entity-annotation[@set='http://ilk.uvt.nl/folia/sets/frog-ner-nl']/@annotator");
                Node cr = (Node) expr.evaluate(doc, XPathConstants.NODE);
                if (cr != null) {
                    er.creator = cr.getNodeValue();
                }

                // determine aggregate text (in right order)
                TreeSet<FoliaToken> entityTokens = new TreeSet<FoliaToken>();
                for (FoliaToken ft : foliaTokens) {
                    if (er.tokenIDs.contains(ft.wID)) {
                        entityTokens.add(ft);
                    }                    
                }
                
                String chars = "";
                for (FoliaToken et : entityTokens) {
                    chars += et.tokenText + " ";
                }
                chars = chars.trim();
                er.text = chars;

                foliaEntityRecords.add(er);
            }

        } catch (XPathExpressionException ex) {
            Logger.getLogger(FoLiA2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public void determineFoliaTokens(Document doc, String sourceURLString, XPath xpath) {
        try {
            XPathExpression expr = xpath.compile("//folia:w/@xml:id");
            Object result = expr.evaluate(doc, XPathConstants.NODESET);
            NodeList nodes = (NodeList) result;

            for (int i = 0; i < nodes.getLength(); i++) {
                String wID = nodes.item(i).getNodeValue();

                FoliaToken fToken = new FoliaToken(wID);
                foliaTokens.add(fToken);
                tokenMap.put(wID, fToken);

                // set token text
                String exprString = "//folia:w[@xml:id='" + wID + "']/folia:t/text()";
                expr = xpath.compile(exprString);

                Node tNode = (Node) expr.evaluate(doc, XPathConstants.NODE);
                if (tNode != null) {
                    fToken.tokenText = tNode.getNodeValue();
                }
            }
        } catch (XPathExpressionException ex) {
            Logger.getLogger(FoLiA2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }

        // foliaTokens is ordered. In order, determine position of token in source text
        int index = 0;

        // read source file into string
        String sourceText = "";
        try {
            URL sourceURL = new URL(sourceURLString);
            sourceText = retrieveText(sourceURL, "UTF-8");

            // TODO: SOLVE THIS, for the demo quick and dirty fix
            sourceText = sourceText.replace("&amp;", "&");

        } catch (IOException ex) {
            Logger.getLogger(FoLiA2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }

        // sourceURL is either a plain text file or an RDF snippet containing ContentAsText:chars
        // TODO: do decent extraction of RDF property value

        if (sourceText.contains("<rdf:RDF")) { // quick and dirty RDF check
            // extract plain text from it
            int startOfChars = sourceText.indexOf("<chars");
            int startOfValue = sourceText.indexOf(">", startOfChars) + 1;

            sourceText = sourceText.substring(startOfValue,
                    sourceText.indexOf("</chars>"));
        }
        
        if (!"".equals(sourceText)) {
            for (FoliaToken f : foliaTokens) {
                index = sourceText.indexOf(f.tokenText, index);
                f.offset = index;
                f.range = f.tokenText.length();
                index += f.range;
            }
        }
    }

    /**
     *   
     */
    public void deriveImplicitInformation() {
        for (EntityRecord entityRecord : foliaEntityRecords) {
            System.out.println(entityRecord.toString());

        }
    }

    public void addTriplesToStore() {

        Calendar cal = Calendar.getInstance();
        String currentDateString = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(cal.getTime());

        for (EntityRecord er : foliaEntityRecords) {   // loop entity records

            // create EntityAnnotation
            URI annotationURI = URI.create("urn:uuid:" + UUID.randomUUID());
            URI entityBodyURI = URI.create("urn:uuid:" + UUID.randomUUID());

            sesameStore.addTriple(annotationURI, SesameStore.RDF_TYPE, SesameStore.OAC_ANNOTATION);
            sesameStore.addTriple(annotationURI, SesameStore.RDF_TYPE, SesameStore.CP_ENTITYANNOTATION);
            sesameStore.addTriple(annotationURI, SesameStore.OAC_HASBODY, entityBodyURI);

            sesameStore.addTriple(annotationURI, SesameStore.CP_CHARS, er.text);

            sesameStore.addTriple(annotationURI, SesameStore.DC_TITLE, er.entityClass + " - " + er.text);
            sesameStore.addTriple(annotationURI, SesameStore.DCTERMS_CREATOR , er.creator);
            sesameStore.addTriple(annotationURI, SesameStore.DCTERMS_CREATED , currentDateString);

            // first write oac:hasTarget triples, since OAS otherwise does not accept them

            Map<String,URI> targetURIMap = new HashMap<String,URI>();
            for (String tokenID : er.tokenIDs){
                URI targetURI = URI.create("urn:uuid:" + UUID.randomUUID());
                sesameStore.addTriple(annotationURI, SesameStore.OAC_HASTARGET, targetURI);

                targetURIMap.put(tokenID, targetURI);
            }

            // targets
            for (String tokenID : er.tokenIDs) {
                // get FoliaToken for tokenID
                FoliaToken fToken = tokenMap.get(tokenID);
                URI targetURI = targetURIMap.get(tokenID);

                // ConstrainedTarget + Constraint
                URI textConstraintURI = URI.create("urn:uuid:" + UUID.randomUUID());
                URI sourceURI = URI.create(sourceURLString);

                sesameStore.addTriple(targetURI, SesameStore.RDF_TYPE, SesameStore.OAC_CONSTRAINEDTARGET);
                sesameStore.addTriple(targetURI, SesameStore.OAC_CONSTRAINS, sourceURI);
                sesameStore.addTriple(targetURI, SesameStore.OAC_CONSTRAINEDBY, textConstraintURI);

                String cText = "\"<textsegment offset=\""
                    + fToken.offset
                    + "\" range=\""
                    + fToken.range + "\"/>\"";
                sesameStore.addTriple(textConstraintURI, SesameStore.RDF_TYPE, SesameStore.OAC_CONSTRAINT);
                sesameStore.addTriple(textConstraintURI, SesameStore.RDF_TYPE, SesameStore.CP_INLINETEXTCONSTRAINT);
                sesameStore.addTriple(textConstraintURI, SesameStore.RDF_TYPE, SesameStore.CNT_CONTENTASTEXT);
                sesameStore.addTriple(textConstraintURI, SesameStore.CNT_CHARS, cText);
                sesameStore.addTriple(textConstraintURI, SesameStore.CNT_CHARACTERENCODING, "UTF-8");
            }

            // ... and it's text Body
            sesameStore.addTriple(entityBodyURI, SesameStore.RDF_TYPE, SesameStore.CNT_CONTENTASTEXT);
            sesameStore.addTriple(entityBodyURI, SesameStore.RDF_TYPE, SesameStore.OAC_BODY);
            sesameStore.addTriple(entityBodyURI, SesameStore.CNT_CHARS, er.entityClass);
            sesameStore.addTriple(entityBodyURI, SesameStore.CNT_CHARACTERENCODING, "UTF-8");
        }
    }

    // copy-paste from example on the web
    public static String retrieveText(URL url, String csName)
                throws IOException {
        Charset cs = Charset.forName(csName);
        return readFile(url, cs);
    }

    public static String readFile(URL url, Charset cs)
                throws IOException {

        InputStream stream = url.openStream();

        try {

            Reader reader = new BufferedReader(new InputStreamReader(stream, cs));
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        } finally {
            // Potential issue here: if this throws an IOException,
            // it will mask any others. Normally I'd use a utility
            // method which would log exceptions and swallow them

             stream.close();
        }
    }

    protected class FoliaNamespaceContext implements NamespaceContext {

        public String getNamespaceURI(String prefix) {
            if (prefix == null) throw new NullPointerException("Null prefix");
            else if ("folia".equals(prefix)) return "http://ilk.uvt.nl/folia";
            else if ("xml".equals(prefix)) return XMLConstants.XML_NS_URI;
            return XMLConstants.NULL_NS_URI;
        }

        // This method isn't necessary for XPath processing.
        public String getPrefix(String uri) {
            throw new UnsupportedOperationException();
        }

        // This method isn't necessary for XPath processing either.
        public Iterator getPrefixes(String uri) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * FoliaToken sorts on basis of FoLiA ids.
     */
    private class FoliaToken implements Comparable<FoliaToken> {

        protected String wID;
        protected String tokenText;
        protected int offset = -1;
        protected int range = -1;
        protected int p;  // paragraph number
        protected int s;  // sentence number
        protected int w;  // word number

        public FoliaToken(String wID) {
            this.wID = wID;

            int pPos = wID.indexOf(".p.");
            int sPos = wID.indexOf(".s.");
            int wPos = wID.indexOf(".w.");

            if (pPos >= 0 && sPos >= 0 && wPos >= 0) {
                p = Integer.parseInt(wID.substring(pPos + 3, sPos));
                s = Integer.parseInt(wID.substring(sPos + 3, wPos));
                w = Integer.parseInt(wID.substring(wPos +3));
            }
        }

        public int compareTo(FoliaToken fToken) {
            if (p < fToken.p) return -1;
            else if (p > fToken.p) return 1;
            else {
                if (s < fToken.s) return -1;
                else if (s > fToken.s) return 1;
                else {
                    if (w < fToken.w) return -1;
                    else if (w > fToken.w) return 1;
                    else return 0;
                }
            }
        }
    }

    private class EntityRecord {
        protected String entityClass;
        protected List<String> tokenIDs;
        protected String creator;
        protected String text;

        public EntityRecord() {
        }
    }
}
