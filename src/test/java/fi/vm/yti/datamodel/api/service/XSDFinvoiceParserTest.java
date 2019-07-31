package fi.vm.yti.datamodel.api.service;

import java.io.File;

import org.junit.Test;

public class XSDFinvoiceParserTest {


    @Test
    public void parseVRKSchemas() {

        XSDParser xsdParser = new XSDParser("http://example.org#","en");

        xsdParser.parseXSDFromFile(new File("src/test/resources/finvoice/Finvoice3.xsd"));

        xsdParser.convert();

        xsdParser.debug();

    }

}
