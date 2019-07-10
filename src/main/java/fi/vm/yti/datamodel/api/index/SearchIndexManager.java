package fi.vm.yti.datamodel.api.index;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Singleton;

import org.apache.jena.rdf.model.Model;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fi.vm.yti.datamodel.api.index.model.DeepSearchHitListDTO;
import fi.vm.yti.datamodel.api.index.model.IndexClassDTO;
import fi.vm.yti.datamodel.api.index.model.IndexModelDTO;
import fi.vm.yti.datamodel.api.index.model.IndexPredicateDTO;
import fi.vm.yti.datamodel.api.index.model.ModelSearchRequest;
import fi.vm.yti.datamodel.api.index.model.ModelSearchResponse;
import fi.vm.yti.datamodel.api.index.model.ResourceSearchRequest;
import fi.vm.yti.datamodel.api.index.model.ResourceSearchResponse;
import fi.vm.yti.datamodel.api.model.AbstractClass;
import fi.vm.yti.datamodel.api.model.AbstractPredicate;
import fi.vm.yti.datamodel.api.model.DataModel;
import fi.vm.yti.datamodel.api.service.JenaClient;
import fi.vm.yti.datamodel.api.service.ModelManager;
import fi.vm.yti.datamodel.api.utils.Frames;
import fi.vm.yti.datamodel.api.utils.LDHelper;

@Singleton
@Service
public class SearchIndexManager {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexManager.class);
    private static final String ELASTIC_INDEX_RESOURCE = "dm_resources";
    private static final String ELASTIC_INDEX_MODEL = "dm_models";

    private RestHighLevelClient esClient;
    private final ElasticConnector esManager;
    private final JenaClient jenaClient;
    private final ObjectMapper objectMapper;
    private final ModelManager modelManager;
    private final ModelQueryFactory modelQueryFactory;
    private final DeepResourceQueryFactory deepResourceQueryFactory;
    private final ResourceQueryFactory resourceQueryFactory;

    @Autowired
    public SearchIndexManager(final ElasticConnector esManager,
                              final JenaClient jenaClient,
                              final ObjectMapper objectMapper,
                              final ModelManager modelManager,
                              final ModelQueryFactory modelQueryFactory,
                              final DeepResourceQueryFactory deepClassQueryFactory,
                              final ResourceQueryFactory resourceQueryFactory) {
        this.esManager = esManager;
        this.esClient = esManager.getEsClient();
        this.jenaClient = jenaClient;
        this.objectMapper = objectMapper;
        this.modelManager = modelManager;
        this.modelQueryFactory = modelQueryFactory;
        this.deepResourceQueryFactory = deepClassQueryFactory;
        this.resourceQueryFactory = resourceQueryFactory;
    }

    /**
     * Drop, re-create and fill search indexes (model and resource indexes, the latter containing classes and predicates).
     */
    public void reindex() {
        try {
            esManager.cleanIndex(ELASTIC_INDEX_RESOURCE);
            esManager.cleanIndex(ELASTIC_INDEX_MODEL);
            logger.info("Indexes cleaned");
            esManager.createIndex(ELASTIC_INDEX_RESOURCE, getResourceMappings());
            esManager.createIndex(ELASTIC_INDEX_MODEL, getModelMappings());
            initSearchIndexes();
            logger.info("Indexes initialized");
        } catch (IOException ex) {
            logger.warn("Reindex failed!", ex);
        }
    }

    public void createIndexClass(AbstractClass classResource) {
        IndexClassDTO indexClass = new IndexClassDTO(classResource);
        logger.debug("Indexing: " + indexClass.getId());
        esManager.putToIndex(ELASTIC_INDEX_RESOURCE, indexClass.getId(), indexClass);
    }

    public void updateIndexClass(AbstractClass classResource) {
        IndexClassDTO indexClass = new IndexClassDTO(classResource);
        logger.debug("Indexing: " + indexClass.getId());
        esManager.updateToIndex(ELASTIC_INDEX_RESOURCE, indexClass.getId(), indexClass);
    }

    public void removeClass(String id) {
        esManager.removeFromIndex(id, ELASTIC_INDEX_RESOURCE);
    }

    public void createIndexPredicate(AbstractPredicate predicateResource) {
        IndexPredicateDTO indexPredicate = new IndexPredicateDTO(predicateResource);
        logger.info("Indexing: " + indexPredicate.getId());
        esManager.putToIndex(ELASTIC_INDEX_RESOURCE, indexPredicate.getId(), indexPredicate);
    }

    public void updateIndexPredicate(AbstractPredicate predicateResource) {
        IndexPredicateDTO indexPredicate = new IndexPredicateDTO(predicateResource);
        logger.info("Indexing: " + indexPredicate.getId());
        esManager.updateToIndex(ELASTIC_INDEX_RESOURCE, indexPredicate.getId(), indexPredicate);
    }

    public void removePredicate(String id) {
        esManager.removeFromIndex(id, ELASTIC_INDEX_RESOURCE);
    }

    public void removeModel(String id) {
        try {
            DeleteByQueryRequest resourceRequest = new DeleteByQueryRequest(ELASTIC_INDEX_RESOURCE);
            resourceRequest.setQuery(QueryBuilders.termQuery("isDefinedBy", id));
            BulkByScrollResponse resourceResponse = esClient.deleteByQuery(resourceRequest, RequestOptions.DEFAULT);
            logger.info("Removed " + resourceResponse.getDeleted() + " resources from \"" + ELASTIC_INDEX_RESOURCE + "\" for model \"" + id + "\"");
        } catch (Exception e) {
            logger.warn("Could not delete resources for model " + id + " from index", e);
        }

        // NOTE: The following should have refresh policy causing removal to have effect on immediate searches
        esManager.removeFromIndex(id, ELASTIC_INDEX_MODEL);
    }

    public void createIndexModel(DataModel model) {
        IndexModelDTO indexModel = new IndexModelDTO(model);
        logger.info("Indexing: " + indexModel.getId());
        esManager.putToIndex(ELASTIC_INDEX_MODEL, indexModel.getId(), indexModel);
    }

    public void updateIndexModel(DataModel model) {
        IndexModelDTO indexModel = new IndexModelDTO(model);
        logger.info("Indexing: " + indexModel.getId());
        esManager.updateToIndex(ELASTIC_INDEX_MODEL, indexModel.getId(), indexModel);
    }

    public ModelSearchResponse searchModels(ModelSearchRequest request,
                                            Set<UUID> privilegedOrganizations) {
        request.setQuery(request.getQuery() != null ? request.getQuery().trim() : "");

        Map<String, List<DeepSearchHitListDTO<?>>> deepSearchHits = null;

        if (request.isSearchResources() && !request.getQuery().isEmpty()) {
            try {
                SearchRequest query = deepResourceQueryFactory.createQuery(request.getQuery(), request.getSortLang(), privilegedOrganizations);
                SearchResponse response = esClient.search(query, RequestOptions.DEFAULT);
                deepSearchHits = deepResourceQueryFactory.parseResponse(response, request);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            SearchRequest finalQuery;
            if (deepSearchHits != null && !deepSearchHits.isEmpty()) {
                Set<String> additionalModelIds = deepSearchHits.keySet();
                logger.debug("Deep model search resulted in " + additionalModelIds.size() + " model matches");
                finalQuery = modelQueryFactory.createQuery(request, additionalModelIds, privilegedOrganizations);
            } else {
                finalQuery = modelQueryFactory.createQuery(request, privilegedOrganizations);
            }
            SearchResponse response = esClient.search(finalQuery, RequestOptions.DEFAULT);
            return modelQueryFactory.parseResponse(response, request, deepSearchHits);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public ResourceSearchResponse searchResources(ResourceSearchRequest request) {
        request.setQuery(request.getQuery() != null ? request.getQuery().trim() : "");
        try {
            SearchRequest finalQuery;
            finalQuery = resourceQueryFactory.createQuery(request);
            SearchResponse response = esClient.search(finalQuery, RequestOptions.DEFAULT);
            return resourceQueryFactory.parseResponse(response, request);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getResourceMappings() throws IOException {
        InputStream is = SearchIndexManager.class.getClassLoader().getResourceAsStream("resource_mapping.json");
        Object obj = objectMapper.readTree(is);
        return objectMapper.writeValueAsString(obj);
    }

    private String getModelMappings() throws IOException {
        InputStream is = SearchIndexManager.class.getClassLoader().getResourceAsStream("model_mapping.json");
        Object obj = objectMapper.readTree(is);
        return objectMapper.writeValueAsString(obj);
    }

    private void bulkInsert(String indexName,
                            JsonNode resourceList) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();
        resourceList = resourceList.get("@graph");
        resourceList.forEach(resource -> {
            String resourceId = resource.get("id").asText();
            if (resourceId.startsWith("iow:")) {
                resourceId = LDHelper.curieToURI(resourceId);
            }
            IndexRequest indexRequest = new IndexRequest(indexName, "doc", LDHelper.encode(resourceId)).
                source(objectMapper.convertValue(resource, Map.class));
            bulkRequest.add(indexRequest);
        });
        BulkResponse bresp = esClient.bulk(bulkRequest, RequestOptions.DEFAULT);
        logger.debug("Bulk insert status: " + bresp.status().getStatus());
    }

    private void initModelIndex() throws IOException {
        String qry = LDHelper.prefix + "CONSTRUCT {" +
            "?model rdfs:label ?prefLabel . " +
            "?model rdfs:comment ?comment . " +
            "?model dcterms:description ?definition . " +
            "?model dcterms:modified ?modified . " +
            "?model a ?modelType . " +
            "?model owl:versionInfo ?versionInfo . " +
            "?model iow:useContext ?useContext . " +
            "?model dcap:preferredXMLNamespaceName ?namespace . " +
            "?model dcap:preferredXMLNamespacePrefix ?prefix .  " +
            "?model dcterms:contributor ?orgID . " +
            "?model dcterms:isPartOf ?groupID . " +
            "} WHERE { " +
            "GRAPH ?model { " +
            "?model a owl:Ontology . " +
            "?model rdfs:label ?prefLabel . " +
            "OPTIONAL {?model rdfs:comment ?comment . FILTER(lang(?comment)!='') }" +
            "OPTIONAL { ?model iow:useContext ?useContext . }" +
            "?model owl:versionInfo ?versionInfo . " +
            "?model dcap:preferredXMLNamespaceName ?namespace . " +
            "?model dcap:preferredXMLNamespacePrefix ?prefix .  " +
            "?model a ?modelType . VALUES ?modelType { dcap:MetadataVocabulary dcap:DCAP }" +
            "?model dcterms:modified ?modified . " +
            "?model dcterms:contributor ?org . BIND(strafter(str(?org), 'urn:uuid:') AS ?orgID) " +
            "?model dcterms:isPartOf ?group . ?group dcterms:identifier ?groupID . }}";

        Model model = jenaClient.constructFromCore(qry);
        if (model.size() < 1) {
            logger.warn("Could not find any models to index!");
            return;
        }
        JsonNode nodes = modelManager.toFramedJsonNode(model, Frames.esModelFrame);
        if (nodes == null) {
            logger.warn("Could not parse JSON");
            return;
        }
        bulkInsert(ELASTIC_INDEX_MODEL, nodes);
    }

    private void initClassIndex() throws IOException {
        String qry = LDHelper.prefix + " CONSTRUCT {" +
            "?class sh:name ?prefLabel . " +
            "?class sh:description ?definition . " +
            "?class rdfs:isDefinedBy ?model . " +
            "?class dcterms:modified ?modified . " +
            "?class owl:versionInfo ?status . " +
            "?class a ?type . " +
            "?class dcterms:contributor ?orgID . " +
            "} WHERE { " +
            "GRAPH ?class { ?class rdf:type ?classType . VALUES ?classType { sh:NodeShape rdfs:Class }" +
            "?class sh:name ?prefLabel . " +
            "?class owl:versionInfo ?status . " +
            "OPTIONAL { ?class sh:description ?definition . FILTER(lang(?definition)!='')}" +
            "?class a ?type . " +
            "?class dcterms:modified ?modified . " +
            "?class rdfs:isDefinedBy ?model . }" +
            "GRAPH ?model {?model a owl:Ontology  . ?model rdfs:label ?label . " +
            "?model a ?modelType . VALUES ?modelType { dcap:MetadataVocabulary dcap:DCAP } . " +
            "?model dcterms:contributor ?org . BIND(strafter(str(?org), 'urn:uuid:') AS ?orgID) }}";
        Model model = jenaClient.constructFromCore(qry);
        if (model.size() < 1) {
            logger.warn("Could not find any classes to index!");
            return;
        }
        JsonNode nodes = modelManager.toFramedJsonNode(model, Frames.esClassFrame);
        if (nodes == null) {
            logger.warn("Could not parse JSON");
            return;
        }
        bulkInsert(ELASTIC_INDEX_RESOURCE, nodes);

    }

    private void initPredicateIndex() throws IOException {
        String qry = LDHelper.prefix + " CONSTRUCT {" +
            "?predicate rdfs:label ?prefLabel . " +
            "?predicate a ?predicateType . " +
            "?predicate dcterms:modified ?modified . " +
            "?predicate rdfs:range ?range . " +
            "?predicate rdfs:comment ?definition . " +
            "?predicate rdfs:isDefinedBy ?model . " +
            "?predicate owl:versionInfo ?status . " +
            "?predicate dcterms:contributor ?orgID . " +
            "} WHERE { " +
            "GRAPH ?predicate { ?predicate a ?predicateType . VALUES ?predicateType { owl:ObjectProperty owl:DatatypeProperty }" +
            "?predicate rdfs:label ?prefLabel . " +
            "OPTIONAL { ?predicate rdfs:range ?range . } " +
            "?predicate owl:versionInfo ?status . " +
            "?predicate dcterms:modified ?modified . " +
            "OPTIONAL { ?predicate rdfs:comment ?definition . FILTER(lang(?definition)!='')}" +
            "?class rdfs:isDefinedBy ?model . }" +
            "GRAPH ?model {?model a owl:Ontology  . ?model rdfs:label ?label . " +
            "?model a ?modelType . VALUES ?modelType { dcap:MetadataVocabulary dcap:DCAP } ." +
            "?model dcterms:contributor ?org . BIND(strafter(str(?org), 'urn:uuid:') AS ?orgID) }}";
        Model model = jenaClient.constructFromCore(qry);
        if (model.size() < 1) {
            logger.warn("Could not find any associations to index!");
            return;
        }
        JsonNode nodes = modelManager.toFramedJsonNode(model, Frames.esPredicateFrame);
        if (nodes == null) {
            logger.warn("Could not parse JSON");
            return;
        }
        bulkInsert(ELASTIC_INDEX_RESOURCE, nodes);
    }

    private void initSearchIndexes() throws IOException {
        initClassIndex();
        initPredicateIndex();
        initModelIndex();
    }
}
