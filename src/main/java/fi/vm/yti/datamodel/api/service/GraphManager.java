/*
 * Licensed under the European Union Public Licence (EUPL) V.1.1 
 */
package fi.vm.yti.datamodel.api.service;

import fi.vm.yti.datamodel.api.config.ApplicationProperties;
import fi.vm.yti.datamodel.api.model.AbstractModel;
import fi.vm.yti.datamodel.api.model.AbstractResource;
import fi.vm.yti.datamodel.api.utils.LDHelper;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.DatasetAccessor;
import org.apache.jena.query.DatasetAccessorFactory;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.apache.jena.system.Txn;
import org.apache.jena.update.UpdateException;
import org.apache.jena.update.UpdateExecutionFactory;
import org.apache.jena.update.UpdateProcessor;
import org.apache.jena.update.UpdateRequest;

import java.util.Date;
import java.util.logging.Level;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
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

@Service
public class GraphManager {

    private static final Logger logger = LoggerFactory.getLogger(GraphManager.class.getName());

    private final EndpointServices endpointServices;
    private final JenaClient jenaClient;
    private final TermedTerminologyManager termedTerminologyManager;
    private final ModelManager modelManager;
    private final ApplicationProperties properties;
    private final ServiceDescriptionManager serviceDescriptionManager;

    @Autowired
    GraphManager(EndpointServices endpointServices,
                 JenaClient jenaClient,
                 TermedTerminologyManager termedTerminologyManager,
                 ModelManager modelManager,
                 ServiceDescriptionManager serviceDescriptionManager,
                 ApplicationProperties properties) {

        this.endpointServices = endpointServices;
        this.jenaClient = jenaClient;
        this.termedTerminologyManager = termedTerminologyManager;
        this.modelManager = modelManager;
        this.serviceDescriptionManager = serviceDescriptionManager;
        this.properties = properties;
    }

    /**
     * Returns true if there are some services defined
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
            logger.warn( "Default graph test failed", ex);
            return false;
        }
    }

    /**
     * Creates export graph by joining all the resources to one graph
     * @param graph model IRI that is used to create export graph
     */
    public void constructExportGraph(String graph) {

             String queryString = "CONSTRUCT { "
                + "?model <http://purl.org/dc/terms/hasPart> ?resource . "    
                + "?ms ?mp ?mo . "
                + "?rs ?rp ?ro . "
                + " } WHERE {"
                + " GRAPH ?model {"
                + "?ms ?mp ?mo . "
                + "} OPTIONAL {"
                + "GRAPH ?modelHasPartGraph { "
                + " ?model <http://purl.org/dc/terms/hasPart> ?resource . "
                + " } GRAPH ?resource { "
                + "?rs ?rp ?ro . "
                + "}"
                + "}}"; 
          
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(queryString);
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("model", graph);
        pss.setIri("modelHasPartGraph", graph+"#HasPartGraph");

        Query query = pss.asQuery();

        Model exportModel = jenaClient.constructFromService(query.toString(), endpointServices.getCoreSparqlAddress());
        jenaClient.putModelToCore(graph+"#ExportGraph", exportModel);

    }

    public void initServiceCategories() {
        Model m = FileManager.get().loadModel("ptvl-skos.rdf");
        jenaClient.putModelToCore("urn:yti:servicecategories",m);
    }

    /**
     * Deleted export graph
     * @param graph IRI of the graph that is going to be deleted
     */
    public void deleteExportModel(String graph) {
        jenaClient.deleteModelFromCore(graph+"#ExportGraph");
    }
    
    /**
     * Creates new graph with owl:Ontology type
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
     * @param graph String IRI of the graph
     * @return Returns graph as Jena model
     */
    public Model getCoreGraph(String graph){
        return jenaClient.getModelFromCore(graph);
    }

    /**
     * Returns graph from core service
     * @param graph IRI of the graph
     * @return Returns graph as Jena model
     */
    public Model getCoreGraph(IRI graph){

        try {
            return getCoreGraph(graph.toString());
        } catch(HttpException ex) {
            logger.warn(ex.toString());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return getCoreGraph(graph.toString());
        }
    }
    
    /**
     * Test if model status restricts removing of the model
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
     * @param prefix Used prefix of the namespace
     * @return true if prefix is in use by existing model or standard
     */
    public boolean isExistingPrefix(String prefix) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String queryString = " ASK { {GRAPH ?graph { ?s ?p ?o . }} UNION { ?s a dcterms:Standard . ?s dcap:preferredXMLNamespacePrefix ?prefix . }}";
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(queryString);
        pss.setLiteral("prefix", prefix);
        pss.setIri("graph",properties.getDefaultNamespace()+prefix);

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
     * @param graphIRI IRI of the graphs
     * @return Returns true if Graph with the given IRI
     */
    public boolean isExistingGraph(IRI graphIRI) {

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
     * Check if Graph is existing
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
     * @param graphIRI IRI of the graph as String
     * @return Returns true if there is a graph in Service description
     */
    public boolean isExistingServiceGraph(String graphIRI) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        
        String queryString = " ASK { GRAPH <urn:csc:iow:sd> { " +
                " ?service a sd:Service . "+
                " ?service sd:availableGraphs ?graphCollection . "+
                " ?graphCollection a sd:GraphCollection . "+
                " ?graphCollection sd:namedGraph ?graph . "+
                " ?graph sd:name ?graphName . "+
                "}}";
        
        if(graphIRI.endsWith("#")) graphIRI = graphIRI.substring(0,graphIRI.length()-1);
        
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
     * @param prefix Prefix used in some model
     * @return Returns graph IRI with given prefix
     */
    public String getServiceGraphNameWithPrefix(String prefix) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String selectResources =
                "SELECT ?graph WHERE { "
                + "GRAPH ?graph { "+
                " ?graph a owl:Ontology . "
                + "?graph dcap:preferredXMLNamespacePrefix ?prefix . "+
                "}}";

        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(selectResources);
        pss.setLiteral("prefix",prefix);


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
     * @param model Model id as IRI String
     * @return Returns remove query as string
     */
    public String buildRemoveModelQuery(String model) {

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String selectResources = "SELECT ?graph WHERE { GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph . } GRAPH ?graph { ?graph rdfs:isDefinedBy ?model . }}";

        pss.setIri("model", model);
        pss.setIri("hasPartGraph",model+"#HasPartGraph");
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
            logger.warn( ex.toString());
        }
    }
    
    /**
     * TODO: remove when resolved JENA-1255. This removes model graph by putting empty model to the graph.
     * @param id
     */
    public void namespaceBugFix(String id) {
        Model empty = ModelFactory.createDefaultModel();
        jenaClient.putModelToCore(id, empty);
    }

    /**
     * Tries to remove single graph
     * @param id IRI of the graph to be removed
     */
    public void removeGraph(IRI id) {

        String query = "DROP GRAPH ?graph ;";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(query);
        pss.setIri("graph", id);

        logger.warn( "Removing graph " + id);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());

        try {
            qexec.execute();
        } catch (UpdateException ex) {
            logger.warn( ex.toString());
        }
    }

    /**
     * Tries to remove single graph
     * @param id String IRI of the graph to be removed
     */
    public void removeGraph(String id) {

        String query = "DROP GRAPH ?graph ;";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(query);
        pss.setIri("graph", id);

        logger.warn( "Removing graph " + id);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());

        try {
            qexec.execute();
        } catch (UpdateException ex) {
            logger.warn( ex.toString());
        }
    }
    
    
    /**
     * Tries to Delete contents of the resource graphs linked to the model graph
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

        logger.warn( pss.toString() + " DROPPING ALL FROM CORE/PROV/SKOS SERVICES");

        UpdateRequest queryObj = pss.asUpdate();
        
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
        
        qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getProvSparqlUpdateAddress());
        qexec.execute();
        
        qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getTempConceptSparqlUpdateAddress());
        qexec.execute();
            
    }

    public static UpdateRequest renameIDRequest(IRI oldID, IRI newID) {
        String query
                = " DELETE { GRAPH ?hasPartGraph { ?graph dcterms:hasPart ?oldID }}"
                + " INSERT { GRAPH ?hasPartGraph { ?graph dcterms:hasPart ?newID }}"
                + " WHERE { GRAPH ?hasPartGraph { ?graph dcterms:hasPart ?oldID }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setCommandText(query);

        logger.warn( "Renaming " + oldID + " to " + newID);

        return pss.asUpdate();
    }
    
    /**
     * Renames IRI:s in HasPart-graph
     * @param oldID Old id IRI
     * @param newID New id IRI
     */
    public void renameID(IRI oldID, IRI newID) {
        UpdateRequest queryObj = renameIDRequest(oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();
    }
    
    /**
     * Renames Association target IRI:s
     * @param modelID Model IRI
     * @param oldID Old resource IRI
     * @param newID New resource IRI
     */
    public void updateClassReferencesInModel(IRI modelID, IRI oldID, IRI newID) {

        String query
                = " DELETE { GRAPH ?graph { ?any sh:valueShape ?oldID }} "
                + " INSERT { GRAPH ?graph { ?any sh:valueShape ?newID }} "
                + " WHERE { "
                + "GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph . } "
                + "GRAPH ?graph { ?graph rdfs:isDefinedBy ?model . ?any sh:valueShape ?oldID}}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setIri("model", modelID);
        pss.setIri("hasPartGraph", modelID+"#HasPartGraph");
        pss.setCommandText(query);

        logger.info(pss.toString());
        logger.warn( "Updating references in "+modelID.toString());

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    public static UpdateRequest updateReferencesInPositionGraphRequest(IRI modelID, IRI oldID, IRI newID) {
        String query
                = " DELETE { GRAPH ?graph { ?oldID ?anyp ?anyo . }} "
                + " INSERT { GRAPH ?graph { ?newID ?anyp ?anyo . }} "
                + " WHERE { "
                + "GRAPH ?graph { ?oldID ?anyp ?anyo . }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setIri("graph", modelID+"#PositionGraph");
        pss.setCommandText(query);

        logger.info(pss.toString());
        logger.warn( "Updating references in "+modelID.toString()+"#PositionGraph");

        return pss.asUpdate();
    }
    
    /**
     * Updates IRI:s in Position-graph
     * @param modelID Model IRI
     * @param oldID Old resource IRI
     * @param newID New resource IRI
     */
    public void updateReferencesInPositionGraph(IRI modelID, IRI oldID, IRI newID) {

        UpdateRequest queryObj = updateReferencesInPositionGraphRequest(modelID, oldID, newID);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    /**
     * Renames Predicate IRI:s
     * @param modelID Model IRI
     * @param oldID Old Predicate IRI
     * @param newID New Predicate IRI
     */
    public void updateResourceReferencesInModel(IRI modelID, IRI oldID, IRI newID) {

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
        pss.setIri("hasPartGraph", modelID+"#HasPartGraph");
        pss.setCommandText(query);

        logger.info(pss.toString());
        logger.warn( "Updating references in "+modelID.toString());

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }
    
    /**
     * Renames Predicate IRI:s
     * @param modelID Model IRI
     * @param oldID Old Predicate IRI
     * @param newID New Predicate IRI
     */
    public void updatePredicateReferencesInModel(IRI modelID, IRI oldID, IRI newID) {

        String query
                = " DELETE { GRAPH ?graph { ?any sh:predicate ?oldID }} "
                + " INSERT { GRAPH ?graph { ?any sh:predicate ?newID }} "
                + " WHERE { "
                + "GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph . } "
                + "GRAPH ?graph { ?graph rdfs:isDefinedBy ?model . ?any sh:predicate ?oldID}}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("oldID", oldID);
        pss.setIri("newID", newID);
        pss.setIri("model", modelID);
        pss.setIri("hasPartGraph", modelID+"#HasPartGraph");
        pss.setCommandText(query);

        logger.info(pss.toString());
        logger.warn( "Updating references in "+modelID.toString());

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    public UpdateRequest insertNewGraphReferenceToModelRequest(String graph, String model ) {
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
        pss.setIri("hasPartGraph", model+"#HasPartGraph");
        pss.setLiteral("date", timestamp);
        pss.setCommandText(query);

        return pss.asUpdate();
    }

    /**
     * Inserts Resource reference to models HasPartGraph and model reference to Resource graph
     * @param graph Resource graph IRI as String
     * @param model Model graph IRI as String
     */
    public void insertNewGraphReferenceToModel(String graph, String model) {
        UpdateRequest queryObj = insertNewGraphReferenceToModelRequest(graph, model);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }


    public static UpdateRequest insertNewGraphReferenceToExportGraphRequest(String graph, String model) {
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
        pss.setIri("exportGraph", model+"#ExportGraph");
        pss.setCommandText(query);
        return pss.asUpdate();
    }

    /**
     * Insert new graph reference to export graph. In some cases it makes more sense to do small changes than create the whole export graph again.
     * @param graph Resource IRI as String
     * @param model Model IRI as String
     */
    public void insertNewGraphReferenceToExportGraph(String graph, String model) {

        UpdateRequest queryObj = insertNewGraphReferenceToExportGraphRequest(graph, model);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    public UpdateRequest insertExistingGraphReferenceToModelRequest(String graph, String model) {
        String query
                = " INSERT { GRAPH ?hasPartGraph { ?model dcterms:hasPart ?graph }} "
                + " WHERE { GRAPH ?graph { ?graph a ?type . }}";

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setIri("graph", graph);
        pss.setIri("model", model);
        pss.setIri("hasPartGraph", model+"#HasPartGraph");
        pss.setCommandText(query);
        return pss.asUpdate();
    }


    /**
     * Add existing Resource to the model as Resource reference. Inserts only the reference to models HasPartGraph.
     * @param graph Resource IRI as String
     * @param model Model IRI as String
     */
    public void insertExistingGraphReferenceToModel(String graph, String model) {

        UpdateRequest queryObj = insertExistingGraphReferenceToModelRequest(graph, model);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }


    public UpdateRequest deleteGraphReferenceFromModelRequest(IRI graph, IRI model) {
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
        pss.setIri("hasPartGraph", model+"#HasPartGraph");
        pss.setCommandText(query);

        return pss.asUpdate();
    }

    /**
     * Removes Resource-graph reference from models HasPartGraph
     * @param graph Resource IRI reference to be removed
     * @param model Model IRI
     */
    public void deleteGraphReferenceFromModel(IRI graph, IRI model) {

        UpdateRequest queryObj = deleteGraphReferenceFromModelRequest(graph, model);
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }

    /**
     * Removes Resource-graph reference from models HasPartGraph
     * @param graph Resource IRI reference to be removed
     * @param model Model IRI
     */
    public void deleteGraphReferenceFromExportModel(IRI graph, IRI model) {

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
        pss.setIri("exportGraph", model+"#ExportGraph");
        pss.setIri("hasPartGraph", model+"#HasPartGraph");
        pss.setCommandText(query);

        UpdateRequest queryObj = pss.asUpdate();
        UpdateProcessor qexec = UpdateExecutionFactory.createRemoteForm(queryObj, endpointServices.getCoreSparqlUpdateAddress());
        qexec.execute();

    }



    /**
     * Copies graph from one Service to another Service
     * @param fromGraph Existing graph in original service as String
     * @param toGraph  New graph IRI as String
     * @param fromService Service where graph exists
     * @param toService Service where graph is copied
     * @throws NullPointerException
     */
    public static void addGraphFromServiceToService(String fromGraph, String toGraph, String fromService, String toService) throws NullPointerException {
        
        DatasetAccessor fromAccessor = DatasetAccessorFactory.createHTTP(fromService);
        Model graphModel = fromAccessor.getModel(fromGraph);
        
        if(graphModel==null) {
            throw new NullPointerException();
        }
        
        DatasetAccessor toAccessor = DatasetAccessorFactory.createHTTP(toService);
        toAccessor.add(toGraph, graphModel);
        
    }

    public void insertExistingResourceToModel(String id, String model) {
        try(RDFConnectionRemote conn = endpointServices.getCoreConnection()) {
            Txn.executeWrite(conn, ()-> {
                conn.update(insertExistingGraphReferenceToModelRequest(id, model));
                conn.update(insertNewGraphReferenceToExportGraphRequest(id, model));
                conn.load(LDHelper.encode(model+"#ExportGraph"),conn.fetch(LDHelper.encode(id)));
              });
        }
    }

    /**
     * Copies graph to another graph in Core service
     * @param fromGraph Graph IRI as string
     * @param toGraph New copied graph IRI as string
     * @throws NullPointerException
     */
    public void addCoreGraphToCoreGraph(String fromGraph, String toGraph) throws NullPointerException {
        
        DatasetAccessor fromAccessor = DatasetAccessorFactory.createHTTP(endpointServices.getCoreReadWriteAddress());
        Model graphModel = fromAccessor.getModel(fromGraph);
        
        if(graphModel==null) {
            throw new NullPointerException();
        }

        fromAccessor.add(toGraph, graphModel);
    }

    /**
     * Put model to graph
     * @param model Jena model
     * @param id IRI of the graph as String
     */
    public void putToGraph(Model model, String id) {
        logger.debug("Putting to "+id);
        try(RDFConnectionRemote conn = endpointServices.getCoreConnection()) {
            Txn.executeWrite(conn, ()->{
                conn.put(LDHelper.encode(id), model);
            });
        } catch(Exception ex) {
            logger.warn(ex.getMessage());
        }
       /*
      DatasetGraphAccessorHTTP accessor = new DatasetGraphAccessorHTTP(services.getCoreReadWriteAddress());
      DatasetAdapter adapter = new DatasetAdapter(accessor);
      adapter.putModel(id, model);
        */
    }
    
    /**
     * Constructs JSON-LD from graph
     * @param query SPARQL query as string
     * @return Returns JSON-LD object
     */
    public String constructStringFromGraph(String query) {
            Model results = jenaClient.constructFromService(query, endpointServices.getCoreSparqlAddress());
            return modelManager.writeModelToJSONLDString(results);
    }

    /**
     * Constructs JSON-LD from graph
     * @param query SPARQL query as string
     * @return Returns JSON-LD object
     */
    public Model constructModelFromCoreGraph(String query){
       try (RDFConnectionRemote conn = endpointServices.getCoreConnection()) {
            return conn.queryConstruct(query);
        } catch (Exception ex) {
            logger.warn(ex.getMessage());
            return null;
        }
    }


    public Model constructModelFromService(String query, String service) {
        try (RDFConnectionRemote conn = endpointServices.getServiceConnection(service)) {
            return conn.queryConstruct(query);
        } catch (Exception ex) {
            logger.warn(ex.getMessage());
            return null;
        }

    }


        /**
         * Construct from combined model of Concept and Model
         * @param conceptID ID of concept
         * @param modelID ID of model
         * @param query Construct SPARQL query
         * @return created Jena model
         */
    @Deprecated
    public Model constructModelFromConceptAndCore(String conceptID, String modelID, Query query) {

        Model conceptModel = termedTerminologyManager.getConceptAsJenaModel(conceptID);

        DatasetAccessor testAcc = DatasetAccessorFactory.createHTTP(endpointServices.getCoreReadAddress());
        conceptModel.add(testAcc.getModel(modelID));

        try(QueryExecution qexec = QueryExecutionFactory.create(query,conceptModel)) {
            Model resultModel = qexec.execConstruct();
            return resultModel;
        }
    }


    /**
     * Returns date when the model was last modified from the Export graph
     * @param graphName Graph IRI as string
     * @return Returns date
     */
    public Date lastModified(String graphName) {
    
     ParameterizedSparqlString pss = new ParameterizedSparqlString();
        String selectResources =
                "SELECT ?date WHERE { "
                + "GRAPH ?exportGraph { "+
                " ?graph a owl:Ontology . "
                + "?graph dcterms:modified ?date . "+
                "}}";

        pss.setNsPrefixes(LDHelper.PREFIX_MAP);
        pss.setCommandText(selectResources);
        pss.setIri("graph",graphName);
        pss.setIri("exportGraph",graphName+"#ExportGraph");

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
        jenaClient.addModelToCore(resource.getModelId()+"#ExportGraph", exportModel);
    }

    public void updateResource(AbstractResource resource) {
        Model oldModel = jenaClient.getModelFromCore(resource.getId());
        Model exportModel = jenaClient.getModelFromCore(resource.getModelId()+"#ExportGraph");

        exportModel = modelManager.removeResourceStatements(oldModel, exportModel);
        exportModel.add(resource.asGraph());

        jenaClient.putModelToCore(resource.getModelId()+"#ExportGraph", exportModel);
        jenaClient.putModelToCore(resource.getId(), resource.asGraph());
    }

    public void updateResourceWithNewId(IRI oldIdIRI, AbstractResource resource) {
        Model oldModel = jenaClient.getModelFromCore(oldIdIRI.toString());
        Model exportModel = jenaClient.getModelFromCore(resource.getModelId()+"#ExportGraph");

        exportModel = modelManager.removeResourceStatements(oldModel, exportModel);
        exportModel.add(resource.asGraph());
        jenaClient.putModelToCore(resource.getModelId()+"#ExportGraph", exportModel);

        jenaClient.putModelToCore(resource.getId(), resource.asGraph());

        removeGraph(oldIdIRI);
        renameID(oldIdIRI,resource.getIRI());
        updateReferencesInPositionGraph(resource.getModelIRI(), oldIdIRI, resource.getIRI());
    }

    public void deleteResource(AbstractResource resource) {
        Model exportModel = jenaClient.getModelFromCore(resource.getModelId()+"#ExportGraph");
        exportModel = modelManager.removeResourceStatements(resource.asGraph(), exportModel);
        exportModel.remove(exportModel.createResource(resource.getModelId()), DCTerms.hasPart, exportModel.createResource(resource.getId()));
        jenaClient.putModelToCore(resource.getModelId()+"#ExportGraph", exportModel);
        deleteGraphReferenceFromModel(resource.getIRI(),resource.getModelIRI());
        jenaClient.deleteModelFromCore(resource.getId());
    }

    public void createModel(AbstractModel amodel) {
        logger.info("Creating model "+amodel.getId());
        jenaClient.putModelToCore(amodel.getId(), amodel.asGraph());
        jenaClient.putModelToCore(amodel.getId()+"#ExportGraph", amodel.asGraph());
    }

    public void updateModel(AbstractModel amodel) {
        LDHelper.rewriteLiteral(amodel.asGraph(), ResourceFactory.createResource(amodel.getId()), DCTerms.modified, LDHelper.getDateTimeLiteral());
        Model oldModel = jenaClient.getModelFromCore(amodel.getId());
        Model exportModel = jenaClient.getModelFromCore(amodel.getId()+"#ExportGraph");

        // OMG: Model.remove() doesnt remove RDFLists
        Statement languageStatement = exportModel.getRequiredProperty(ResourceFactory.createResource(amodel.getId()), DCTerms.language);
        RDFList languageList = languageStatement.getObject().as(RDFList.class);
        languageList.removeList();
        languageStatement.remove();

        exportModel.remove(oldModel);
        exportModel.add(amodel.asGraph());
        jenaClient.putModelToCore(amodel.getId()+"#ExportGraph", exportModel);
        jenaClient.putModelToCore(amodel.getId(), amodel.asGraph());
    }

    public void deleteModel(AbstractModel amodel) {
        serviceDescriptionManager.deleteGraphDescription(amodel.getId());
        removeModel(amodel.getIRI());
    }


}