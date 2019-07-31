package fi.vm.yti.datamodel.api.service;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

import javax.jws.WebParam;
import javax.xml.parsers.SAXParserFactory;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.XSDPlainType;
import org.apache.jena.ext.xerces.DatatypeFactoryInst;
import org.apache.jena.ext.xerces.jaxp.datatype.DatatypeFactoryImpl;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.impl.AdhocDatatype;
import org.apache.jena.graph.impl.LiteralLabelFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.NodeIterator;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.topbraid.shacl.vocabulary.SH;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.sun.xml.xsom.XSAnnotation;
import com.sun.xml.xsom.XSAttributeDecl;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSContentType;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSModelGroup;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSRestrictionSimpleType;
import com.sun.xml.xsom.XSSchema;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSTerm;
import com.sun.xml.xsom.XSType;
import com.sun.xml.xsom.parser.AnnotationParserFactory;
import com.sun.xml.xsom.parser.XSOMParser;

import fi.vm.yti.datamodel.api.utils.LDHelper;
import fi.vm.yti.datamodel.api.utils.XSDAnnotationFactory;

@Service
public class XSDParser {
    private static final Logger logger = LoggerFactory.getLogger(XSDParser.class.getName());
    private Dataset dataset;
    private Model hasPartGraph;
    private Model modelGraph;
    private XSOMParser parser;
    private XSSchemaSet xsdModel;
    private String namespace;
    private String language;

    XSDParser() {}

    XSDParser(String namespace, String language) {
        init(namespace,language);
    }

    public void init(String namespace, String language) {
        this.dataset = DatasetFactory.createGeneral();
        this.hasPartGraph = ModelFactory.createDefaultModel();
        this.modelGraph = ModelFactory.createDefaultModel();
        this.dataset.addNamedModel(namespace.replaceFirst("#",""), this.modelGraph);
        this.dataset.addNamedModel(namespace+"HasPartGraph",this.hasPartGraph);
        this.parser = new XSOMParser(SAXParserFactory.newInstance());
        parser.setAnnotationParser(new XSDAnnotationFactory());
        this.namespace = namespace;
        this.language = language;
    }

    public void parseXSDFromFile(File file) {
        try {

            if(!file.exists()) {
                throw new FileNotFoundException(file.getAbsolutePath());
            }

            parser.parse(file);
            this.xsdModel = parser.getResult();

        } catch(SAXException | IOException ex) {
            ex.printStackTrace();
            logger.error(ex.getMessage());
        }
    }

    public void parseXSD(InputStream is) {
        try {
            parser.parse(is);
            this.xsdModel = parser.getResult();
        } catch (SAXException ex) {
            ex.printStackTrace();
            logger.error(ex.getMessage());
        }
    }

    public void parseXSDFromURL(URL url) {
        try {
        InputSource is = new InputSource(url.openStream());
        is.setSystemId(url.toExternalForm());
        parser.parse(is);
        this.xsdModel = parser.getResult();
        } catch (SAXException | IOException e) {
            e.printStackTrace();
        }
    }

    public void convertCoreLibrary() {

    }

    public void convert() {

        Collection<XSSchema> schemas = this.xsdModel.getSchemas();

        for(XSSchema schema : schemas) {

            String targetNamespace = schema.getTargetNamespace();
            logger.debug("Parsing "+targetNamespace);

            // Ignore XSD namespace
            if(targetNamespace.isEmpty() || !targetNamespace.isEmpty() && !XSD.getURI().startsWith(targetNamespace)) {

                /*
                Iterator<XSSimpleType> simpleTypes = schema.iterateSimpleTypes();

                while (simpleTypes.hasNext()) {
                    convertSimpleType(simpleTypes.next());
                }

                Iterator<XSComplexType> complexTypes = schema.iterateComplexTypes();

                while (complexTypes.hasNext()) {
                    convertComplexType(complexTypes.next());
                } */

                Iterator<XSElementDecl> elements = schema.iterateElementDecls();

                while(elements.hasNext()) {
                    convertElement(elements.next());
                }

            }
        }
    }

    private void convertSimpleType(XSSimpleType simpleType, Resource propertyShape) {

        String targetNamespace = simpleType.getTargetNamespace();
        String name = simpleType.getName();
        XSSimpleType baseType = simpleType.getSimpleBaseType();
        propertyShape.addProperty(SH.datatype, baseTypeToResource(baseType.getName()));
        logger.debug("TYPE: "+name+ " : "+baseType.getName() + " from "+targetNamespace+" "+(simpleType.isGlobal() ? "Global" : "Local"));
        XSAnnotation anno = simpleType.getAnnotation();

        if (anno != null && anno.getAnnotation() != null) {
            String annoString = anno.getAnnotation().toString();
            propertyShape.addProperty(SH.description, ResourceFactory.createLangLiteral(annoString,this.language));
        }

            if(simpleType.isPrimitive()) {
                logger.debug(name+" is Primitive!");
            } else if(simpleType.isRestriction()) {
                logger.debug(name+" is Restriction!");
             XSRestrictionSimpleType restriction = simpleType.asRestriction();
             Iterator<XSFacet> enums = restriction.iterateDeclaredFacets();
             while(enums.hasNext()) {
                 XSFacet facet = enums.next();
                 String facetName = facet.getName();
                 switch (facetName) {
                     case "minLength":
                         propertyShape.addLiteral(SH.minLength, ResourceFactory.createTypedLiteral(facet.getValue().value,XSDDatatype.XSDinteger));
                         break;
                     case "maxLength":
                         propertyShape.addLiteral(SH.maxLength, ResourceFactory.createTypedLiteral(facet.getValue().value,XSDDatatype.XSDinteger));
                         break;
                     case "pattern":
                         propertyShape.addLiteral(SH.pattern, facet.getValue().value);
                         break;
                     case "enumeration":
                         propertyShape.addLiteral(SH.in, facet.getValue().value);
                         break;
                     default: logger.warn(facetName+" NOT IMPLEMENTED!");
                 }

             }
            } else if(simpleType.isUnion()) {
                logger.warn("Union not implemented!");
                //simpleType.asUnion();
            }

    }

    private Resource baseTypeToResource(String type) {
        switch (type) {
            case "anyURI":
                return XSD.anyURI;
            case "base64Binary":
                return XSD.base64Binary;
            case "date":
                return XSD.date;
            case "dateTime":
                return XSD.dateTime;
            case "decimal":
                return XSD.decimal;
            case "duration":
                return XSD.xstring;
            case "gDay":
                return XSD.gDay;
            case "gMonth":
                return XSD.gMonth;
            case "gMonthDay":
                return XSD.gMonthDay;
            case "gYear":
                return XSD.gYear;
            case "gYearMonth":
                return XSD.gYearMonth;
            case "hexBinary":
                return XSD.hexBinary;
            case "integer":
                return XSD.integer;
            case "negativeInteger":
                return XSD.integer;
            case "nonNegativeInteger":
                return XSD.integer;
            case "nonPositiveInteger":
                return XSD.integer;
            case "normalizedString":
                return XSD.xstring;
            case "positiveInteger":
                return XSD.integer;
            case "time":
                return XSD.time;
            case "boolean":
                return XSD.xboolean;
            case "double":
                return XSD.xdouble;
            case "float":
                return XSD.xfloat;
            case "int":
                return XSD.integer;
            case "long":
                return XSD.xlong;
            case "short":
                return XSD.xshort;
            default:
                return XSD.xstring;
        }
    }

    private void convertComplexType(XSComplexType complexType, Resource shape) {

        String targetNamespace = complexType.getTargetNamespace();
        String name = complexType.getName();

        if(complexType.isGlobal()) {
            logger.debug(name+" is Global!");
            if (targetNamespace.equals(XSD.getURI())) {
                logger.debug("Skipping XSD type!");
            } else {
                // shape = model.createResource(this.namespace + name, SH.NodeShape);
            }
        } else if(complexType.isLocal()) {
            logger.debug("Local ComplexType!");
            //shape = model.createResource(this.namespace + name, SH.NodeShape);
        } else {
            logger.debug(name+ " is something else!");
        }

        XSType baseType = complexType.getBaseType();
        logger.debug("BaseType: "+baseType.getName()+" from "+baseType.getOwnerSchema().getTargetNamespace());

        Iterator<? extends XSAttributeUse> attributeUses = complexType.getAttributeUses().iterator();
        int order = 0;
        while (attributeUses.hasNext()) {
            XSAttributeUse attributeUse = attributeUses.next();
            XSAttributeDecl attribute = attributeUse.getDecl();
            Resource propertyShape = shape.getModel().createResource(LDHelper.randomURNUUID(), SH.PropertyShape);
            String attributeName = attribute.getName();
            String localName = LDHelper.propertyName(attributeName);
            logger.debug("Attribute: "+attributeName);
            String predicateURI = this.namespace+localName;
            String prefName = LDHelper.preferredName(attributeName);
            propertyShape.addProperty(SH.predicate,ResourceFactory.createResource(predicateURI));

            if(this.dataset.containsNamedModel(predicateURI)) {
                logger.debug("EXISTS DatatypeProperty: "+predicateURI);
            } else {
                Resource datatypeProperty = createResource(this.namespace, localName, OWL.DatatypeProperty);
                datatypeProperty.addProperty(RDFS.range,baseTypeToResource(attribute.getType().getBaseType().getName()));
            }

            if(attributeUse.isRequired()) {
                propertyShape.addLiteral(SH.minCount, ResourceFactory.createTypedLiteral("1",XSDDatatype.XSDinteger));
            }
            propertyShape.addLiteral(SH.maxCount, ResourceFactory.createTypedLiteral("2",XSDDatatype.XSDinteger));
            propertyShape.addLiteral(SH.name,ResourceFactory.createLangLiteral(prefName,this.language));
            propertyShape.addLiteral(LDHelper.curieToProperty("iow:isXmlAttribute"),true);
            propertyShape.addLiteral(LDHelper.curieToProperty("iow:localName"),localName);
            propertyShape.addLiteral(SH.order, ResourceFactory.createTypedLiteral(String.valueOf(order),XSDDatatype.XSDinteger));
            shape.addProperty(SH.property,propertyShape);
            order+=1;
        }

        XSParticle particle = complexType.getContentType().asParticle();

        if(particle!=null) {
            logger.debug("Particle!");
            XSTerm term = particle.getTerm();
            if(term.isModelGroup()) {
                logger.debug("Model Group!");
                convertModelGroup(term.asModelGroup(),shape, order);
            } else if(term.isModelGroupDecl()) {
                logger.debug("Model Group Declaration!");
            } else if(term.isElementDecl()) {
                logger.debug("Element Declaration!");
            }
        }

        XSContentType complexContent = complexType.getExplicitContent();

    }

    private void convertModelGroup(XSModelGroup modelGroup, Resource shape, int order) {
        Iterator<XSParticle> childIterator = modelGroup.iterator();
        while(childIterator.hasNext()) {
            XSParticle particle = childIterator.next();
            int maxOccurs = particle.getMaxOccurs().intValue();
            int minOccurs = particle.getMinOccurs().intValue();
            XSTerm term = particle.getTerm();
            if(term.isElementDecl()) {
                XSElementDecl element = term.asElementDecl();
                XSType type = element.getType();
                String name = element.getName();
                String localName = LDHelper.propertyName(name);
                String prefName = LDHelper.preferredName(name);
                logger.info(type+": "+name+" ["+minOccurs+","+maxOccurs+"]");

                Resource propertyShape = shape.getModel().createResource(LDHelper.randomURNUUID(), SH.PropertyShape);
                String predicateURI = this.namespace+localName;

                propertyShape.addProperty(SH.predicate,ResourceFactory.createResource(predicateURI));
                propertyShape.addLiteral(SH.minCount, ResourceFactory.createTypedLiteral(String.valueOf(minOccurs),XSDDatatype.XSDinteger));
                if(maxOccurs>=0) {
                    propertyShape.addLiteral(SH.maxCount, ResourceFactory.createTypedLiteral(String.valueOf(maxOccurs),XSDDatatype.XSDinteger));
                }
                propertyShape.addLiteral(LDHelper.curieToProperty("iow:localName"),localName);
                propertyShape.addLiteral(SH.name,ResourceFactory.createLangLiteral(prefName,this.language));
                propertyShape.addLiteral(SH.order, ResourceFactory.createTypedLiteral(String.valueOf(order),XSDDatatype.XSDinteger));
                shape.addProperty(SH.property,propertyShape);

                if(type.isComplexType()) {

                    // TODO: Handle simpleContent with restictions
                    if(type.getBaseType()!=null) {
                        XSType baseType = type.getBaseType();
                        if(baseType.asSimpleType()!=null) {
                            logger.info("Is restriction: " + baseType.asSimpleType().isRestriction());
                        }
                    }

                    String subLocalName = LDHelper.resourceName(name);
                    String resourceURI = this.namespace+subLocalName;

                    boolean newResource = true;

                    if(this.dataset.containsNamedModel(resourceURI)) {
                        logger.info("EXISTS: "+resourceURI);
                        newResource = false;
                    }

                    Resource subResource = createResource(this.namespace, subLocalName,RDFS.Resource);
                    propertyShape.addProperty(SH.target,subResource);

                    if(this.dataset.containsNamedModel(predicateURI)) {
                        logger.debug("EXISTS ObjectProperty: "+predicateURI);
                    } else {
                        Resource objectProperty = createResource(this.namespace, LDHelper.propertyName(name), OWL.ObjectProperty);
                        objectProperty.addProperty(RDFS.range, subResource);
                        objectProperty.addLiteral(RDFS.label, ResourceFactory.createLangLiteral(prefName,this.language));
                    }

                    if(newResource) {
                        convertComplexType(type.asComplexType(), subResource);
                    }
                } else if(type.isSimpleType()) {
                    if(this.dataset.containsNamedModel(predicateURI)) {
                        logger.debug("EXISTS DatatypeProperty: "+predicateURI);
                    } else {
                        Resource datatypeProperty = createResource(this.namespace, localName, OWL.DatatypeProperty);
                        datatypeProperty.addProperty(RDFS.range,baseTypeToResource(type.getBaseType().getName()));
                        datatypeProperty.addLiteral(RDFS.label, ResourceFactory.createLangLiteral(prefName,this.language));
                    }
                    convertSimpleType(type.asSimpleType(), propertyShape);
                }
            }
            order+=1;
        }
    }

    private void convertElement(XSElementDecl element) {
        String targetNamespace = element.getTargetNamespace();
        String name = element.getName();
        String localName = LDHelper.resourceName(name);
        String prefName = LDHelper.preferredName(name);
        XSType elementType = element.getType();

        Resource shape = createResource(this.namespace, localName, RDFS.Resource);
        shape.addLiteral(SH.name,ResourceFactory.createLangLiteral(prefName,this.language));

        logger.debug("Converting "+name+" from "+targetNamespace);
        logger.debug(name+ " is "+elementType+ " Element");

        if(element.isGlobal()) {
            logger.debug(name+" is Global!");
            if(elementType.isSimpleType()) {
                logger.warn("Simple elements not supported as ROOT!");
               // convertSimpleType(elementType.asSimpleType());
            } else if(elementType.isComplexType()) {
                convertComplexType(elementType.asComplexType(),shape);
            }
        } else if(element.isLocal()) {
            logger.debug(name+" is Local!");
        } else {
            logger.debug(name+ " is something else!");
        }
    }

    private Resource createResource(String namespace, String localName, Resource type) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefixes(LDHelper.PREFIX_MAP);
        String resourceUri = namespace+localName;
        this.dataset.addNamedModel(resourceUri,model);
        Resource res = model.createResource(resourceUri, type);
        this.hasPartGraph.add(ResourceFactory.createResource(namespace), DCTerms.hasPart, ResourceFactory.createResource(resourceUri));
        return res;
    }


    public void debug() {
        NodeIterator nit = this.hasPartGraph.listObjectsOfProperty(DCTerms.hasPart);
        while(nit.hasNext()) {
            logger.debug(nit.next().asResource().getURI());
        }

        Iterator<String> it = this.dataset.listNames();
        logger.debug("Empty: "+this.dataset.isEmpty());
        /*
        logger.debug("GRAPHS:");
        while(it.hasNext()) {
            String graph = it.next();
            this.dataset.getNamedModel(graph).write(System.out,"TURTLE");
        }*/
    }


    public Dataset getGeneratedDataset() {
        return this.dataset;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(final String namespace) {
        this.namespace = namespace;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(final String language) {
        this.language = language;
    }
}
