package fi.vm.yti.datamodel.api;
import fi.vm.yti.datamodel.api.service.EndpointServices;
import fi.vm.yti.datamodel.api.service.FrameManager;
import fi.vm.yti.datamodel.api.service.GraphManager;
import fi.vm.yti.datamodel.api.service.NamespaceManager;
import fi.vm.yti.datamodel.api.service.RHPOrganizationManager;
import fi.vm.yti.datamodel.api.service.TermedTerminologyManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;

@Component
public class StartUpListener  {

    private static final Logger logger = LoggerFactory.getLogger(StartUpListener.class.getName());

    private final TermedTerminologyManager termedTerminologyManager;
    private final RHPOrganizationManager rhpOrganizationManager;
    private final GraphManager graphManager;
    private final NamespaceManager namespaceManager;
    private final FrameManager frameManager;

    @Autowired
    StartUpListener(TermedTerminologyManager termedTerminologyManager,
                    RHPOrganizationManager rhpOrganizationManager,
                    GraphManager graphManager,
                    NamespaceManager namespaceManager,
                    FrameManager frameManager) {

        this.termedTerminologyManager = termedTerminologyManager;
        this.rhpOrganizationManager = rhpOrganizationManager;
        this.graphManager = graphManager;
        this.namespaceManager = namespaceManager;
        this.frameManager = frameManager;
    }

    @PostConstruct
    public void contextInitialized() {

        logger.info( "System is starting ...");

        initDefaultGraph();
        initDefaultNamespaces();
        //initCodeServers();
        initServiceCategories();
        termedTerminologyManager.initConceptsFromTermed();
        initRHPOrganizations();
        initFramingCache();
    }

    @PreDestroy
    public void contextDestroyed() {
        logger.info( "System is closing ...");
    }

    @Scheduled(cron = "0 */5 * * * *")
    void initRHPOrganizations() {
        logger.info("Updating organizations");
        rhpOrganizationManager.initOrganizationsFromRHP();
    }

    private void initServiceCategories() {
        graphManager.initServiceCategories();
    }

    private void initCodeServers() {
        // TODO: Codelists are now updated with each CodeList API query
/*
        OPHCodeServer codeServer = new OPHCodeServer("https://virkailija.opintopolku.fi/koodisto-service/rest/json/", endpointServices);
        codeServer.updateCodelistsFromServer();

        SuomiCodeServer codeServer2 = new SuomiCodeServer("https://koodistot.suomi.fi","https://koodistot-dev.suomi.fi/codelist-api/api/v1/", endpointServices);
        codeServer2.updateCodelistsFromServer();
*/
    }

    private void initDefaultGraph() {
        if (graphManager.testDefaultGraph()) {
            logger.info("Default graph is initialized!");
        }
        else {
            logger.warn("Default graph is NOT initialized!");
            graphManager.createDefaultGraph();
            if (graphManager.testDefaultGraph())
                logger.info("Created NEW DEFAULT graph!");
            else
                logger.warn("Failed to create default graph!");
        }
    }

    private void initDefaultNamespaces() {
        namespaceManager.resolveDefaultNamespaceToTheCore();
    }
    
    private void initFramingCache() {
        frameManager.initCache();
    }
}
