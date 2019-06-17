package fi.vm.yti.datamodel.api.service;

import java.io.File;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.update.UpdateAction;
import org.junit.Test;

public class XSDParserTest {


    @Test
    public void parseVRKSchemas() {

        XSDParser xsdParser = new XSDParser("http://example.org#","ex","fi");

        xsdParser.parseXSDFromFile(new File("src/test/resources/vrk/PERUSSANOMA.xsd"));

        xsdParser.convert();

        xsdParser.debug();

    }

}
