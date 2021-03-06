package fi.vm.yti.datamodel.api.index.model;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public class IntegrationContainerRequest {

    private Set<String> uri;
    private String searchTerm;
    private String language;
    private Set<String> status;
    private String type;
    private Date after;
    private Date before;
    private Set<String> filter;
    private Integer pageSize;
    private Integer pageFrom;
    private Boolean includeIncomplete;
    private Set<String> includeIncompleteFrom;

    public IntegrationContainerRequest() {
    }

    public IntegrationContainerRequest(final Set<String> uri,
                                       final String searchTerm,
                                       final String language,
                                       final Set<String> status,
                                       final String type,
                                       final Date after,
                                       final Date before,
                                       final Set<String> filter,
                                       final Integer pageSize,
                                       final Integer pageFrom,
                                       final Boolean includeIncomplete,
                                       final Set<String> includeIncompleteFrom) {
        this.uri = uri;
        this.searchTerm = searchTerm;
        this.language = language;
        this.status = status;
        this.type = type;
        this.after = after;
        this.before = before;
        this.filter = filter;
        this.pageSize = pageSize;
        this.pageFrom = pageFrom;
        this.includeIncomplete = includeIncomplete;
        this.includeIncompleteFrom = includeIncompleteFrom;
    }

    public Set<String> getUri() {
        return uri;
    }

    public void setUri(final Set<String> uri) {
        this.uri = uri;
    }

    public String getSearchTerm() {
        return searchTerm;
    }

    public void setSearchTerm(final String searchTerm) {
        this.searchTerm = searchTerm;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(final String language) {
        this.language = language;
    }

    public Set<String> getStatus() {
        return status;
    }

    public void setStatus(final Set<String> status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public Date getAfter() {
        return after;
    }

    public void setAfter(final Date after) {
        this.after = after;
    }

    public Date getBefore() {
        return before;
    }

    public void setBefore(final Date before) {
        this.before = before;
    }

    public Set<String> getFilter() {
        return filter;
    }

    public void setFilter(final Set<String> filter) {
        this.filter = filter;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(final Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Integer getPageFrom() {
        return pageFrom;
    }

    public void setPageFrom(final Integer pageFrom) {
        this.pageFrom = pageFrom;
    }

    public Set<String> getIncludeIncompleteFrom() {
        return includeIncompleteFrom;
    }

    public void setIncludeIncompleteFrom(final Set<String> includeIncompleteFrom) {
        this.includeIncompleteFrom = includeIncompleteFrom;
    }

    public void setIncludeIncompleteEmpty() {
        this.includeIncompleteFrom = new HashSet<>();
    }

    public Boolean getIncludeIncomplete() {
        return includeIncomplete;
    }

    public void setIncludeIncomplete(final Boolean includeIncomplete) {
        this.includeIncomplete = includeIncomplete;
    }

    @Override
    public String toString() {
        return "IntegrationContainerRequest{" +
            "searchTerm='" + searchTerm + '\'' +
            ", language='" + language + '\'' +
            ", status=" + status +
            ", type='" + type + '\'' +
            ", after=" + after +
            ", before=" + before +
            ", filter=" + filter +
            ", pageSize=" + pageSize +
            ", pageFrom=" + pageFrom +
            ", includeIncomplete=" + includeIncomplete +
            ", includeIncompleteFrom=" + includeIncompleteFrom +
            '}';
    }
}
