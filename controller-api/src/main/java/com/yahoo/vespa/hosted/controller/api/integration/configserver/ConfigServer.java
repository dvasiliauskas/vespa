// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.configserver;

import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.vespa.hosted.controller.api.application.v4.model.DeployOptions;
import com.yahoo.vespa.hosted.controller.api.application.v4.model.EndpointStatus;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.identifiers.Hostname;
import com.yahoo.vespa.serviceview.bindings.ApplicationView;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The API controllers use when communicating with config servers.
 *
 * @author Oyvind Grønnesby
 */
public interface ConfigServer {

    interface PreparedApplication {
        void activate();
        List<Log> messages();
        PrepareResponse prepareResponse();
    }

    // TODO: Deprecated, remove when implementations have been removed
    default PreparedApplication prepare(DeploymentId applicationInstance, DeployOptions deployOptions, Set<String> rotationCnames, Set<String> rotationNames, byte[] content) {
        return deploy(applicationInstance, deployOptions, rotationCnames, rotationNames, content);
    }

    PreparedApplication deploy(DeploymentId applicationInstance, DeployOptions deployOptions, Set<String> rotationCnames, Set<String> rotationNames, byte[] content);

    void restart(DeploymentId applicationInstance, Optional<Hostname> hostname) throws NoInstanceException;

    void deactivate(DeploymentId applicationInstance) throws NoInstanceException;

    ApplicationView getApplicationView(String tenantName, String applicationName, String instanceName, String environment, String region);

    Map<?,?> getServiceApiResponse(String tenantName, String applicationName, String instanceName, String environment, String region, String serviceName, String restPath);

    HttpResponse getLogs();
    /**
     * Set new status on en endpoint in one zone.
     *
     * @param deployment The application/zone pair
     * @param endpoint The endpoint to modify
     * @param status The new status with metadata
     * @throws IOException If trouble contacting the server
     */
    void setGlobalRotationStatus(DeploymentId deployment, String endpoint, EndpointStatus status) throws IOException;

    /**
     * Get the endpoint status for an app in one zone
     *
     * @param deployment The application/zone pair
     * @param endpoint The endpoint to modify
     * @return The endpoint status with metadata
     * @throws IOException If trouble contacting the server
     */
    EndpointStatus getGlobalRotationStatus(DeploymentId deployment, String endpoint) throws IOException;

    /** The node repository on this config server */
    NodeRepository nodeRepository();

    /** Get service convergence status for given deployment */
    Optional<ServiceConvergence> serviceConvergence(DeploymentId deployment);

}
