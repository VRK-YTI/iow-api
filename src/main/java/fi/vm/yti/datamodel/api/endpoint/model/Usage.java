/*
 * Licensed under the European Union Public Licence (EUPL) V.1.1
 */
package fi.vm.yti.datamodel.api.endpoint.model;

import fi.vm.yti.datamodel.api.service.EndpointServices;
import fi.vm.yti.datamodel.api.service.IDManager;
import fi.vm.yti.datamodel.api.service.JerseyClient;
import fi.vm.yti.datamodel.api.service.JerseyResponseManager;
import fi.vm.yti.datamodel.api.service.NamespaceManager;
import fi.vm.yti.datamodel.api.utils.LDHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.apache.jena.iri.IRI;
import org.apache.jena.iri.IRIException;
import org.apache.jena.query.ParameterizedSparqlString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import java.util.Map;

// Returns all known references to the given resource. Resource ID and Model ID are alternative parameters

@Component
@Path("v1/usage")
@Tag(name = "Resource" )
public class Usage {

    private final IDManager idManager;
    private final JerseyResponseManager jerseyResponseManager;
    private final NamespaceManager namespaceManager;
    private final EndpointServices endpointServices;
    private final JerseyClient jerseyClient;

    @Autowired
    Usage(IDManager idManager,
          JerseyResponseManager jerseyResponseManager,
          NamespaceManager namespaceManager,
          EndpointServices endpointServices,
          JerseyClient jerseyClient) {

        this.idManager = idManager;
        this.jerseyResponseManager = jerseyResponseManager;
        this.namespaceManager = namespaceManager;
        this.endpointServices = endpointServices;
        this.jerseyClient = jerseyClient;
    }

    @GET
    @Produces("application/ld+json")
    @Operation(description = "Get related resources with resource, model or concept uri")
    @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Usage message returned"),
        @ApiResponse(responseCode = "400", description = "Invalid ID supplied"),
        @ApiResponse(responseCode = "403", description = "Invalid IRI in parameter"),
        @ApiResponse(responseCode = "404", description = "Service not found") })
    public Response getUsage(
        @Parameter(description = "Resource ID") @QueryParam("id") String id,
        @Parameter(description = "Model ID") @QueryParam("model") String model,
        @Parameter(description = "Concept ID") @QueryParam("concept") String concept) {

        IRI resourceIRI = null;
        IRI modelIRI = null;
        IRI conceptIRI = null;

        try {
            if (id != null && !id.equals("undefined")) resourceIRI = idManager.constructIRI(id);
            if (model != null && !model.equals("undefined")) modelIRI = idManager.constructIRI(model);
            if (concept != null && !concept.equals("undefined")) conceptIRI = idManager.constructIRI(concept);
        } catch (NullPointerException e) {
            return jerseyResponseManager.invalidParameter();
        } catch (IRIException e) {
            return jerseyResponseManager.invalidIRI();
        }

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        Map<String, String> namespaces = namespaceManager.getCoreNamespaceMap();
        namespaces.putAll(LDHelper.PREFIX_MAP);
        pss.setNsPrefixes(namespaces);

        // wrap this in OPTIONAL if no specific resourceModel was given
        String conceptResourceModelClause =
            "?resource rdfs:isDefinedBy ?resourceModel . ";
        if (modelIRI == null) {
            conceptResourceModelClause = String.format("OPTIONAL { %s } ",
                conceptResourceModelClause);
        }

        // wrap this in OPTIONAL if no specific usageModel was given
        String resourceModelClause =
            "?usage rdfs:isDefinedBy ?usageModel . "
            + "GRAPH ?usageModel {"
            + "?usageModel a ?usageModelType . "
            + "?usageModel rdfs:label ?usageModelLabel . "
            + "?usageModel dcap:preferredXMLNamespaceName ?usageNamespace . "
            + "?usageModel dcap:preferredXMLNamespacePrefix ?usagePrefix . "
            + "} ";
        if (modelIRI == null) {
            resourceModelClause = String.format("OPTIONAL { %s } ",
                resourceModelClause);
        }

        String conceptQueryString = "CONSTRUCT  { "
            + "?concept dcterms:isReferencedBy ?resource . "
            + "?concept skos:prefLabel ?prefLabel . "
            + "?resource a ?type . "
            + "?resource rdfs:label ?label . "
            + "?resource rdfs:comment ?comment . "
            + "?resource rdfs:isDefinedBy ?resourceModel . "
            + "?resourceModel a ?modelType . "
            + "?resourceModel rdfs:label ?modelLabel . "
            + "?resourceModel dcap:preferredXMLNamespaceName ?namespace . "
            + "?resourceModel dcap:preferredXMLNamespacePrefix ?prefix . "
            + "} WHERE { "
            + "GRAPH ?resource { "
            + "?resource dcterms:subject ?concept . "
            + "?resource a ?type . "
            + "?resource ?usageNamePredicate ?label . "
            + "VALUES ?usageNamePredicate { rdfs:label sh:name }"
            + "OPTIONAL { ?resource rdfs:comment ?comment . }"
            + conceptResourceModelClause
            + "GRAPH ?resourceModel {"
            + "?resourceModel a ?modelType . "
            + "?resourceModel rdfs:label ?modelLabel . "
            + "?resourceModel dcap:preferredXMLNamespaceName ?namespace . "
            + "?resourceModel dcap:preferredXMLNamespacePrefix ?prefix . "
            + "}"
            + "}}";

        String queryString = "CONSTRUCT  { "
            + "?resource a ?type . "
            + "?resource sh:name ?label . "
            + "?resource rdfs:isDefinedBy ?resourceModel . "
            + "?resourceModel a ?modelType . "
            + "?resourceModel rdfs:label ?modelLabel . "
            + "?resourceModel dcap:preferredXMLNamespaceName ?namespace . "
            + "?resourceModel dcap:preferredXMLNamespacePrefix ?prefix . "
            + "?resource dcterms:isReferencedBy ?usage . "
            + "?usage a ?usageType . "
            + "?usage rdfs:label ?usageLabel . "
            + "?usage rdfs:isDefinedBy ?usageModel . "
            + "?usageModel a ?usageModelType . "
            + "?usageModel rdfs:label ?usageModelLabel . "
            + "?usageModel dcap:preferredXMLNamespaceName ?usageNamespace . "
            + "?usageModel dcap:preferredXMLNamespacePrefix ?usagePrefix . "
            + "} WHERE { "
            + "GRAPH ?resource { "
            + "?resource a ?type . "
            + "?resource ?resourceNamePredicate ?label . "
            + "VALUES ?resourceNamePredicate { rdfs:label sh:name }"
            + "?resource rdfs:isDefinedBy ?resourceModel . }"
            + "GRAPH ?resourceModel {"
            + "?resourceModel a ?modelType . "
            + "?resourceModel rdfs:label ?modelLabel . "
            + "?resourceModel dcap:preferredXMLNamespaceName ?namespace . "
            + "?resourceModel dcap:preferredXMLNamespacePrefix ?prefix . "
            + "}"
            + "GRAPH ?usage { "
            + "?subject ?property ?resource . "
            + "?usage a ?usageType . "
            + "?usage ?usageNamePredicate ?usageLabel . "
            + "VALUES ?usageNamePredicate { rdfs:label sh:name }"
            + "}"
            + resourceModelClause
            + "FILTER(?usage!=?resource && ?subject!=?resource && ?usage!=?usageModel)"
            + "}";

        String modelQueryString = "CONSTRUCT  { "
            + "?resourceModel a ?modelType . "
            + "?resourceModel rdfs:label ?modelLabel . "
            + "?resourceModel dcterms:isReferencedBy ?usage . "
            + "?resourceModel dcap:preferredXMLNamespaceName ?namespace . "
            + "?resourceModel dcap:preferredXMLNamespacePrefix ?prefix . "
            + "?usage a ?usageType . "
            + "?usage rdfs:label ?usageLabel . "
            + "?usage rdfs:isDefinedBy ?usageModel . "

            + "?usageModel a ?usageModelType . "
            + "?usageModel rdfs:label ?usageModelLabel . "
            + "?usageModel dcap:preferredXMLNamespaceName ?usageNamespace . "
            + "?usageModel dcap:preferredXMLNamespacePrefix ?usagePrefix . "

            + "} WHERE { "
            + "GRAPH ?resource { "
            + "?resource a ?type . "
            + "?resource ?labelProperty ?label . "
            + "VALUES ?labelProperty { rdfs:label sh:name } "
            + "?resource rdfs:isDefinedBy ?resourceModel . }"
            + "GRAPH ?resourceModel {"
            + "?resourceModel a ?modelType . "
            + "?resourceModel rdfs:label ?modelLabel . "
            + "?resourceModel dcap:preferredXMLNamespaceName ?namespace . "
            + "?resourceModel dcap:preferredXMLNamespacePrefix ?prefix . "
            + "}"
            + "GRAPH ?usage { "
            + "?subject ?property ?resource . "
            + "?usage a ?usageType . "
            + "?usage ?usageLabelPredicate ?usageLabel . "
            + "VALUES ?usageLabelPredicate { rdfs:label sh:name }"
            + "?usage rdfs:isDefinedBy ?usageModel . "
            + "}"
            + "FILTER(?usageModel!=?resourceModel && ?subject!=?resource)"
            + "GRAPH ?usageModel {"
            + "?usageModel a ?usageModelType . "
            + "?usageModel rdfs:label ?usageModelLabel . "
            + "?usageModel dcap:preferredXMLNamespaceName ?usageNamespace . "
            + "?usageModel dcap:preferredXMLNamespacePrefix ?usagePrefix . "
            + "}"
            + "}";

        if (resourceIRI != null) {
            pss.setCommandText(queryString);
            pss.setIri("resource", resourceIRI);
            if (modelIRI != null) {
                pss.setIri("usageModel", modelIRI);
            }
        } else if (conceptIRI != null) {
            pss.setCommandText(conceptQueryString);
            pss.setIri("concept", conceptIRI);
            if (modelIRI != null) {
                pss.setIri("resourceModel", modelIRI);
            }
        } else if (modelIRI != null) {
            pss.setCommandText(modelQueryString);
            pss.setIri("resourceModel", modelIRI);
        } else return jerseyResponseManager.invalidParameter();

        return jerseyClient.constructGraphFromService(pss.toString(), endpointServices.getCoreSparqlAddress());
    }
}
