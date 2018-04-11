/*
 * Licensed under the European Union Public Licence (EUPL) V.1.1 
 */
package fi.vm.yti.datamodel.api.service;

import fi.vm.yti.datamodel.api.config.ApplicationProperties;
import fi.vm.yti.datamodel.api.utils.LDHelper;
import org.apache.jena.iri.IRI;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.update.UpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProvenanceManager {

    public static final Property generatedAtTime = ResourceFactory.createProperty("http://www.w3.org/ns/prov#", "generatedAtTime");

    private final EndpointServices endpointServices;
    private final ApplicationProperties properties;
    private final JenaClient jenaClient;

    @Autowired
    ProvenanceManager(EndpointServices endpointServices,
                      ApplicationProperties properties,
                      JenaClient jenaClient) {
        this.endpointServices = endpointServices;
        this.properties = properties;
        this.jenaClient = jenaClient;
    }

    public boolean getProvMode() {
        return properties.isProvenance();
    }
    
    /**
     * Put model to provenance graph
     * @param model Jena model
     * @param id IRI of the graph as String
     */
    public void putToProvenanceGraph(Model model, String id) {
      jenaClient.putModelToProv(id, model);
    }

    /**
     * Creates Provenance activity for the given resource
     * @param id ID of the resource
     * @param model Model containing the resource
     * @param provUUID Provenance UUID for the resource
     * @param email Email of the committing user
     */
    public void createProvenanceActivityFromModel(String id, Model model, String provUUID, String email) {
       putToProvenanceGraph(model, provUUID);
       createProvenanceActivity(id, provUUID, email);
    }

    /**
     * Returns query for creating the PROV Activity
     * @param graph ID of the resource
     * @param provUUID Provenance id of the resource
     * @param user Email of the committing user
     * @return UpdateRequest of the activity
     */

    public UpdateRequest createProvenanceActivityRequest(String graph, String provUUID, String user) {
        String query
                = "INSERT { "
                + "GRAPH ?graph { "
                + "?graph prov:startedAtTime ?creation . "
                + "?graph prov:generated ?jsonld . "
                + "?graph prov:used ?jsonld . "
                + "?graph a prov:Activity . "
                + "?graph prov:wasAttributedTo ?user . "
                + "?jsonld a prov:Entity . "
                + "?jsonld prov:wasAttributedTo ?user . "
                + "?jsonld prov:generatedAtTime ?creation . "
                + "}"
                + "GRAPH ?jsonld { "
                + "?graph a prov:Entity . "
                + "?graph dcterms:identifier ?versionID . }"
                + "}"
                + "WHERE { "
                + "BIND(now() as ?creation)"
                + "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);

        pss.setIri("graph", graph);
        pss.setIri("user", "mailto:"+user);
        pss.setIri("jsonld", provUUID);
        pss.setCommandText(query);
        return pss.asUpdate();
    }

    public void createProvenanceActivity(String graph, String provUUID, String user) {
        UpdateRequest queryObj = createProvenanceActivityRequest(graph, provUUID, user);
        jenaClient.updateToService(queryObj, endpointServices.getProvSparqlUpdateAddress());
    }

    public UpdateRequest createProvEntityRequest(String graph, String user, String provUUID) {
        String query
                = "DELETE { "
                + "GRAPH ?graph {"
                + "?graph prov:used ?oldEntity . "
                + "}"
                + "}"
                + "INSERT { "
                + "GRAPH ?graph { "
                + "?graph prov:generated ?jsonld . "
                + "?graph prov:used ?jsonld . "
                + "?jsonld a prov:Entity . "
                + "?jsonld prov:wasAttributedTo ?user . "
                + "?jsonld prov:generatedAtTime ?creation . "
                + "?jsonld prov:wasRevisionOf ?oldEntity . "
                + "}"
                + "GRAPH ?jsonld {"
                + "?graph a prov:Entity ."
                + "}"
                + "}"
                + "WHERE { "
                + "GRAPH ?graph { "
                + "?graph prov:used ?oldEntity . "
                + "}"
                + "BIND(now() as ?creation)"
                + "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);

        pss.setIri("graph", graph);
        pss.setIri("user", "mailto:"+user);
        pss.setIri("jsonld", provUUID);
        pss.setCommandText(query);
        return pss.asUpdate();
    }

    public void createProvEntity(String graph, String provUUID, String user) {
        UpdateRequest queryObj = createProvEntityRequest(graph, user, provUUID);
        jenaClient.updateToService(queryObj, endpointServices.getProvSparqlUpdateAddress());
    }

    /**
     * Creates PROV Entities and renames ID:s if changed
     * @param graph Graph of the resource
     * @param model Model containing the resource
     * @param user Email of the committing user
     * @param provUUID Provenance UUID for the resource
     * @param oldIdIRI Optional: Old IRI for the resource
     */
    public void createProvEntityBundle(String graph, Model model, String user, String provUUID, IRI oldIdIRI) {
      putToProvenanceGraph(model, provUUID);
      createProvEntity(graph, provUUID, user);
        if(oldIdIRI!=null) {
            renameID(oldIdIRI.toString(), graph);
        }
    }

    /**
     * Query that renames ID:s in provenance service
     * @param oldid Old id
     * @param newid New id
     * @return
     */
    public UpdateRequest renameIDRequest(String oldid, String newid) {

        String query
                = "INSERT { "
                + "GRAPH ?newid { "
                + "?newid prov:generated ?jsonld . "
                + "?newid prov:startedAtTime ?creation . "
                + "?newid prov:used ?any . "
                + "?newid a prov:Activity . "
                + "?newid prov:wasAttributedTo ?user . "
                + "?jsonld a prov:Entity . "
                + "?jsonld prov:wasAttributedTo ?user . "
                + "?jsonld prov:generatedAtTime ?creation . "
                + "?jsonld prov:wasRevisionOf ?oldEntity . "
                + "}}"
                + "WHERE { "
                + "GRAPH ?oldid { "
                + "?oldid prov:startedAtTime ?creation . "
                + "?oldid prov:generated ?jsonld . "
                + "?oldid prov:used ?any . "
                + "?oldid a prov:Activity . "
                + "?oldid prov:wasAttributedTo ?user . "
                + "?jsonld a prov:Entity . "
                + "?jsonld prov:wasAttributedTo ?user . "
                + "?jsonld prov:generatedAtTime ?creation . "
                + "OPTIONAL {?jsonld prov:wasRevisionOf ?oldEntity . }"
                + "}"
                + "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);

        pss.setIri("oldid", oldid);
        pss.setIri("newid", newid);
        pss.setCommandText(query);

        return pss.asUpdate();

    }

    public void renameID(String oldid, String newid) {
        UpdateRequest queryObj = renameIDRequest(oldid, newid);
        jenaClient.updateToService(queryObj, endpointServices.getProvSparqlUpdateAddress());
    }
}