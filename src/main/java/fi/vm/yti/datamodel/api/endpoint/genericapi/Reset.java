/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package fi.vm.yti.datamodel.api.endpoint.genericapi;

import fi.vm.yti.datamodel.api.config.ApplicationProperties;
import fi.vm.yti.datamodel.api.utils.GraphManager;
import fi.vm.yti.datamodel.api.utils.GroupManager;
import fi.vm.yti.datamodel.api.utils.NamespaceManager;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.core.Response;

/**
 * REST Web Service
 *
 * @author malonen
 */
@Path("reset")
@Api(tags = {"Admin"}, description = "DROP ALL and Recover")
public class Reset {

    @Context ServletContext context;

    @GET
    @ApiOperation(value = "Log user out", notes = "Removes session used by API")
      @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Logged out"),
      @ApiResponse(code = 400, message = "Not logged in")
  })
    public Response drop(@Context HttpServletRequest request) {
        Response.ResponseBuilder rb;
              
        if(ApplicationProperties.getDebugMode()) {
            GraphManager.deleteGraphs();
            GraphManager.createDefaultGraph();
            GroupManager.createDefaultGroups();
            NamespaceManager.addDefaultNamespacesToCore();
            //ConceptMapper.updateSchemesFromFinto();
        }
        
        rb = Response.status(Response.Status.OK);
     
        return rb.build();
        
    }
    
}