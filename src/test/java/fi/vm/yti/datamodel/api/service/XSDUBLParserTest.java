package fi.vm.yti.datamodel.api.service;

import java.io.File;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.update.UpdateAction;
import org.junit.Test;

public class XSDUBLParserTest {


    @Test
    public void parseUBLSchemas() {

        XSDParser xsdParser = new XSDParser("http://example.org#","en");

        xsdParser.parseXSDFromFile(new File("src/test/resources/ubl/UBL-CommonAggregateComponents-2.1.xsd"));

        xsdParser.convert();

        xsdParser.debug();


    }

}
