package fi.vm.yti.datamodel.api.endpoint.imports;
import java.io.InputStream;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fi.vm.yti.datamodel.api.security.AuthorizationManager;
import fi.vm.yti.datamodel.api.service.GraphManager;
import fi.vm.yti.datamodel.api.service.JenaClient;
import fi.vm.yti.datamodel.api.service.JerseyResponseManager;
import fi.vm.yti.datamodel.api.service.XSDParser;
import fi.vm.yti.datamodel.api.utils.LDHelper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;

@Component
@Path("/importXSD")
@Api(tags = { "Admin" })
public class ImportXSD {

    private final JenaClient jenaClient;
    private final GraphManager graphManager;
    private final AuthorizationManager authManager;
    private final JerseyResponseManager jerseyResponseManager;
    private final XSDParser xsdParser;
    private static final Logger logger = LoggerFactory.getLogger(ImportXSD.class.getName());

    @Autowired
    ImportXSD(JenaClient jenaClient,
              GraphManager graphManager,
              JerseyResponseManager jerseyResponseManager,
              AuthorizationManager authManager,
              XSDParser xsdParser) {
        this.jenaClient = jenaClient;
        this.graphManager = graphManager;
        this.authManager = authManager;
        this.jerseyResponseManager = jerseyResponseManager;
        this.xsdParser = xsdParser;
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importXSD(@ApiParam(value="Graph ID", required = true) @QueryParam("graphID") final String graphID,
                              @ApiParam(value="Language of the Schema", required = true) @QueryParam("lang") final String lang,
                              @FormDataParam(value="XSD payload") final InputStream inputStream) {

        if (!authManager.hasRightToImport()) {
            return jerseyResponseManager.unauthorized();
        }

        try {
            if(LDHelper.isInvalidIRI(graphID)) {
                return jerseyResponseManager.invalidIRI();
            }

            if(jenaClient.isInCore(graphID)) {
                logger.debug("Importing XSD to " + graphID);
                logger.debug("Input stream: " + inputStream.available());
                xsdParser.init(graphID+"#", lang);
                xsdParser.parseXSD(inputStream);
                xsdParser.convert();
                xsdParser.debug();
            } else {
                return jerseyResponseManager.invalidIRI();
            }
        } catch(Exception ex) {
            ex.printStackTrace();
            logger.warn("Could not parse XML schema. Combine local imports to single schema");
        }
        return jerseyResponseManager.ok();
    }


}
