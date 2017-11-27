package fi.vm.yti.datamodel.api.config;

import fi.vm.yti.datamodel.api.model.Role;
import fi.vm.yti.datamodel.api.model.YtiUser;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;

import javax.net.ssl.SSLContext;
import javax.servlet.http.HttpSession;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class AuthenticationHandler {

    private static final Logger logger = Logger.getLogger(AuthenticationHandler.class.getName());

    private static final String AUTHENTICATED_USER_ATTRIBUTE = "authenticatedUser";

    static void initializeUser(HttpSession session, ShibbolethAuthenticationDetails authenticationDetails) {

        if (authenticationDetails.isAuthenticated()) {
            // No need to fetch user every time if session is already authenticated
            if (!isAuthenticatedSession(session)) {
                setUser(session, getAuthenticatedUser(authenticationDetails));
            }
        } else {
            setUser(session, YtiUser.ANONYMOUS_USER);
        }
    }

    static YtiUser getUser(HttpSession session) {
        return (YtiUser) session.getAttribute(AUTHENTICATED_USER_ATTRIBUTE);
    }

    private static void setUser(HttpSession session, YtiUser authenticatedUser) {
        session.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, authenticatedUser);
    }

    private static YtiUser getAuthenticatedUser(ShibbolethAuthenticationDetails authenticationDetails) {

        String url = ApplicationProperties.getDefaultGroupManagementAPI() + "user";

        logger.info("Fetching user from URL: " + url);

        Response response = ClientBuilder.newBuilder()
                .sslContext(naiveSSLContext())
                .build().target(url)
                .queryParam("email", authenticationDetails.getEmail())
                .queryParam("firstName", authenticationDetails.getFirstName())
                .queryParam("lastName", authenticationDetails.getLastName())
                .request(MediaType.APPLICATION_JSON)
                .get();

        User user = response.readEntity(User.class);

        Map<UUID, Set<Role>> rolesInOrganizations = new HashMap<>();

        for (Organization organization : user.organization) {

            Set<Role> roles = organization.role.stream()
                    .filter(AuthenticationHandler::isRoleMappableToEnum)
                    .map(Role::valueOf)
                    .collect(Collectors.toSet());

            rolesInOrganizations.put(organization.uuid, roles);
        }

        YtiUser ytiUser = new YtiUser(user.email, user.firstName, user.lastName, user.superuser, user.newlyCreated, rolesInOrganizations);

        logger.info("User fetched: " + ytiUser);

        return ytiUser;
    }

    private static boolean isRoleMappableToEnum(String roleString) {

        boolean contains = Role.contains(roleString);

        if (!contains) {
            logger.warning("Cannot map role (" + roleString + ")" + " to role enum");
        }

        return contains;
    }

    private static boolean isAuthenticatedSession(HttpSession session) {
        YtiUser user = getUser(session);
        return user != null && !user.isAnonymous();
    }

    private static SSLContext naiveSSLContext() {

        TrustStrategy naivelyAcceptingTrustStrategy = (X509Certificate[] chain, String authType) -> true;

        try {
            return SSLContexts.custom()
                    .loadTrustMaterial(null, naivelyAcceptingTrustStrategy)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    private static class User {

        public String email;
        public String firstName;
        public String lastName;
        public boolean superuser;
        public boolean newlyCreated;
        public List<Organization> organization;
    }

    private static class Organization {

        public UUID uuid;
        public List<String> role;
    }
}