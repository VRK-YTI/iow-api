package fi.vm.yti.datamodel.api.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jsonldjava.core.JsonLdOptions;
import com.github.jsonldjava.core.JsonLdProcessor;
import com.github.jsonldjava.utils.JsonUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.apache.jena.iri.IRI;
import org.apache.jena.iri.IRIFactory;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RiotException;
import org.glassfish.jersey.uri.UriComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.json.Json;
import javax.json.JsonObject;
import javax.ws.rs.core.Response;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidParameterException;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author malonen
 */
public class LDHelper {

    private static final Logger logger = LoggerFactory.getLogger(LDHelper.class);

    private static final IRIFactory iriFactory = IRIFactory.iriImplementation();

    public static final String[] UNRESOLVABLE = { "xsd", "iow", "text", "sh", "afn", "schema", "dcap", "termed" };

    public static final String[] RESERVEDWORDS = {
        "urn",
        "http",
        "https",
        "abstract",
        "and",
        "andCond",
        "class",
        "classIn",
        "codeLists",
        "comment",
        "contributor",
        "constraint",
        "context",
        "created",
        "creator",
        "datatype",
        "defaultValue",
        "definition",
        "description",
        "editorialNote",
        "equivalentClass",
        "equivalentProperty",
        "example",
        "first",
        "graph",
        "hasPart",
        "hasValue",
        "homepage",
        "id",
        "identifier",
        "imports",
        "inScheme",
        "inValues",
        "isDefinedBy",
        "isPartOf",
        "isResourceIdentifier",
        "isXmlAttribute",
        "isXmlWrapper",
        "last",
        "label",
        "language",
        "languageIn",
        "localName",
        "maxCount",
        "maxLength",
        "memberOf",
        "minCount",
        "name",
        "node",
        "nodeKind",
        "not",
        "notCond",
        "or",
        "orCond",
        "path",
        "pattern",
        "pointXY",
        "preferredXMLNamespaceName",
        "preferredXMLNamespacePrefix",
        "prefLabel",
        "property",
        "predicate",
        "range",
        "readOnlyValue",
        "references",
        "relations",
        "requires",
        "rootResource",
        "rest",
        "stem",
        "subClassOf",
        "subject",
        "subPropertyOf",
        "targetClass",
        "title",
        "type",
        "uniqueLang",
        "useContext",
        "uri",
        "versionInfo",
        "vertexXY",
        "xor" };

    public static boolean isReservedWord(String item) {
        return Arrays.stream(RESERVEDWORDS).anyMatch(item::equals);
    }

    public static String encode(String param) {
        return UriComponent.encode(param, UriComponent.Type.QUERY_PARAM);
    }

    public static IRI toIRI(String url) {
        return iriFactory.create(url);
    }

    public static boolean isInvalidIRI(String url) {
        IRI testIRI = toIRI(url);
        return testIRI.hasViolation(false);
    }

    /**
     * Used in startup to load external schemas. Returns false if matches any of UNRESOLVABLE array
     *
     * @param item
     * @return boolean
     */
    public static boolean isPrefixResolvable(String item) {
        return !Arrays.stream(UNRESOLVABLE).anyMatch(item::equals);
    }

    public static final Map<String, String> PREFIX_MAP =
        Collections.unmodifiableMap(new HashMap<String, String>() {{
            put("owl", "http://www.w3.org/2002/07/owl#");
            put("xsd", "http://www.w3.org/2001/XMLSchema#");
            put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
            put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
            put("foaf", "http://xmlns.com/foaf/0.1/");
            put("dcterms", "http://purl.org/dc/terms/");
            put("adms", "http://www.w3.org/ns/adms#");
            put("dc", "http://purl.org/dc/elements/1.1/");
            put("void", "http://rdfs.org/ns/void#");
            put("sd", "http://www.w3.org/ns/sparql-service-description#");
            put("text", "http://jena.apache.org/text#");
            put("sh", "http://www.w3.org/ns/shacl#");
            put("iow", "http://uri.suomi.fi/datamodel/ns/iow#");
            put("skos", "http://www.w3.org/2004/02/skos/core#");
            put("prov", "http://www.w3.org/ns/prov#");
            put("dcap", "http://purl.org/ws-mmi-dc/terms/");
            put("afn", "http://jena.hpl.hp.com/ARQ/function#");
            put("schema", "http://schema.org/");
            put("ts", "http://www.w3.org/2003/06/sw-vocab-status/ns#");
            put("dcam", "http://purl.org/dc/dcam/");
            put("termed", "http://termed.thl.fi/meta/");
            put("at", "http://publications.europa.eu/ontology/authority/");
            put("skosxl", "http://www.w3.org/2008/05/skos-xl#");
            put("httpv", "http://www.w3.org/2011/http#");
        }});

    public static Literal getDateTimeLiteral() {
        Calendar cal = GregorianCalendar.getInstance();
        return ResourceFactory.createTypedLiteral(cal);
    }

    public static void rewriteLiteral(Model model,
                                      Resource res,
                                      Property prop,
                                      Literal newLiteral) {
        Selector literalSelector = new SimpleSelector(res, prop, (Literal) null);

        Iterator<Statement> statements = model.listStatements(literalSelector).toList().iterator();

        if (!statements.hasNext()) {
            model.add(res, prop, newLiteral);
        } else {
            while (statements.hasNext()) {
                Statement stat = statements.next();
                stat.changeObject(newLiteral);
            }
        }
    }

    public static void rewriteResourceReference(Model model,
                                                Resource res,
                                                Property prop,
                                                Resource newResource) {
        Selector resourceSelector = new SimpleSelector(res, prop, (Resource) null);

        Iterator<Statement> statements = model.listStatements(resourceSelector).toList().iterator();

        if (!statements.hasNext()) {
            model.add(res, prop, newResource);
        } else {
            while (statements.hasNext()) {
                Statement stat = statements.next();
                stat.changeObject(newResource);
            }
        }
    }

    public static Property curieToProperty(String curieString) {
        return ResourceFactory.createProperty(curieToURI(curieString));
    }

    public static Resource curieToResource(String curieString) {
        return ResourceFactory.createResource(curieToURI(curieString));
    }

    public static String curieToURI(String curie) {
        String[] splitted = curie.split(":");
        return PREFIX_MAP.get(splitted[0]) + splitted[1];
    }

    public static Map<String, String> RDFNodeListToMap(List<RDFNode> nodes) {
        Map<String, String> map = new HashMap<>();
        nodes.forEach(node -> {
            Literal lit = node.asLiteral();
            map.put(lit.getLanguage(), lit.getString());
        });
        return map;
    }

    public static List<String> RDFNodeListToStringList(List<RDFNode> nodes,
                                                       Property pred) {
        List<String> arrList = new ArrayList();
        nodes.forEach(node -> {
            arrList.add(node.asResource().getRequiredProperty(pred).getLiteral().getString());
        });
        return arrList;
    }

    public static final Map<String, Object> CONTEXT_MAP =
        Collections.unmodifiableMap(new HashMap<String, Object>() {{
            put("subClassOf", jsonObject("{ '@id': 'http://www.w3.org/2000/01/rdf-schema#subClassOf', '@type': '@id' }"));
            put("property", jsonObject("{ '@id': 'http://www.w3.org/ns/shacl#property', '@type': '@id' }"));
            put("predicate", jsonObject("{ '@id': 'http://www.w3.org/ns/shacl#predicate', '@type': '@id' }"));
        }});

    public static final Map<String, Object> OPH_MAP =
        Collections.unmodifiableMap(new HashMap<String, Object>() {{
            put("koodistos", jsonObject("{ '@id': 'http://www.w3.org/2000/01/rdf-schema#subClassOf', '@type': '@id' }"));
            put("koodistoUrl", jsonObject("{ '@id': 'http://www.w3.org/ns/shacl#property', '@type': '@id' }"));
            put("predicate", jsonObject("{ '@id': 'http://www.w3.org/ns/shacl#predicate', '@type': '@id' }"));
        }});

    private static Map<String, Object> jsonObject(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
            return mapper.readValue(json, new TypeReference<HashMap<String, Object>>() {
            });
        } catch (IOException ex) {
            logger.warn(ex.getMessage(), ex);
            return null;
        }
    }

    public static String getNamespaceWithPrefix(String prefix) {
        return PREFIX_MAP.get(prefix);
    }

    public final static String prefix = "PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
        "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> " +
        "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
        "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
        "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
        "PREFIX dcterms: <http://purl.org/dc/terms/> " +
        "PREFIX adms: <http://www.w3.org/ns/adms#> " +
        "PREFIX dc: <http://purl.org/dc/elements/1.1/> " +
        "PREFIX void: <http://rdfs.org/ns/void#> " +
        "PREFIX sd: <http://www.w3.org/ns/sparql-service-description#> " +
        "PREFIX text: <http://jena.apache.org/text#> " +
        "PREFIX sh: <http://www.w3.org/ns/shacl#> " +
        "PREFIX skos: <http://www.w3.org/2004/02/skos/core#> " +
        "PREFIX skosxl: <http://www.w3.org/2008/05/skos-xl#> " +
        "PREFIX prov: <http://www.w3.org/ns/prov#> " +
        "PREFIX iow: <http://uri.suomi.fi/datamodel/ns/iow#>" +
        "PREFIX dcap: <http://purl.org/ws-mmi-dc/terms/> " +
        "PREFIX afn: <http://jena.hpl.hp.com/ARQ/function#>" +
        "PREFIX schema: <http://schema.org/>" +
        "PREFIX ts: <http://www.w3.org/2003/06/sw-vocab-status/ns#>" +
        "PREFIX dcam: <http://purl.org/dc/dcam/>" +
        "PREFIX termed: <http://termed.thl.fi/meta/>" +
        "PREFIX at: <http://publications.europa.eu/ontology/authority/>" +
        "PREFIX httpv: <http://www.w3.org/2011/http#>";

    ParameterizedSparqlString pss = new ParameterizedSparqlString();

    static String encodeQuery(String queryString) {
        queryString = prefix + queryString;
        return UriComponent.encode(queryString, UriComponent.Type.QUERY_PARAM); // URLEncoder.encode(queryString, "UTF-8");
    }

    static String prefixQuery(String queryString) {
        return prefix + queryString;
    }

    public static String randomURNUUID() {
        return "urn:uuid:"+UUID.randomUUID().toString();
    }

    public static String concatWithReplace(List<UUID> orgs,
                                           String sep,
                                           String replace) {
        StringBuilder sb = new StringBuilder();
        Iterator<UUID> orgIt = orgs.iterator();
        while (orgIt.hasNext()) {
            String orgID = orgIt.next().toString();

            sb.append(replace.replaceAll("@this", orgID));
            if (orgIt.hasNext()) sb.append(sep);
        }
        return sb.toString();
    }

    public static String concatStringList(List<String> services,
                                          String sep,
                                          String wrap) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> seIt = services.iterator();
        while (seIt.hasNext()) {
            sb.append(wrap + seIt.next() + wrap);
            if (seIt.hasNext()) sb.append(sep);
        }
        return sb.toString();
    }

    public static RDFList addStringListToModel(Model model,
                                               String stringSpaces) {

        RDFList newList = model.createList();
        String[] stringList = stringSpaces.split(" ");
        for (int i = 0; i < stringList.length; i++) {
            newList = newList.with(ResourceFactory.createPlainLiteral(stringList[i]));
        }

        return newList;
    }

    /**
     * Parses allowed languages from , separated string
     *
     * @param allowedLang list of languages separated by ",". If allowedLang is null ('fi','en') is returned.
     * @return parsed language list eg. "('fi','en','sv')"
     */
    public static String parseAllowedLangString(String allowedLang) throws InvalidParameterException {

        if (allowedLang == null || allowedLang.equals("undefined") || allowedLang.length() < 2) {
            allowedLang = "('fi' 'en')";
        } else if (allowedLang.length() == 2 && LDHelper.isAlphaString(allowedLang)) {
            allowedLang = "('" + allowedLang + "')";
        } else {
            if (!allowedLang.contains(" ")) {
                throw new InvalidParameterException();
            }

            String[] languages = allowedLang.split(" ");
            String builtLang = "(";

            for (String s : languages) {
                if (s.length() > 2 || !LDHelper.isAlphaString(s)) {
                    throw new InvalidParameterException();
                }
                builtLang = builtLang.concat(" '" + s + "'");
            }

            builtLang = builtLang.concat(" )");
            allowedLang = builtLang;

        }

        //logger.info("Built langlist: "+allowedLang);
        return allowedLang;
    }

    /**
     * Returns true of string uses alphanumerics only
     *
     * @param name name used in something
     * @return boolean
     */
    public static boolean isAlphaString(String name) {
        return name.matches("[a-zA-Z]+");
    }

    /**
     * Used to strip invalid characters from model name
     *
     * @param name model name
     * @return stripped name
     */

    public static String modelName(String name) {
        name = name.toLowerCase();
        return removeInvalidCharacters(name);
    }

    /**
     * Used to mangle property name
     *
     * @param name property name
     * @return uncapitalized property name
     */
    public static String propertyName(String name) {
        name = StringUtils.uncapitalize(name);
        return removeInvalidCharacters(name);
    }

    public static String preferredName(String name) {
        String splittedName = String.join(" ",name.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])"));
        return Character.toUpperCase(splittedName.charAt(0))+splittedName.substring(1);
    }

    /**
     * Used to mangle resource name
     *
     * @param name resource name
     * @return Capitalized resource name
     */

    public static String resourceName(String name) {
        name = WordUtils.capitalize(name);
        return removeInvalidCharacters(name);
    }

    /**
     * Creates URI based on namespace and resource name
     *
     * @param namespace namespace
     * @param name      resource name
     * @return URI as string
     */
    public static String resourceIRI(String namespace,
                                     String name) {
        if (namespace.endsWith("/")) {
            return namespace + name;
        } else {
            return namespace + "#" + name;
        }
    }

    /**
     * Returns true if resouce URI as string is defined in namespace
     *
     * @param resource  Resource URI as string
     * @param namespace Namespace
     * @return boolean
     */
    public static boolean isResourceDefinedInNamespace(String resource,
                                                       String namespace) {
        return guessNamespaceFromResourceURI(resource).equals(namespace);
    }

    /**
     * Tries to parse namespace from Resource uri by quessing last index of # or /
     *
     * @param resource resource uri as string
     * @return namespace as string
     */
    public static String guessNamespaceFromResourceURI(String resource) {
        if (resource.contains("#")) {
            return resource.substring(0, resource.lastIndexOf("#"));
        } else {
            return resource.substring(0, resource.lastIndexOf("/") + 1);
        }
    }

    /**
     * Removes invalid characters from resource names
     *
     * @param name resource name
     * @return stripped resource name
     */
    public static String removeInvalidCharacters(String name) {
        name = removeAccents(name);
        name = name.replaceAll("[^a-zA-Z0-9_-]", "");
        return name;
    }

    /**
     * Removes accents from string
     *
     * @param text input text
     * @return stripped text
     */
    public static String removeAccents(@Nonnull String text) {
        return Normalizer.normalize(text, Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    public static InputStream getDefaultGraphInputStream() {
        return LDHelper.class.getClassLoader().getResourceAsStream("defaultGraph.json");
    }

    public static InputStream getDefaultSchemes() {
        return LDHelper.class.getClassLoader().getResourceAsStream("defaultSchemes.json");
    }

    public static InputStream getDefaultCodeServers() {
        return LDHelper.class.getClassLoader().getResourceAsStream("defaultCodeServers.json");
    }

    public static InputStream getDefaultGroupsInputStream() {
        return LDHelper.class.getClassLoader().getResourceAsStream("defaultGroups.json");
    }

    public static Object getUserContext() {
        try {
            return JsonUtils.fromInputStream(LDHelper.class.getClassLoader().getResourceAsStream("userContext.json"));
        } catch (IOException ex) {
            logger.error("Cannot load export", ex);
            return null;
        }
    }

    public static Object getDescriptionContext() {
        try {
            return JsonUtils.fromInputStream(LDHelper.class.getClassLoader().getResourceAsStream("descriptionContext.json"));
        } catch (IOException ex) {
            logger.error("Cannot load description context", ex);
            return null;
        }
    }

    public static Object getGroupContext() {
        try {
            return JsonUtils.fromInputStream(LDHelper.class.getClassLoader().getResourceAsStream("groupContext.json"));
        } catch (IOException ex) {
            logger.error("Cannot load group context", ex);
            return null;
        }
    }

    public static Object getExportContext() {
        try {
            return JsonUtils.fromInputStream(LDHelper.class.getClassLoader().getResourceAsStream("export.json"));
        } catch (IOException ex) {
            logger.error("Cannot load export", ex);
            return null;
        }
    }

    public static Object getOPHContext() {
        try {
            return JsonUtils.fromInputStream(LDHelper.class.getClassLoader().getResourceAsStream("oph.json"));
        } catch (IOException ex) {
            logger.error("Cannot load context", ex);
            return null;
        }
    }

    /**
     * Expands sparql query with default namespaces
     *
     * @param query SPARQL query as string
     * @return expanded sparql query
     */
    public static String expandSparqlQuery(String query) {
        return expandSparqlQuery(query, LDHelper.PREFIX_MAP);
    }

    public static String expandSparqlQuery(boolean skip,
                                           String query) {
        if (skip) {
            return prefixQuery(query);
        }
        return expandSparqlQuery(query, LDHelper.PREFIX_MAP);
    }

    /**
     * Expands SPARQL query by removing prefixes. Useful cases when prefixes are mixed.
     *
     * @param query      SPARQL query to be expanded
     * @param prefix_map Prefix-map to be used in expand
     * @return SPARQL query as string
     */
    public static String expandSparqlQuery(String query,
                                           Map<String, String> prefix_map) {

        for (Map.Entry<String, String> namespaces : prefix_map.entrySet()) {
            StringBuffer sb = new StringBuffer();
            /* Find curies starting with whitespace */
            String prefiz = " " + namespaces.getKey().concat(":");
            String REGEX = prefiz + "\\w*\\b";
            Pattern p = Pattern.compile(REGEX);
            Matcher m = p.matcher(query);

            while (m.find()) {
                StringBuffer replacement = new StringBuffer();
                replacement.append(" <").append(m.group().replace(prefiz, namespaces.getValue())).append(">");
                m.appendReplacement(sb, replacement.toString());
            }

            m.appendTail(sb);
            query = sb.toString();

        }

        return query;
    }

    /**
     * Returns JENA model from JSONLD Response
     *
     * @param response Response object
     * @return Jena model parsed from Reponse entity or empty model
     */
    public static Model getJSONLDResponseAsJenaModel(Response response) {
        Model model = ModelFactory.createDefaultModel();

        try {
            RDFReader reader = model.getReader(Lang.JSONLD.getName());
            reader.read(model, (InputStream) response.getEntity(), "urn:yti:resource");
        } catch (RiotException ex) {
            logger.info(ex.getMessage());
            return model;
        }

        return model;
    }

    public static Model getJSONArrayResponseAsJenaModel(Response response,
                                                        Map<String, Object> contextObject) {
        Model model = ModelFactory.createDefaultModel();
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Object> jsonList = mapper.readValue((InputStream) response.getEntity(), new TypeReference<List<Object>>() {
            });
            Map<String, Object> rootObject = new HashMap<>();
            rootObject.put("@context", contextObject);
            rootObject.put("@graph", jsonList);
            String jsonLdString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootObject);
            InputStream targetStream = new ByteArrayInputStream(jsonLdString.getBytes());
            model = model.read(targetStream, "", "JSON-LD");
        } catch (IOException ex) {
            logger.info(ex.getMessage());
        }
        return model;
    }



}
