/*
 * Licensed under the European Union Public Licence (EUPL) V.1.1 
 */
package com.csc.fi.ioapi.api.concepts;

import com.csc.fi.ioapi.config.EndpointServices;
import com.csc.fi.ioapi.utils.JerseyJsonLDClient;
import com.csc.fi.ioapi.utils.LDHelper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import org.apache.jena.query.ParameterizedSparqlString;

/**
 * REST Web Service
 *
 * @author malonen
 */
@Path("conceptSchemes")
@Api(value = "/conceptSchemes", description = "Available concept schemes from Term editor")
public class ConceptsSchemes {

    @Context ServletContext context;
    EndpointServices services = new EndpointServices();
   
    
  @GET
  @Produces("application/ld+json")
  @ApiOperation(value = "Get available concepts", notes = "Lists terminologies from Termeditor")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Concepts"),
      @ApiResponse(code = 406, message = "Term not defined"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response vocab() {

          String queryString;
          ParameterizedSparqlString pss = new ParameterizedSparqlString();
          pss.setNsPrefixes(LDHelper.PREFIX_MAP);
            
          queryString = "CONSTRUCT { "
                      + "?scheme a skos:ConceptScheme . "
                      + "?scheme dcterms:identifier ?id . "
                      + "?scheme dcterms:title ?title .  "
                      + "?scheme dcterms:description ?description . "
                      + "?scheme dcterms:isFormatOf ?FintoLink . "
                      + "} WHERE { "
                      + "?concept a skos:Concept . "
                      + "?concept skos:prefLabel ?label . "
                      + "?concept skos:definition ?definition . "
                      + "?concept skos:inScheme ?scheme . "
                      + "?scheme dc:identifier ?id . "
                      + "?scheme dc:title ?title . "
                      + "?scheme dc:description ?description . "
                      + "?scheme dcterms:isFormatOf ?FintoLink . "
                      + "}";

  	  
          pss.setCommandText(queryString);
          
          return JerseyJsonLDClient.constructGraphFromService(pss.toString(), services.getTempConceptReadSparqlAddress());
          
       // return JerseyFusekiClient.getGraphResponseFromService("urn:csc:schemes", services.getTempConceptReadWriteAddress());      
      //    return JerseyJsonLDClient.getGraphFromTermedAPI(ApplicationProperties.getDefaultTermAPI()+"graphs");
  }
  
  
}
