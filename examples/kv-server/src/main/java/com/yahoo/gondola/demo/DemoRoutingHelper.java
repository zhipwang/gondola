/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola.demo;

import com.yahoo.gondola.Cluster;
import com.yahoo.gondola.Config;
import com.yahoo.gondola.Gondola;
import com.yahoo.gondola.container.spi.RoutingHelper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.ws.rs.container.ContainerRequestContext;

/**
 * The callback that inspect the request and return a cluster ID for RoutingFilter.
 */
public class DemoRoutingHelper implements RoutingHelper {

    Gondola gondola;
    int numberOfClusters;
    List<String> clusterIdList;
    DemoService demoService;

    public DemoRoutingHelper(Gondola gondola, DemoService demoService) {
        this.gondola = gondola;
        this.demoService = demoService;
        loadNumberOfClusters(gondola);
        loadClusterIdList(gondola);
    }

    @Override
    public int getBucketId(ContainerRequestContext request) {
        int hashValue = hashUri(request.getUriInfo().getPath());
        return hashValue % numberOfClusters;
    }

    @Override
    public int getAppliedIndex(String clusterId) {
        return demoService.getAppliedIndex();
    }

    @Override
    public String getSiteId(ContainerRequestContext request) {
        return gondola.getConfig().getAttributesForHost(gondola.getHostId()).get("siteId");
    }

    @Override
    public void clearState(String clusterId) {
        demoService.clearState();
    }

    private void loadNumberOfClusters(Gondola gondola) {
        Config config = gondola.getConfig();

        Set<String> clusterIds = new HashSet<>();
        for (String hostId : config.getHostIds()) {
            clusterIds.addAll(config.getClusterIds(hostId));
        }
        numberOfClusters = clusterIds.size();
    }

    private void loadClusterIdList(Gondola gondola) {
        clusterIdList = gondola.getClustersOnHost().stream()
                          .map(Cluster::getClusterId)
                          .collect(Collectors.toList());
    }

    /**
     * The URI will be relative to servlet.
     * e.g: entries/key1?foo=123
     */
    private int hashUri(String path) {
        Pattern pattern = Pattern.compile("entries/([^/?]+)");
        Matcher m = pattern.matcher(path);
        return m.find() ? m.group(0).hashCode() : 0;
    }
}