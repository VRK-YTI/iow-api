/*
 * Licensed under the European Union Public Licence (EUPL) V.1.1
 */
package fi.vm.yti.datamodel.api.service;

import fi.vm.yti.datamodel.api.config.ApplicationProperties;
import fi.vm.yti.datamodel.api.index.ElasticConnector;
import fi.vm.yti.datamodel.api.index.FrameManager;
import fi.vm.yti.datamodel.api.model.AbstractModel;
import fi.vm.yti.datamodel.api.model.AbstractResource;
import fi.vm.yti.datamodel.api.utils.Frames;
import fi.vm.yti.datamodel.api.utils.LDHelper;

import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.update.*;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.jena.util.ResourceUtils;
import org.apache.jena.web.DatasetAdapter;
import org.apache.jena.web.DatasetGraphAccessorHTTP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.XSDDateTime;
import org.apache.jena.iri.IRI;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.util.FileManager;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

@Service
public class GraphManager {

    private static final Logger logger = LoggerFactory.getLogger(GraphManager.class.getName());

    private final EndpointServices endpointServices;
    private final JenaClient jenaClient;
    private final TermedTerminologyManager termedTerminologyManager;
    private final ModelManager modelManager;
    private final ApplicationProperties properties;
    private final ServiceDescriptionManager serviceDescriptionManager;
    private final String versionGraphURI = "urn:yti:metamodel:version";
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final FrameManager frameManager;
    private final ElasticConnector elasticConnector;

    @Autowired
    GraphManager(EndpointServices endpointServices,
                 JenaClient jenaClient,
                 TermedTerminologyManager termedTerminologyManager,
                 ModelManager modelManager,
                 ServiceDescriptionManager serviceDescriptionManager,
                 ApplicationProperties properties,
                 FrameManager frameManager,
                 ElasticConnector elasticConnector) {

        this.endpointServices = endpointServices;
        this.jenaClient = jenaClient;
        this.termedTerminologyManager = termedTerminologyManager;
        this.modelManager = modelManager;
        this.serviceDescriptionManager = serviceDescriptionManager;
        this.properties = properties;
        this.frameManager = frameManager;
        this.elasticConnector = elasticConnector;
    }

    /**
     * Returns true if version graph exists
     *
     * @return boolean
     */
    public boolean isVersionGraphInitialized() {
        return jenaClient.isInCore(versionGraphURI);
    }

    /**
     * Set version number for the used metamodel
     */
    public void setVersionNumber(int version) {
        Model versionModel = ModelFactory.createDefaultModel().addLiteral(ResourceFactory.createResource(versionGraphURI), LDHelper.curieToProperty("iow:version"), version);
        versionModel.setNsPrefix("iow", "http://uri.suomi.fi/datamodel/ns/iow#");
        jenaClient.putModelToCore(versionGraphURI, versionModel);
    }

    /**
     * Get version number for the used metamodel
     *
     * @return version number as int
     */
    public int getVersionNumber() {
        return jenaClient.getModelFromCore(versionGraphURI).getRequiredProperty(ResourceFactory.createResource(versionGraphURI), LDHelper.curieToProperty("iow:version")).getLiteral().getInt();
    }

    /**
     * Returns true if there are some services defined
     *
     * @return boolean
     */
    public boolean testDefaultGraph() {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String queryString = " ASK { ?s a sd:Service ; sd:defaultDataset ?d . ?d sd:defaultGraph ?g . ?g dcterms:title ?title . }";
        pss.setCommandText(queryString);
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);

        Query query = pss.asQuery();

        try {
            boolean b = jenaClient.askQuery(endpointServices.getCoreSparqlAddress(), query, "urn:csc:iow:sd");
            return b;
        } catch (Exception ex) {
            logger.warn("Default graph test failed", ex);
            return false;
        }
    }

    public void initServiceCategories() {
        Model m = FileManager.get().loadModel("ptvl-skos.rdf");
        jenaClient.putModelToCore("urn:yti:servicecategories", m);
    }

    /**
     * Deleted export graph
     *
     * @param graph IRI of the graph that is going to be deleted
     */
    public void deleteExportModel(String graph) {
        jenaClient.deleteModelFromCore(graph + "#ExportGraph");
    }

    /**
     * Creates new graph with owl:Ontology type
     *
     * @param graph IRI of the graph to be created
     */
    public void createNewOntologyGraph(String graph) {
        Model empty = ModelFactory.createDefaultModel();
        empty.setNsPrefixes(LDHelper.PREFIX_MAP);
        empty.add(ResourceFactory.createResource(graph), RDF.type, OWL.Ontology);
        jenaClient.putModelToCore(graph, empty);
    }

    /**
     * Returns graph from core service
     *
     * @param graph String IRI of the graph
     * @return Returns graph as Jena model
     */
    public Model getCoreGraph(String graph) {
        return jenaClient.getModelFromCore(graph);
    }

    /**
     * Returns graph from core service
     *
     * @param graph IRI of the graph
     * @return Returns graph as Jena model
     */
    public Model getCoreGraph(IRI graph) {

        try {
            return getCoreGraph(graph.toString());
        } catch (HttpException ex) {
            logger.warn(ex.toString());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.warn("Thread error", e);
                Thread.currentThread().interrupt();
            }
            return getCoreGraph(graph.toString());
        }
    }

    /**
     * Test if model status restricts removing of the model
     *
     * @param graphIRI IRI of the graph
     * @return Returns true if model status or resource status is "VALID".
     */
    public boolean modelStatusRestrictsRemoving(IRI graphIRI) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String queryString = " ASK { {"
            + "GRAPH ?graph { "
            + "VALUES ?status { \"VALID\" } "
            + "?graph owl:versionInfo ?status . }"
            + "} UNION { "
            + "GRAPH ?hasPartGraph { "
            + "?graph dcterms:hasPart ?resource . }"
            + "GRAPH ?resource { "
            + "?resource rdfs:isDefinedBy ?graph . "
            + "VALUES ?status { \"VALID\" } "
            + "?resource owl:versionInfo ?status  . "
            + "}"
            + "}}";

        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(queryString);
        pss.setIri("graph", graphIRI);

        Query query = pss.asQuery();
        try {
            boolean b = jenaClient.askQuery(endpointServices.getCoreSparqlAddress(), query);
            return b;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * TODO: Check also against known model from lov etc?
     *
     * @param prefix Used prefix of the namespace
     * @return true if prefix is in use by existing model or standard
     */
    public boolean isExistingPrefix(String prefix) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String queryString = " ASK { {GRAPH ?graph { ?s ?p ?o . }} UNION { ?s a dcterms:Standard . ?s dcap:preferredXMLNamespacePrefix ?prefix . }}";
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(queryString);
        pss.setLiteral("prefix", prefix);
        pss.setIri("graph", properties.getDefaultNamespace() + prefix);

        Query query = pss.asQuery();

        try {
            boolean b = jenaClient.askQuery(endpointServices.getCoreSparqlAddress(), query);
            return b;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Check if Graph is existing
     *
     * @param graphIRI IRI of the graphs
     * @return Returns true if Graph with the given IRI
     */
    public boolean isExistingGraph(IRI graphIRI) {
        return isExistingGraph(graphIRI.toString());
    }

    /**
     * Check if Graph is existing
     *
     * @param graphIRI IRI of the graphs
     * @return Returns true if Graph with the given URL String
     */
    public boolean isExistingGraph(String graphIRI) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String queryString = " ASK { GRAPH ?graph { ?s ?p ?o }}";
        pss.setCommandText(queryString);
        pss.setIri("graph", graphIRI);

        Query query = pss.asQuery();
        try {
            boolean b = jenaClient.askQuery(endpointServices.getCoreSparqlAddress(), query);
            return b;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Test if namespace exists as NamedGraph in GraphCollection
     *
     * @param graphIRI IRI of the graph as String
     * @return Returns true if there is a graph in Service description
     */
    public boolean isExistingServiceGraph(String graphIRI) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);

        String queryString = " ASK { GRAPH <urn:csc:iow:sd> { " +
            " ?service a sd:Service . " +
            " ?service sd:availableGraphs ?graphCollection . " +
            " ?graphCollection a sd:GraphCollection . " +
            " ?graphCollection sd:namedGraph ?graph . " +
            " ?graph sd:name ?graphName . " +
            "}}";

        if (graphIRI.endsWith("#")) graphIRI = graphIRI.substring(0, graphIRI.length() - 1);

        pss.setCommandText(queryString);
        pss.setIri("graphName", graphIRI);

        Query query = pss.asQuery();
        try {
            boolean b = jenaClient.askQuery(endpointServices.getCoreSparqlAddress(), query);
            return b;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Check if there exists a Graph that uses the given prefix
     *
     * @param prefix Prefix given to the namespace
     * @return Returns true prefix is in use
     */
    public boolean isExistingGraphBasedOnPrefix(String prefix) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        String queryString = " ASK { GRAPH ?graph { ?graph a owl:Ontology . ?graph dcap:preferredXMLNamespacePrefix ?prefix . }}";
        pss.setCommandText(queryString);
        pss.setIri("prefix", prefix);

        Query query = pss.asQuery();
        try {
            boolean b = jenaClient.askQuery(endpointServices.getCoreSparqlAddress(), query);
            return b;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Returns service graph IRI as string with given prefix
     *
     * @param prefix Prefix used in some model
     * @return Returns graph IRI with given prefix
     */
    public String getServiceGraphNameWithPrefix(String prefix) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String selectResources =
            "SELECT ?graph WHERE { "
                + "GRAPH ?graph { " +
                " ?graph a owl:Ontology . "
                + "?graph dcap:preferredXMLNamespacePrefix ?prefix . " +
                "}}";

        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(selectResources);
        pss.setLiteral("prefix", prefix);

        ResultSet results = jenaClient.selectQuery(endpointServices.getCoreSparqlAddress(), pss.asQuery());

        String graphUri = null;

        while (results.hasNext()) {
            QuerySolution soln = results.nextSolution();
            if (soln.contains("graph")) {
                Resource resType = soln.getResource("graph");
                graphUri = resType.getURI();
            }
        }

        return graphUri;

    }

    /**
     * Initializes Core service with default Graph from static resources file
     */
    public void createDefaultGraph() {

        DatasetAccessor accessor = DatasetAccessorFactory.createHTTP(endpointServices.getCoreReadWriteAddress());

        Model m = ModelFactory.createDefaultModel();
        RDFDataMgr.read(m, LDHelper.getDefaultGraphInputStream(), RDFLanguages.JSONLD);

        accessor.putModel("urn:csc:iow:sd", m);

    }

    /**
     * Builds DROP query by querying model resource graphs
     *
     * @param model Model id as IRI String
     * @return Returns remove query as string
     */
    public String buildRemoveModelQuery(String model) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String selectResources = "SELECT ?graph WHERE { GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph . } GRAPH ?graph { ?graph rdfs:isDefinedBy ?model . }}";

        pss.setIri("model", model);
        pss.setIri("hasPartGraph", model + "#HasPartGraph");
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(selectResources);

        ResultSet results = jenaClient.selectQuery(endpointServices.getCoreSparqlAddress(), pss.asQuery());
        String newQuery = "DROP SILENT GRAPH <" + model + ">; ";
        newQuery += "DROP SILENT GRAPH <" + model + "#HasPartGraph>; ";
        newQuery += "DROP SILENT GRAPH <" + model + "#ExportGraph>; ";
        newQuery += "DROP SILENT GRAPH <" + model + "#PositionGraph>; ";

        while (results.hasNext()) {
            QuerySolution soln = results.nextSolution();
            newQuery += "DROP SILENT GRAPH <" + soln.getResource("graph").toString() + ">; ";
        }

        return newQuery;
    }

    /**
     * Removes all graphs related to the model graph
     *
     * @param id IRI of the graph
     */
    public void removeModel(IRI id) {

        String query = buildRemoveModelQuery(id.toString());

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(query);
        pss.setIri("graph", id);

        logger.info("Removing model from " + id);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());

        /* TODO: remove when resolved JENA-1255 */
        namespaceBugFix(id.toString());

        try {
            qexec.execute();
        } catch (UpdateException ex) {
            logger.warn(ex.toString());
        }
    }

    /**
     * TODO: remove when resolved JENA-1255. This removes model graph by putting empty model to the graph.
     *
     * @param id
     */
    public void namespaceBugFix(String id) {
        Model empty = ModelFactory.createDefaultModel();
        jenaClient.putModelToCore(id, empty);
    }

    /**
     * Tries to remove single graph
     *
     * @param id IRI of the graph to be removed
     */
    public void removeGraph(IRI id) {

        String query = "DROP GRAPH ?graph ;";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(query);
        pss.setIri("graph", id);

        logger.warn("Removing graph " + id);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());

        try {
            qexec.execute();
        } catch (UpdateException ex) {
            logger.warn(ex.toString());
        }
    }

    /**
     * Tries to Delete contents of the resource graphs linked to the model graph
     *
     * @param model String representing the IRI of an model graph
     */
    public void deleteResourceGraphs(String model) {

        String query = "DELETE { GRAPH ?graph { ?s ?p ?o . } } WHERE { GRAPH ?graph { ?s ?p ?o . ?graph rdfs:isDefinedBy ?model . } }";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        pss.setIri("model", model);

        pss.setCommandText(query);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

        /* OPTIONALLY. Ummm. Not really?
        
         UpdateRequest request = UpdateFactory.create() ;
         request.add("DROP ALL")
         UpdateAction.execute(request, graphStore) ;
        
         */
    }

    /**
     * TODO: Remove!? Not in use. Fixed in front?
     *
     * @param model
     */
    public void deleteExternalGraphReferences(String model) {

        String query = "DELETE { "
            + "GRAPH ?graph { ?any rdfs:label ?label . } } "
            + "WHERE { GRAPH ?graph { "
            + "?graph dcterms:requires ?any . "
            + "?any a dcap:MetadataVocabulary . "
            + "?any rdfs:label ?label . "
            + "}}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("graph", model);

        pss.setCommandText(query);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    /**
     * Tries to update the dcterms:modified date of an resource
     *
     * @param resource IRI of an Resource as String
     */
    public void updateModifyDates(String resource) {

        String query =
            "DELETE { GRAPH ?resource { ?resource dcterms:modified ?oldModDate . } } "
                + "INSERT { GRAPH ?resource { ?resource dcterms:modified ?time . } } "
                + "WHERE { GRAPH ?resource { ?resource dcterms:modified ?oldModDate .  } BIND(now() as ?time) }";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("resource", resource);

        pss.setCommandText(query);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    /**
     * Deletes all graphs from Core service.
     */
    public void deleteGraphs() {

        String query = "DROP ALL";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(query);

        logger.warn(pss.toString() + " DROPPING ALL FROM CORE/PROV/SKOS SERVICES");

        UpdateRequest queryObj = pss.asUpdate();

        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

        qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getProvSparqlUpdateAddress());
        qexec.execute();

        qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getTempConceptSparqlUpdateAddress());
        qexec.execute();

    }

    public static UpdateRequest renameIDRequest(IRI oldID,
                                                IRI newID) {
        String query
            = " DELETE { GRAPH ?hasPartGraph { ?graph dcterms:hasPart ?oldID }}"
            + " INSERT { GRAPH ?hasPartGraph { ?graph dcterms:hasPart ?newID }}"
            + " WHERE { GRAPH ?hasPartGraph { ?graph dcterms:hasPart ?oldID }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setCommandText(query);

        logger.warn("Renaming " + oldID + " to " + newID);

        return pss.asUpdate();
    }

    /**
     * Renames IRI:s in HasPart-graph
     *
     * @param oldID Old id IRI
     * @param newID New id IRI
     */
    public void renameID(IRI oldID,
                         IRI newID) {
        UpdateRequest queryObj = renameIDRequest(oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public static UpdateRequest updateReferencesInPositionGraphRequest(IRI modelID,
                                                                       IRI oldID,
                                                                       IRI newID) {
        String query
            = " DELETE { GRAPH ?graph { ?oldID ?anyp ?anyo . }} "
            + " INSERT { GRAPH ?graph { ?newID ?anyp ?anyo . }} "
            + " WHERE { "
            + "GRAPH ?graph { ?oldID ?anyp ?anyo . }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setIri("graph", modelID + "#PositionGraph");
        pss.setCommandText(query);
        logger.warn("Updating references in " + modelID.toString() + "#PositionGraph");
        return pss.asUpdate();
    }

    /**
     * Updates IRI:s in Position-graph
     *
     * @param modelID Model IRI
     * @param oldID   Old resource IRI
     * @param newID   New resource IRI
     */
    public void updateReferencesInPositionGraph(IRI modelID,
                                                IRI oldID,
                                                IRI newID) {

        UpdateRequest queryObj = updateReferencesInPositionGraphRequest(modelID, oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    /**
     * Renames Resource IRI references
     *
     * @param modelID Model IRI
     * @param oldID   Old Predicate IRI
     * @param newID   New Predicate IRI
     */
    public void updateResourceReferencesInModel(IRI modelID,
                                                IRI oldID,
                                                IRI newID) {

        String query
            = " DELETE { GRAPH ?graph { ?any ?predicate ?oldID }} "
            + " INSERT { GRAPH ?graph { ?any ?predicate ?newID }} "
            + " WHERE { "
            + "GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph . } "
            + "GRAPH ?graph { ?graph rdfs:isDefinedBy ?model . ?any ?predicate ?oldID . }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setIri("model", modelID);
        pss.setIri("hasPartGraph", modelID + "#HasPartGraph");
        pss.setCommandText(query);

        logger.warn("Updating references in " + modelID.toString());

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    public void createNewHasPartGraphVersion(IRI oldID,
                                             IRI newID) {

        String query =
            " INSERT { GRAPH ?newHasPartGraph { ?newID dcterms:hasPart ?graph .  }} "
                + " WHERE { "
                + "GRAPH ?hasPartGraph { ?oldID dcterms:hasPart ?graph . } }";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setIri("hasPartGraph", oldID.toString() + "#HasPartGraph");
        pss.setIri("newHasPartGraph", newID.toString() + "#HasPartGraph");
        pss.setCommandText(query);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    /**
     * Renames Resource IRI references in export graph
     *
     * @param modelID Model IRI
     * @param oldID   Old Predicate IRI
     * @param newID   New Predicate IRI
     */
    public void updateResourceReferencesInAllGraphs(IRI modelID,
                                                    IRI oldID,
                                                    IRI newID) {

        String query
            = " DELETE { GRAPH ?anyGraph { ?any ?predicate ?oldID }} "
            + " INSERT { GRAPH ?anyGraph { ?any ?predicate ?newID }} "
            + " WHERE { GRAPH ?anyGraph { ?any ?predicate ?oldID . }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setCommandText(query);

        logger.warn("Updating references in " + modelID.toString() + "#ExportGraph");

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    public void updateStatusAndProvInModel(IRI oldID,
                                           IRI newID) {
        UpdateRequest queryObj = updateStatusAndRevisionInModelRequest(oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public static UpdateRequest updateStatusAndRevisionInModelRequest(IRI oldID,
                                                                      IRI newID) {
        String query = "DELETE { GRAPH ?newID { ?newID owl:versionInfo ?status . ?newID prov:wasRevisionOf ?oldRevisionID . } }"
            + " INSERT { GRAPH ?newID { ?newID owl:versionInfo 'DRAFT' . ?newID prov:wasRevisionOf ?oldID . } } "
            + " WHERE { GRAPH ?oldID { ?oldID owl:versionInfo ?status . } GRAPH ?newID { ?newID owl:versionInfo ?status . OPTIONAL { ?newID prov:wasRevisionOf ?oldRevisionID . } }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setCommandText(query);
        logger.info("Writing status and revision");

        return pss.asUpdate();
    }

    public void updateStatusAndDerivationInModel(IRI oldID,
                                                 IRI newID) {
        UpdateRequest queryObj = updateStatusAndDerivationInModelRequest(oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public static UpdateRequest updateStatusAndDerivationInModelRequest(IRI oldID,
                                                                        IRI newID) {
        String query = "DELETE { GRAPH ?newID { ?newID owl:versionInfo ?status . } }"
            + "INSERT { GRAPH ?newID { ?newID owl:versionInfo 'DRAFT' . ?newID prov:wasDerivedFrom ?oldID . } "
            + "GRAPH ?oldID { ?oldID prov:hadDerivation ?newID . } }"
            + " WHERE { GRAPH ?oldID { ?oldID owl:versionInfo ?status . } GRAPH ?newID { ?newID owl:versionInfo ?status . } }";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setCommandText(query);
        logger.info("Writing status and derivation");

        return pss.asUpdate();
    }

    public void renameObjectIRIinModel(IRI oldID,
                                       IRI newID) {
        UpdateRequest queryObj = updateObjectIRIInGraph(oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public static UpdateRequest updateObjectIRIInGraph(IRI oldIRI,
                                                       IRI newIRI) {
        String query =
            "DELETE { GRAPH ?newGraph { ?s ?p ?o1 . } } " +
                "INSERT { GRAPH ?newGraph { ?s ?p ?o2 . } } " +
                "WHERE { GRAPH ?newGraph { ?s ?p ?o1 .  " +
                "FILTER (strstarts(str(?o1), str(?oldIRI)) && isIRI(?o1)) " +
                "BIND (IRI(replace(str(?o1), str(?oldIRI), str(?newIRI)))  AS ?o2) } " +
                "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldIRI", oldIRI);
        pss.setIri("newIRI", newIRI);
        pss.setIri("newGraph", newIRI);
        pss.setCommandText(query);
        logger.info("Rewriting version objects from " + oldIRI + " to " + newIRI);
        return pss.asUpdate();
    }

    public void changeNamespaceInObjects(IRI oldID,
                                         IRI newID) {
        UpdateRequest queryObj = updateNamespaceInObject(oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public static UpdateRequest updateNamespaceInObject(IRI oldIRI,
                                                        IRI newIRI) {
        String query =
            "DELETE { GRAPH ?newGraph { ?s ?p ?o1 . } } " +
                "INSERT { GRAPH ?newGraph { ?s ?p ?o2 . } } " +
                "WHERE { GRAPH ?newGraph { ?s ?p ?o1 .  " +
                "FILTER (strstarts(str(?o1), str(?oldIRI)) && isIRI(?o1)) " +
                "BIND (IRI(replace(str(?o1), str(?oldIRI), str(?newIRI)))  AS ?o2) } " +
                "FILTER(strstarts(str(?newGraph),str(?newGraphIRI))) " +
                "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldIRI", oldIRI);
        pss.setIri("newIRI", newIRI);
        pss.setIri("newGraphIRI", newIRI + "#");
        pss.setCommandText(query);
        logger.info("Rewriting resource objects in version graphs from " + oldIRI + " to " + newIRI);
        return pss.asUpdate();
    }

    public void changeNamespaceInResources(IRI oldID,
                                           IRI newID) {
        UpdateRequest queryObj = renameResourcesInNewGraphsQuery(oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public static UpdateRequest renameResourcesInNewGraphsQuery(IRI oldIRI,
                                                                IRI newIRI) {
        String query =
            "DELETE { GRAPH ?newGraph { ?s1 ?p ?o . } }" +
                "INSERT { GRAPH ?newGraph { ?s2 ?p ?o . } }" +
                "WHERE { GRAPH ?newGraph { ?s1 ?p ?o . " +
                "FILTER (strstarts(str(?s1), str(?oldIRI))) " +
                "BIND (IRI(replace(str(?s1), str(?oldIRI), str(?newIRI))) AS ?s2) }" +
                "FILTER(strstarts(str(?newGraph),str(?newIRI))) " +
                "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldIRI", oldIRI + "#");
        pss.setIri("newIRI", newIRI + "#");
        pss.setCommandText(query);
        logger.info("Rewriting " + oldIRI + " to " + newIRI);
        return pss.asUpdate();
    }

    public void changeStatusInNewGraphs(IRI oldID,
                                        IRI newID) {
        UpdateRequest queryObj = changeStatusInNewGraphsQuery(oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public static UpdateRequest changeStatusInNewGraphsQuery(IRI oldIRI,
                                                             IRI newIRI) {
        String query =
            "DELETE { GRAPH ?s2 { ?s2 owl:versionInfo ?status . } }" +
                "INSERT { GRAPH ?s2 { ?s2 owl:versionInfo 'DRAFT' . ?s2 prov:wasRevisionOf ?s1 . } } " +
                // "GRAPH ?s1 { ?s1 owl:versionInfo 'SUPERSEDED' . ?s1 prov:hadRevision ?s2 . } }"+
                "WHERE { " +
                "GRAPH ?s1 { ?s1 owl:versionInfo ?status . }" +
                "FILTER (strstarts(str(?s1), str(?oldIRI))) " +
                "BIND (IRI(replace(str(?s1), str(?oldIRI), str(?newIRI))) AS ?s2) " +
                "GRAPH ?s2 { ?s2 owl:versionInfo ?status . }  " +
                "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldIRI", oldIRI + "#");
        pss.setIri("newIRI", newIRI + "#");
        pss.setCommandText(query);
        logger.info("Rewriting " + oldIRI + " to " + newIRI);
        return pss.asUpdate();
    }

    /**
     * Copies graph
     */
    public void copyGraph(String from,
                          String to) {
        String query = "COPY SILENT ?from TO ?to ";
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setIri("from", from);
        pss.setIri("to", to);
        pss.setCommandText(query);
        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    /**
     * NOT WORKING WITH FUSEKI 2.3.0
     **/
    public UpdateRequest getCreateVersionGraphsRequest(IRI model,
                                                       IRI newModel) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String selectResources = "SELECT ?graph WHERE { GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph . } GRAPH ?graph { ?graph rdfs:isDefinedBy ?model . }}";

        pss.setIri("model", model);
        pss.setIri("hasPartGraph", model + "#HasPartGraph");
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(selectResources);

        ResultSet results = jenaClient.selectQuery(endpointServices.getCoreSparqlAddress(), pss.asQuery());

        String query = "COPY SILENT <" + model.toString() + "> TO <" + newModel.toString() + "> ; ";

        while (results.hasNext()) {
            QuerySolution soln = results.nextSolution();
            String oldGraph = soln.getResource("graph").toString();
            String newGraph = oldGraph.replace(model.toString() + "#", newModel.toString() + "#");
            query += "COPY SILENT <" + oldGraph + "> TO <" + newGraph + ">; ";
        }

        ParameterizedSparqlString copyPss = new ParameterizedSparqlString();
        copyPss.setCommandText(query);
        logger.info("Copying graphs starting with " + model.toString() + " to " + newModel.toString());
        return copyPss.asUpdate();
    }

    public void createVersionGraphsWithJenaAdapter(Model oldModelGraph,
                                                   String newPrefix,
                                                   IRI model,
                                                   IRI newModel) {

        DatasetGraphAccessorHTTP accessor = new DatasetGraphAccessorHTTP(endpointServices.getCoreReadWriteAddress());
        DatasetAdapter adapter = new DatasetAdapter(accessor);

        // Model oldModelGraph = adapter.getModel(model.toString());

        Resource modelResource = oldModelGraph.getResource(model.toString());
        ResourceUtils.renameResource(modelResource, newModel.toString());
        oldModelGraph.setNsPrefix(newPrefix, newModel.toString() + "#");
        Resource newModelResource = ResourceFactory.createResource(newModel.toString());
        LDHelper.rewriteLiteral(oldModelGraph, newModelResource, OWL.versionInfo, ResourceFactory.createPlainLiteral("DRAFT"));
        LDHelper.rewriteResourceReference(oldModelGraph, newModelResource, LDHelper.curieToProperty("prov:wasRevisionOf"), ResourceFactory.createResource(model.toString()));
        LDHelper.rewriteLiteral(oldModelGraph, newModelResource, LDHelper.curieToProperty("dcap:preferredXMLNamespaceName"), ResourceFactory.createPlainLiteral(newModel.toString() + "#"));
        LDHelper.rewriteLiteral(oldModelGraph, newModelResource, LDHelper.curieToProperty("dcap:preferredXMLNamespacePrefix"), ResourceFactory.createPlainLiteral(newPrefix));
        renameObjectNamespaceInModel(oldModelGraph, model.toString() + "#", newModel.toString() + "#");
        adapter.putModel(newModel.toString(), oldModelGraph);

        Model oldHasPartGraph = adapter.getModel(model.toString() + "#HasPartGraph");
        ResourceUtils.renameResource(oldHasPartGraph.getResource(model.toString()), newModel.toString());

        Model oldPositionGraph = adapter.getModel(model.toString() + "#PositionGraph");
        ResIterator positionResources = oldPositionGraph.listSubjects();
        while (positionResources.hasNext()) {
            Resource posRes = positionResources.next();
            if (!posRes.isAnon()) {
                String posResId = posRes.getURI();
                if (posResId.startsWith(model.toString() + "#")) {
                    String newPosId = posResId.replace(model.toString() + "#", newModel.toString() + "#");
                    ResourceUtils.renameResource(posRes, newPosId);
                }
            }
        }
        adapter.putModel(newModel.toString() + "#PositionGraph", oldPositionGraph);

        NodeIterator hasPartObjects = oldHasPartGraph.listObjectsOfProperty(DCTerms.hasPart);

        while (hasPartObjects.hasNext()) {
            Resource hasPartResource = hasPartObjects.next().asResource();
            String oldGraph = hasPartResource.getURI();
            if (oldGraph.startsWith(model.toString() + "#")) {
                String newGraph = oldGraph.replace(model.toString() + "#", newModel.toString() + "#");
                logger.info("Creating version from " + oldGraph + " to " + newGraph);
                Model oldResourceGraph = adapter.getModel(oldGraph);
                if (oldResourceGraph != null) { // FIXME: References to removed resources?!?
                    Resource oldResource = oldResourceGraph.getResource(oldGraph);
                    ResourceUtils.renameResource(oldResource, newGraph);
                    Resource newResource = ResourceFactory.createResource(newGraph);
                    oldResourceGraph.setNsPrefix(newPrefix, newModel.toString() + "#");
                    LDHelper.rewriteLiteral(oldResourceGraph, newResource, OWL.versionInfo, ResourceFactory.createPlainLiteral("DRAFT"));
                    LDHelper.rewriteResourceReference(oldResourceGraph, newResource, LDHelper.curieToProperty("prov:wasRevisionOf"), ResourceFactory.createResource(oldGraph));
                    renameObjectNamespaceInModel(oldResourceGraph, model.toString() + "#", newModel.toString() + "#");
                    adapter.putModel(newGraph, oldResourceGraph);
                    ResourceUtils.renameResource(hasPartResource, newGraph);
                }
            }
        }
        adapter.putModel(newModel.toString() + "#HasPartGraph", oldHasPartGraph);
    }

    public void renameObjectNamespaceInModel(Model model,
                                             String oldNS,
                                             String newNS) {
        NodeIterator objectList = model.listObjects();
        while (objectList.hasNext()) {
            RDFNode objectNode = objectList.next();
            if (objectNode.isURIResource()) {
                Resource objectResource = objectNode.asResource();
                String objectResourceURI = objectResource.getURI();
                if (objectResourceURI.startsWith(oldNS)) {
                    String newObjectResourceURI = objectResourceURI.replace(oldNS, newNS);
                    ResourceUtils.renameResource(objectResource, newObjectResourceURI);
                }
            }
        }
    }

    public void newModelVersion(Model oldVocabulary,
                                String newPrefix,
                                IRI oldID,
                                IRI newID) {
        createVersionGraphsWithJenaAdapter(oldVocabulary, newPrefix, oldID, newID);
    }

    public void changePrefixAndNamespaceFromModelCopy(IRI newID,
                                                      String newPrefix) {
        UpdateRequest queryObj = changePrefixAndNamespaceFromModelCopyQuery(newID, newPrefix);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public static UpdateRequest changePrefixAndNamespaceFromModelCopyQuery(IRI newIRI,
                                                                           String newPrefix) {
        String query =
            "DELETE { GRAPH ?newIRI { ?newIRI dcap:preferredXMLNamespaceName ?oldNamespace .  ?newIRI dcap:preferredXMLNamespacePrefix ?oldPrefix . } }" +
                "INSERT { GRAPH ?newIRI { ?newIRI dcap:preferredXMLNamespaceName ?newNamespace .  ?newIRI dcap:preferredXMLNamespacePrefix ?newPrefix . } }" +
                "WHERE { GRAPH ?newIRI { ?newIRI dcap:preferredXMLNamespaceName ?oldNamespace .  ?newIRI dcap:preferredXMLNamespacePrefix ?oldPrefix . } }";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("newIRI", newIRI);
        pss.setLiteral("newNamespace", newIRI.toString() + "#");
        pss.setLiteral("newPrefix", newPrefix);
        pss.setCommandText(query);
        logger.info("Rewriting namespace and prefix for " + newPrefix);
        return pss.asUpdate();
    }

    public UpdateRequest insertNewGraphReferenceToModelRequest(String graph,
                                                               String model) {
        Literal timestamp = LDHelper.getDateTimeLiteral();

        String query
            = " INSERT { "
            + "GRAPH ?hasPartGraph { "
            + "?model dcterms:hasPart ?graph "
            + "} "
            + "GRAPH ?graph { "
            + "?graph rdfs:isDefinedBy ?model . "
            + "?graph dcterms:created ?timestamp . }} "
            + " WHERE { GRAPH ?graph { ?graph a ?type . }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("graph", graph);
        pss.setIri("model", model);
        pss.setIri("hasPartGraph", model + "#HasPartGraph");
        pss.setLiteral("date", timestamp);
        pss.setCommandText(query);

        return pss.asUpdate();
    }

    /**
     * Inserts Resource reference to models HasPartGraph and model reference to Resource graph
     *
     * @param graph Resource graph IRI as String
     * @param model Model graph IRI as String
     */
    public void insertNewGraphReferenceToModel(String graph,
                                               String model) {
        UpdateRequest queryObj = insertNewGraphReferenceToModelRequest(graph, model);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    public static UpdateRequest insertNewGraphReferenceToExportGraphRequest(String graph,
                                                                            String model) {
        String query
            = " INSERT { "
            + "GRAPH ?exportGraph { "
            + "?model dcterms:hasPart ?graph . "
            + "}} WHERE { "
            + "GRAPH ?exportGraph { "
            + "?model a owl:Ontology . "
            + "}}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("graph", graph);
        pss.setIri("model", model);
        pss.setIri("exportGraph", model + "#ExportGraph");
        pss.setCommandText(query);
        return pss.asUpdate();
    }

    /**
     * Insert new graph reference to export graph. In some cases it makes more sense to do small changes than create the whole export graph again.
     *
     * @param graph Resource IRI as String
     * @param model Model IRI as String
     */
    public void insertNewGraphReferenceToExportGraph(String graph,
                                                     String model) {

        UpdateRequest queryObj = insertNewGraphReferenceToExportGraphRequest(graph, model);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    public UpdateRequest insertExistingGraphReferenceToModelRequest(String graph,
                                                                    String model) {
        String query
            = " INSERT { GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph }} "
            + " WHERE { GRAPH ?graph { ?graph a ?type . }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("graph", graph);
        pss.setIri("model", model);
        pss.setIri("hasPartGraph", model + "#HasPartGraph");
        pss.setCommandText(query);
        return pss.asUpdate();
    }

    /**
     * Add existing Resource to the model as Resource reference. Inserts only the reference to models HasPartGraph.
     *
     * @param graph Resource IRI as String
     * @param model Model IRI as String
     */
    public void insertExistingGraphReferenceToModel(String graph,
                                                    String model) {
        UpdateRequest queryObj = insertExistingGraphReferenceToModelRequest(graph, model);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public void deleteGraphReferenceFromModel(IRI graph,
                                              IRI model) {
        deleteGraphReferenceFromModel(graph.toString(), model.toString());
    }

    /**
     * Removes Resource-graph reference from models HasPartGraph
     *
     * @param graph Resource IRI reference to be removed
     * @param model Model IRI
     */

    public void deleteGraphReferenceFromModel(String graph,
                                              String model) {
        UpdateRequest queryObj = deleteGraphReferenceFromModelRequest(graph, model);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }

    public UpdateRequest deleteGraphReferenceFromModelRequest(String graph,
                                                              String model) {
        String query
            = " DELETE { "
            + "GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph } "
            + "} "
            + " WHERE { "
            + "GRAPH ?model { ?model a ?type . } "
            + "GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph } "
            + "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("graph", graph);
        pss.setIri("model", model);
        pss.setIri("hasPartGraph", model + "#HasPartGraph");
        pss.setCommandText(query);

        return pss.asUpdate();
    }

    public void deleteGraphReferenceFromExportModel(IRI graph,
                                                    IRI model) {
        deleteGraphReferenceFromExportModel(graph.toString(), model.toString());
    }

    /**
     * Removes Resource-graph reference from models HasPartGraph
     *
     * @param graph Resource IRI reference to be removed
     * @param model Model IRI
     */
    public void deleteGraphReferenceFromExportModel(String graph,
                                                    String model) {

        String query
            = " DELETE { "
            + "GRAPH ?exportGraph { ?model dcterms:hasPart ?graph } "
            + "} "
            + " WHERE { "
            + "GRAPH ?model { ?model a ?type . } "
            + "GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph } "
            + "}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("graph", graph);
        pss.setIri("model", model);
        pss.setIri("exportGraph", model + "#ExportGraph");
        pss.setIri("hasPartGraph", model + "#HasPartGraph");
        pss.setCommandText(query);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    /**
     * Copies graph from one Service to another Service
     *
     * @param fromGraph   Existing graph in original service as String
     * @param toGraph     New graph IRI as String
     * @param fromService Service where graph exists
     * @param toService   Service where graph is copied
     * @throws NullPointerException
     */
    public static void addGraphFromServiceToService(String fromGraph,
                                                    String toGraph,
                                                    String fromService,
                                                    String toService) throws NullPointerException {

        DatasetAccessor fromAccessor = DatasetAccessorFactory.createHTTP(fromService);
        Model graphModel = fromAccessor.getModel(fromGraph);

        if (graphModel == null) {
            throw new NullPointerException();
        }

        DatasetAccessor toAccessor = DatasetAccessorFactory.createHTTP(toService);
        toAccessor.add(toGraph, graphModel);

    }

    public void insertExistingResourceToModel(String id,
                                              String model) {
        insertExistingGraphReferenceToModel(id, model);
        insertNewGraphReferenceToExportGraph(id, model);
        addCoreGraphToCoreGraph(id, model + "#ExportGraph");
        // FIXME: Refactored this earlier from RDFConnectionRemote to RDFConnection. Not working returns 500!?!?
        /*
        try(RDFConnection conn = endpointServices.getCoreConnection()) {
            Txn.executeWrite(conn, ()-> {
                conn.update(insertExistingGraphReferenceToModelRequest(id, model));
                conn.update(insertNewGraphReferenceToExportGraphRequest(id, model));
                conn.load(LDHelper.encode(model+"#ExportGraph"),conn.fetch(LDHelper.encode(id)));
              });
        }*/
    }

    /**
     * Copies graph to another graph in Core service
     *
     * @param fromGraph Graph IRI as string
     * @param toGraph   New copied graph IRI as string
     * @throws NullPointerException
     */
    public void addCoreGraphToCoreGraph(String fromGraph,
                                        String toGraph) throws NullPointerException {

        DatasetAccessor fromAccessor = DatasetAccessorFactory.createHTTP(endpointServices.getCoreReadWriteAddress());
        Model graphModel = fromAccessor.getModel(fromGraph);

        if (graphModel == null) {
            throw new NullPointerException();
        }

        fromAccessor.add(toGraph, graphModel);
    }

    /**
     * Put model to graph
     *
     * @param model Jena model
     * @param id    IRI of the graph as String
     */
    public void putToGraph(Model model,
                           String id) {
        logger.debug("Putting to " + id);

        // TODO: This is not saving prefixes and namespaces! How does it work?
      /*  try(RDFConnection conn = endpointServices.getCoreConnection()) {
            Txn.executeWrite(conn, ()->{
                conn.put(LDHelper.encode(id), model);
            });
        } catch(Exception ex) {
            logger.warn(ex.getMessage());
        } */

        DatasetGraphAccessorHTTP accessor = new DatasetGraphAccessorHTTP(endpointServices.getCoreReadWriteAddress());
        DatasetAdapter adapter = new DatasetAdapter(accessor);
        adapter.putModel(id, model);

    }

    public void addToGraph(Model model,
                           String id) {
        logger.debug("Adding to " + id);
        DatasetGraphAccessorHTTP accessor = new DatasetGraphAccessorHTTP(endpointServices.getCoreReadWriteAddress());
        DatasetAdapter adapter = new DatasetAdapter(accessor);
        adapter.add(id, model);
    }

    /**
     * Constructs JSON-LD from graph
     *
     * @param query SPARQL query as string
     * @return Returns JSON-LD object
     */
    public String constructStringFromGraph(String query) {
        Model results = jenaClient.constructFromService(query, endpointServices.getCoreSparqlAddress());
        return modelManager.writeModelToJSONLDString(results);
    }

    /**
     * Constructs JSON-LD from graph
     *
     * @param query SPARQL query as string
     * @return Returns JSON-LD object
     */
    public Model constructModelFromCoreGraph(String query) {
        return jenaClient.constructFromService(query, endpointServices.getCoreSparqlAddress());
      /* try (RDFConnectionRemote conn = endpointServices.getCoreConnection()) {
            return conn.queryConstruct(query);
        } catch (Exception ex) {
            logger.warn(ex.getMessage());
            return null;
        } */
    }

    public Model constructModelFromService(String query,
                                           String service) {
        return jenaClient.constructFromService(query, service);
    }

    /**
     * Construct from combined model of Concept and Model
     *
     * @param conceptID ID of concept
     * @param modelID   ID of model
     * @param query     Construct SPARQL query
     * @return created Jena model
     */
    @Deprecated
    public Model constructModelFromConceptAndCore(String conceptID,
                                                  String modelID,
                                                  Query query) {

        Model conceptModel = termedTerminologyManager.getConceptAsJenaModel(conceptID);

        DatasetAccessor testAcc = DatasetAccessorFactory.createHTTP(endpointServices.getCoreReadAddress());
        conceptModel.add(testAcc.getModel(modelID));

        try (QueryExecution qexec = QueryExecutionFactory.create(query, conceptModel)) {
            Model resultModel = qexec.execConstruct();
            return resultModel;
        }
    }

    public Model getModelInfo(IRI modelIRI) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);

        String queryString = "CONSTRUCT  { "
            + "?model a ?type . "
            + "?model rdfs:label ?modelLabel . "
            + "?model a ?modelType . "
            + "} WHERE { GRAPH ?model {"
            + "?model a ?type . "
            + "?model rdfs:label ?modelLabel . "
            + "}}";

        pss.setCommandText(queryString);
        pss.setIri("model", modelIRI);

        return constructModelFromCoreGraph(pss.toString());

    }

    /**
     * Returns date when the model was last modified from the Export graph
     *
     * @param graphName Graph IRI as string
     * @return Returns date
     */
    public Date lastModified(String graphName) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String selectResources =
            "SELECT ?date WHERE { "
                + "GRAPH ?exportGraph { " +
                " ?graph a owl:Ontology . "
                + "?graph dcterms:modified ?date . " +
                "}}";

        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(selectResources);
        pss.setIri("graph", graphName);
        // TODO: Remove #ExportGraph when creating it dynamically
        pss.setIri("exportGraph", graphName + "#ExportGraph");

        ResultSet results = jenaClient.selectQuery(endpointServices.getCoreSparqlAddress(), pss.asQuery());

        Date modified = null;

        while (results.hasNext()) {
            QuerySolution soln = results.nextSolution();
            if (soln.contains("date")) {
                Literal liteDate = soln.getLiteral("date");
                modified = ((XSDDateTime) XSDDatatype.XSDdateTime.parse(liteDate.getString())).asCalendar().getTime();
            }
        }

        return modified;
    }

    public void createResource(AbstractResource resource) {
        jenaClient.putModelToCore(resource.getId(), resource.asGraph());
        insertNewGraphReferenceToModel(resource.getId(), resource.getModelId());
        Model exportModel = resource.asGraphCopy();
        exportModel.add(exportModel.createResource(resource.getModelId()), DCTerms.hasPart, exportModel.createResource(resource.getId()));
        LDHelper.rewriteLiteral(exportModel, ResourceFactory.createResource(resource.getModelId()), DCTerms.modified, LDHelper.getDateTimeLiteral());
        jenaClient.addModelToCore(resource.getModelId() + "#ExportGraph", exportModel);
    }

    public void updateResource(String modelId,
                               String resourceId,
                               Model oldModel,
                               Model newModel) {
        Model exportModel = jenaClient.getModelFromCore(modelId + "#ExportGraph");
        exportModel = modelManager.removeResourceStatements(oldModel, exportModel);
        exportModel.add(newModel);
        LDHelper.rewriteLiteral(exportModel, ResourceFactory.createResource(modelId), DCTerms.modified, LDHelper.getDateTimeLiteral());
        jenaClient.putModelToCore(modelId + "#ExportGraph", exportModel);
        jenaClient.putModelToCore(resourceId, newModel);
    }

    public void updateResource(AbstractResource resource) {
        final Model oldModel = jenaClient.getModelFromCore(resource.getId());
        final Model newModel = resource.asGraph();
        updateResource(resource.getModelId(), resource.getId(), oldModel, newModel);
    }

    public void updateResourceWithNewId(IRI oldIdIRI,
                                        AbstractResource resource) {
        updateResource(resource.getModelId(), resource.getId(), jenaClient.getModelFromCore(oldIdIRI.toString()), resource.asGraph());
        removeGraph(oldIdIRI);
        updateResourceReferencesInAllGraphs(resource.getModelIRI(), oldIdIRI, resource.getIRI());
        updateReferencesInPositionGraph(resource.getModelIRI(), oldIdIRI, resource.getIRI());
        try {
            frameManager.cleanCachedFrames();
        } catch (IOException e) {
            logger.warn("Could not clean cached frames");
            logger.warn(e.getMessage());
        }
    }

    public void deleteResource(AbstractResource resource) {
        deleteResource(resource.getId(), resource.getModelId(), resource.asGraph());
    }

    public void deleteResource(String resourceId,
                               String modelId,
                               Model resourceModel) {
        Model exportModel = jenaClient.getModelFromCore(modelId + "#ExportGraph");
        exportModel = modelManager.removeResourceStatements(resourceModel, exportModel);
        exportModel.remove(exportModel.createResource(modelId), DCTerms.hasPart, exportModel.createResource(resourceId));
        LDHelper.rewriteLiteral(exportModel, ResourceFactory.createResource(modelId), DCTerms.modified, LDHelper.getDateTimeLiteral());
        jenaClient.putModelToCore(modelId + "#ExportGraph", exportModel);
        deleteGraphReferenceFromModel(resourceId, modelId);
        jenaClient.deleteModelFromCore(resourceId);
    }

    public void createModel(AbstractModel amodel) {
        LDHelper.rewriteLiteral(amodel.asGraph(), ResourceFactory.createResource(amodel.getId()), DCTerms.modified, LDHelper.getDateTimeLiteral());
        logger.info("Creating model " + amodel.getId());
        jenaClient.putModelToCore(amodel.getId(), amodel.asGraph());
        jenaClient.putModelToCore(amodel.getId() + "#ExportGraph", amodel.asGraph());
    }

    public void updateModel(AbstractModel amodel) {
        LDHelper.rewriteLiteral(amodel.asGraph(), ResourceFactory.createResource(amodel.getId()), DCTerms.modified, LDHelper.getDateTimeLiteral());
        Model oldModel = jenaClient.getModelFromCore(amodel.getId());
        Model exportModel = jenaClient.getModelFromCore(amodel.getId() + "#ExportGraph");

        // OMG: Model.remove() doesnt remove RDFLists
        Resource modelResource = ResourceFactory.createResource(amodel.getId());
        if (exportModel.contains(modelResource, DCTerms.language)) {
            Statement languageStatement = exportModel.getProperty(modelResource, DCTerms.language);
            RDFList languageList = languageStatement.getObject().as(RDFList.class);
            languageList.removeList();
            languageStatement.remove();
        }

        // FIXME: This can be changed to if after data is updated in production
        while (exportModel.contains(modelResource, DCTerms.relation)) {
            Statement relatedStatement = exportModel.getProperty(modelResource, DCTerms.relation);
            RDFList relatedLinkList = relatedStatement.getObject().as(RDFList.class);
            relatedLinkList.asJavaList().forEach((node) -> {
                node.asResource().removeProperties();
            });
            relatedLinkList.removeList();
            relatedStatement.remove();
        }

        exportModel.getNsPrefixMap().forEach((key, value) -> {
            exportModel.removeNsPrefix(key);
        });

        exportModel.setNsPrefixes(amodel.asGraph().getNsPrefixMap());

        exportModel.remove(oldModel);
        exportModel.add(amodel.asGraph());
        jenaClient.putModelToCore(amodel.getId() + "#ExportGraph", exportModel);
        jenaClient.putModelToCore(amodel.getId(), amodel.asGraph());
    }

    public void deleteModel(AbstractModel amodel) {
        serviceDescriptionManager.deleteGraphDescription(amodel.getId());
        removeModel(amodel.getIRI());
    }

    public void importDataset(String namespace, Dataset dataset) {
        if(!dataset.containsNamedModel(namespace+"HasPartGraph")) {
            throw new IllegalArgumentException("No HasPartGraph defined!");
        }
        if(!isExistingGraph(namespace)) {
            throw new IllegalArgumentException("Cannot import if model is not defined!");
        }

        Model hasPartGraph = dataset.getNamedModel(namespace+"HasPartGraph");
        dataset.removeNamedModel(namespace+"HasPartGraph");
        addToGraph(hasPartGraph, namespace+"HasPartGraph");

        Iterator<String> graphNames = dataset.listNames();
        while(graphNames.hasNext()) {
            String graphName = graphNames.next();
            putToGraph(dataset.getNamedModel(graphName), graphName);
        }
    }

}
