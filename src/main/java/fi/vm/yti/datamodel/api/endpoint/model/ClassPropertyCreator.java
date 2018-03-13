/*
 * Licensed under the European Union Public Licence (EUPL) V.1.1
 */
package fi.vm.yti.datamodel.api.endpoint.model;

import fi.vm.yti.datamodel.api.service.EndpointServices;
import fi.vm.yti.datamodel.api.service.GraphManager;
import fi.vm.yti.datamodel.api.service.IDManager;
import fi.vm.yti.datamodel.api.service.JerseyClient;
import fi.vm.yti.datamodel.api.service.JerseyResponseManager;
import fi.vm.yti.datamodel.api.utils.LDHelper;
import io.swagger.annotations.*;
import org.apache.jena.iri.IRI;
import org.apache.jena.iri.IRIException;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.util.SplitIRI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.util.UUID;

@Component
@Path("classProperty")
@Api(tags = {"Class"}, description = "Operations about property")
public class ClassPropertyCreator {

    private final IDManager idManager;
    private final GraphManager graphManager;
    private final EndpointServices endpointServices;
    private final JerseyClient jerseyClient;
    private final JerseyResponseManager jerseyResponseManager;

    @Autowired
    ClassPropertyCreator(IDManager idManager,
                         GraphManager graphManager,
                         EndpointServices endpointServices,
                         JerseyClient jerseyClient,
                         JerseyResponseManager jerseyResponseManager) {
        this.idManager = idManager;
        this.graphManager = graphManager;
        this.endpointServices = endpointServices;
        this.jerseyClient = jerseyClient;
        this.jerseyResponseManager = jerseyResponseManager;
    }

    @GET
    @Produces("application/ld+json")
    @ApiOperation(value = "Get property from model", notes = "More notes about this method")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid model supplied"),
            @ApiResponse(code = 404, message = "Service not found"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public Response json(
            @ApiParam(value = "Predicate ID", required = true) @QueryParam("predicateID") String predicateID,
            @ApiParam(value = "Predicate type", allowableValues="owl:DatatypeProperty,owl:ObjectProperty") @QueryParam("type") String type) {

        IRI predicateIRI, typeIRI;

        String service;
        String queryString;
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);

        try {
            // Parse IRI:s
            predicateIRI = idManager.constructIRI(predicateID);

            UUID classPropertyUUID = UUID.randomUUID();

            if (graphManager.isExistingServiceGraph(SplitIRI.namespace(predicateID))) {
                /* Local predicate */
                if(!graphManager.isExistingGraph(predicateIRI)) {
                    return jerseyResponseManager.invalidIRI();
                }

                service = endpointServices.getCoreSparqlAddress();

                queryString = "CONSTRUCT { "
                        + "?uuid sh:predicate ?predicate . "
                        + "?uuid dcterms:type ?predicateType . "
                        + "?uuid dcterms:type ?externalType . "
                        + "?uuid dcterms:created ?creation . "
                        + "?uuid dcterms:identifier ?localIdentifier . "
                        + "?uuid owl:equivalentProperty ?mappedPredicate . "
                        + "?uuid rdfs:label ?label . "
                        + "?uuid rdfs:comment ?comment . "
                        + "?uuid sh:valueShape ?valueClass . "
                        + "?uuid sh:datatype ?datatype . } "
                        + "WHERE { "
                        + "BIND(now() as ?creation) "
                        + "OPTIONAL { "
                        + "GRAPH ?predicate { "
                        + "?predicate rdfs:label ?label .  "
                        + "?predicate a ?predicateType . "
                        + "OPTIONAL { ?predicate owl:equivalentProperty ?mappedPredicate . } "
                        + "OPTIONAL{ ?predicate rdfs:comment ?comment . } "
                        + "OPTIONAL{ ?predicate a owl:DatatypeProperty . "
                        + "?predicate rdfs:range ?datatype . } "
                        + "OPTIONAL { ?predicate a owl:ObjectProperty . "
                        + "?predicate rdfs:range ?valueClass . }}}}";

                pss.setCommandText(queryString);
                pss.setIri("predicate", predicateIRI);
                pss.setIri("uuid", "urn:uuid:"+classPropertyUUID.toString());
                pss.setLiteral("localIdentifier", SplitIRI.localname(predicateID));

            } else {
             
         /* Removed predicate addition
         String predicateType = NamespaceManager.getExternalPredicateType(predicateIRI);
         
         if((predicateType==null || predicateType.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property")) && type!=null && !type.equals("undefined")) {
             String typeURI = type.replace("owl:", "http://www.w3.org/2002/07/owl#");
             typeIRI = IDManager.constructIRI(typeURI);
          } else {
             if(predicateType==null || predicateType.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property")) return JerseyResponseManager.invalidParameter();
             else typeIRI = IDManager.constructIRI(predicateType);
         } */

                if(type==null || type.equals("undefined"))
                    return jerseyResponseManager.invalidParameter();

                String typeURI = type.replace("owl:", "http://www.w3.org/2002/07/owl#");
                typeIRI = idManager.constructIRI(typeURI);


                service = endpointServices.getImportsSparqlAddress();

                /* TODO: ADD LANGUAGE TAG TO COMMENTS */

                queryString = "CONSTRUCT { "
                        + "?uuid sh:predicate ?predicate . "
                        + "?uuid dcterms:type ?predicateType . "
                        + "?uuid dcterms:created ?creation . "
                        + "?uuid dcterms:identifier ?localIdentifier . "
                        + "?uuid rdfs:label ?label . "
                        + "?uuid rdfs:comment ?comment . "
                        + "?uuid sh:class ?valueClass . "
                        + "?uuid sh:datatype ?prefDatatype . } "
                        + "WHERE { "
                        + "BIND(now() as ?creation) "
                        + "OPTIONAL { ?predicate rdfs:label ?labelStr . FILTER(LANG(?labelStr) = '') BIND(STRLANG(STR(?labelStr),'en') as ?label) }"
                        + "OPTIONAL { ?predicate rdfs:label ?label . FILTER(LANG(?label)!='') }"
                        + "VALUES ?commentPred { rdfs:comment skos:definition dcterms:description dc:description }"
                        + "OPTIONAL { ?predicate ?commentPred ?commentStr . FILTER(LANG(?commentStr) = '') BIND(STRLANG(STR(?commentStr),'en') as ?comment) } "
                        + "OPTIONAL { ?predicate ?commentPred ?comment . FILTER(LANG(?comment)!='') }"
                        + "OPTIONAL { ?predicate a owl:DatatypeProperty . "
                        + "?predicate rdfs:range ?datatype . "
                        + "BIND(IF(?datatype=rdfs:Literal,xsd:string,?datatype) as ?prefDatatype) } "
                        + "OPTIONAL { ?predicate a owl:ObjectProperty . ?predicate rdfs:range ?valueClass . } "
                        + "OPTIONAL { ?predicate a rdf:Property . "
                        + "FILTER NOT EXISTS { ?predicate a owl:DatatypeProperty . }"
                        + "?predicate rdfs:range rdfs:Literal . "
                        + "BIND(xsd:string as ?prefDatatype) } "
                        + "}";

                pss.setCommandText(queryString);
                pss.setIri("predicate", predicateIRI);
                pss.setLiteral("localIdentifier", SplitIRI.localname(predicateID));
                pss.setIri("uuid", "urn:uuid:"+classPropertyUUID.toString());
                pss.setIri("predicateType", typeIRI);

            }
        } catch (IRIException e) {
            return jerseyResponseManager.invalidIRI();
        }

        //logger.info(""+SplitIRI.localname(predicateID));

        return jerseyClient.constructNonEmptyGraphFromService(pss.toString(), service);
    }
}
