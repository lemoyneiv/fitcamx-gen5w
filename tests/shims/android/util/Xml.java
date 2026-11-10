package android.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

public final class Xml {
    public static XmlPullParser newPullParser() {
        return new StaxPullParser();
    }

    private static class StaxPullParser implements XmlPullParser {
        private XMLStreamReader r;
        private int eventType = START_DOCUMENT;

        @Override
        public void setInput(Reader in) throws XmlPullParserException {
            try {
                XMLInputFactory f = XMLInputFactory.newInstance();
                f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
                r = f.createXMLStreamReader(in);
                eventType = START_DOCUMENT;
            } catch (Exception e) {
                throw new XmlPullParserException(e.getMessage());
            }
        }

        @Override
        public int getEventType() throws XmlPullParserException {
            return eventType;
        }

        @Override
        public String getName() {
            if (eventType == START_TAG || eventType == END_TAG) {
                return r.getLocalName();
            }
            return null;
        }

        @Override
        public String nextText() throws XmlPullParserException, IOException {
            try {
                String txt = r.getElementText();
                eventType = END_TAG;
                return txt;
            } catch (Exception e) {
                throw new XmlPullParserException(e.getMessage());
            }
        }

        @Override
        public int next() throws XmlPullParserException, IOException {
            try {
                while (r.hasNext()) {
                    int c = r.next();
                    if (c == XMLStreamConstants.START_ELEMENT) return eventType = START_TAG;
                    if (c == XMLStreamConstants.END_ELEMENT) return eventType = END_TAG;
                    if (c == XMLStreamConstants.CHARACTERS) return eventType = TEXT;
                    if (c == XMLStreamConstants.END_DOCUMENT) return eventType = END_DOCUMENT;
                }
                return eventType = END_DOCUMENT;
            } catch (Exception e) {
                throw new XmlPullParserException(e.getMessage());
            }
        }

        @Override public void setFeature(String n, boolean s) {}
        @Override public boolean getFeature(String n) { return false; }
        @Override public void setProperty(String n, Object v) {}
        @Override public Object getProperty(String n) { return null; }
        @Override public void setInput(InputStream is, String enc) {}
        @Override public String getInputEncoding() { return "UTF-8"; }
        @Override public void defineEntityReplacementText(String e, String r) {}
        @Override public int getNamespaceCount(int d) { return 0; }
        @Override public String getNamespacePrefix(int p) { return null; }
        @Override public String getNamespaceUri(int p) { return null; }
        @Override public String getNamespace(String p) { return null; }
        @Override public int getDepth() { return 0; }
        @Override public String getPositionDescription() { return ""; }
        @Override public int getLineNumber() { return 0; }
        @Override public int getColumnNumber() { return 0; }
        @Override public boolean isWhitespace() { return false; }
        @Override public String getText() { return r.getText(); }
        @Override public char[] getTextCharacters(int[] h) { return null; }
        @Override public String getNamespace() { return null; }
        @Override public String getPrefix() { return null; }
        @Override public boolean isEmptyElementTag() { return false; }
        @Override public int getAttributeCount() { return 0; }
        @Override public String getAttributeNamespace(int i) { return null; }
        @Override public String getAttributeName(int i) { return null; }
        @Override public String getAttributePrefix(int i) { return null; }
        @Override public String getAttributeType(int i) { return null; }
        @Override public boolean isAttributeDefault(int i) { return false; }
        @Override public String getAttributeValue(int i) { return null; }
        @Override public String getAttributeValue(String ns, String n) { return null; }
        @Override public int nextToken() { return 0; }
        @Override public void require(int t, String ns, String n) {}
        @Override public int nextTag() { return 0; }
    }
}
