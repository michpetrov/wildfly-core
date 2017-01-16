/*
 * Copyright 2017 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.server.deploymentoverlay;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADDRESS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT_OVERLAY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REDEPLOY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REDEPLOY_LINKS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNTIME_NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBDEPLOYMENT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.operations.DomainOperationTransformer;
import org.jboss.as.controller.operations.OperationAttachments;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class AffectedDeploymentOverlay {

    public static Set<String> listAllLinks(OperationContext context, String overlay) {
        Set<String> serverGoupNames = listServerGroupsReferencingOverlay(context, overlay);
        Set<String> links = new HashSet<>();
        for(String serverGoupName : serverGoupNames) {
            links.addAll(listLinks(context, PathAddress.pathAddress(
                    PathElement.pathElement(SERVER_GROUP, serverGoupName),
                    PathElement.pathElement(DEPLOYMENT_OVERLAY, overlay))));
        }
        return links;
    }

    public static Set<String> listLinks(OperationContext context, PathAddress deploymentsRootAddress) {
        Resource overlayResource = context.readResourceFromRoot(deploymentsRootAddress);
        if (overlayResource.hasChildren(DEPLOYMENT)) {
            return overlayResource.getChildrenNames(DEPLOYMENT);
        }
        return Collections.emptySet();
    }

    public static void redeployLinks(OperationContext context, PathAddress deploymentsRootAddress, Set<String> runtimeNames) throws OperationFailedException {
        Set<String> deploymentNames = listDeploymentNames(context, deploymentsRootAddress, runtimeNames.stream().map(wildcardExpr -> Pattern.compile(wildcardToJavaRegex(wildcardExpr))).collect(Collectors.toSet()));
        for (String deploymentName : deploymentNames) {
            PathAddress address = deploymentsRootAddress.append(DEPLOYMENT, deploymentName);
            OperationStepHandler handler = context.getRootResourceRegistration().getOperationHandler(address, REDEPLOY);
            ModelNode operation = addRedeployStep(address);
            assert handler != null;
            assert operation.isDefined();
            context.addStep(operation, handler, OperationContext.Stage.MODEL);
        }
    }

    public static void redeployLinksAndTransformOperationForDomain(OperationContext context, Set<String> serverGroupNames, Set<String> runtimeNames) throws OperationFailedException {
        Set<String> deploymentNames = new HashSet<>();
        for (String serverGoupName : serverGroupNames) {
            deploymentNames.addAll(listDeploymentNames(context, PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, serverGoupName)), runtimeNames.stream().map(wildcardExpr -> Pattern.compile(wildcardToJavaRegex(wildcardExpr))).collect(Collectors.toSet())));
        }
        //Remote part
        Operations.CompositeOperationBuilder opBuilder = Operations.CompositeOperationBuilder.create();
        deploymentNames.forEach((deploymentName) -> opBuilder.addStep(addRedeployStep(context.getCurrentAddress().getParent().append(DEPLOYMENT, deploymentName))));
        // Add the domain op transformer
        List<DomainOperationTransformer> transformers = context.getAttachment(OperationAttachments.SLAVE_SERVER_OPERATION_TRANSFORMERS);
        if (transformers == null) {
            context.attach(OperationAttachments.SLAVE_SERVER_OPERATION_TRANSFORMERS, transformers = new ArrayList<>());
        }
        final ModelNode slave = opBuilder.build().getOperation();
        transformers.add(new OverlayOperationTransformer(slave));
    }

    public static void redeployLinksAndTransformOperationForServerGroup(OperationContext context, PathAddress deploymentsRootAddress, Set<String> runtimeNames) throws OperationFailedException {
        Set<String> deploymentNames = listDeploymentNames(context, deploymentsRootAddress, runtimeNames.stream().map(wildcardExpr -> Pattern.compile(wildcardToJavaRegex(wildcardExpr))).collect(Collectors.toSet()));
        Operations.CompositeOperationBuilder opBuilder = Operations.CompositeOperationBuilder.create();
        for (String deploymentName : deploymentNames) {
            opBuilder.addStep(addRedeployStep(context.getCurrentAddress().getParent().append(DEPLOYMENT, deploymentName)));
        }
        // Add the domain op transformer
        List<DomainOperationTransformer> transformers = context.getAttachment(OperationAttachments.SLAVE_SERVER_OPERATION_TRANSFORMERS);
        if (transformers == null) {
            context.attach(OperationAttachments.SLAVE_SERVER_OPERATION_TRANSFORMERS, transformers = new ArrayList<>());
        }
        final ModelNode slave = opBuilder.build().getOperation();
        transformers.add(new OverlayOperationTransformer(slave));
    }

    /**
     * Returns the deployment names with the specified runtime names.
     *
     * @param context
     * @param runtimeNames
     * @return
     */
    public static Set<String> listDeploymentNames(OperationContext context, PathAddress deploymentRootAddress, Set<Pattern> patterns) {
        Set<String> deploymentNames = new HashSet<>();
        Resource deploymentRootResource = context.readResourceFromRoot(deploymentRootAddress);
        if (deploymentRootResource.hasChildren(DEPLOYMENT)) {
            for (Resource.ResourceEntry deploymentResource : deploymentRootResource.getChildren(DEPLOYMENT)) {
                if (isAcceptableDeployment(deploymentResource.getModel(), patterns)) {
                    deploymentNames.add(deploymentResource.getName());
                } else if (deploymentResource.hasChildren(SUBDEPLOYMENT)) {
                    for (Resource.ResourceEntry subdeploymentResource : deploymentResource.getChildren(SUBDEPLOYMENT)) {
                        if (isAcceptableDeployment(subdeploymentResource.getModel(), patterns)) {
                            deploymentNames.add(deploymentResource.getName());
                        }
                    }
                }
            }
        }
        return deploymentNames;
    }

    private static boolean isAcceptableDeployment(ModelNode deploymentNode, Set<Pattern> patterns) {
        return deploymentNode.get(ENABLED).asBoolean()
                && patterns.stream().anyMatch(pattern -> pattern.matcher(deploymentNode.require(RUNTIME_NAME).asString()).matches());
    }

    public static ModelNode addRedeployStep(PathAddress address) {
        final ModelNode redeployOperation = new ModelNode();
        redeployOperation.get(OP).set(REDEPLOY);
        redeployOperation.get(OP_ADDR).set(address.toModelNode());
        return redeployOperation;
    }

    public static Set<String> listServerGroupsReferencingOverlay(OperationContext context, String overlayName) {
        final Resource rootResource = context.readResourceFromRoot(PathAddress.EMPTY_ADDRESS);
        final PathElement overlayPath = PathElement.pathElement(DEPLOYMENT_OVERLAY, overlayName);
        if (rootResource.hasChildren(SERVER_GROUP)) {
            return rootResource.getChildrenNames(SERVER_GROUP).stream().filter(
                    serverGroupName -> rootResource.getChild(PathElement.pathElement(SERVER_GROUP, serverGroupName)).hasChild(overlayPath)).collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }


    public static String wildcardToJavaRegex(String expr) {
        if (expr == null) {
            throw new IllegalArgumentException("expr is null");
        }
        String regex = expr.replaceAll("([(){}\\[\\].+^$])", "\\\\$1"); // escape regex characters
        regex = regex.replaceAll("\\*", ".*"); // replace * with .*
        regex = regex.replaceAll("\\?", "."); // replace ? with .
        return regex;
    }

    private static final class OverlayOperationTransformer implements DomainOperationTransformer {
        private final ModelNode newOperation;

        public OverlayOperationTransformer(ModelNode newOperation) {
            this.newOperation = newOperation;
        }

        @Override
        public ModelNode transform(final OperationContext context, final ModelNode operation) {
            if (operation.get(OP).asString().equals(COMPOSITE)) {
                ModelNode ret = operation.clone();
                final List<ModelNode> list = new ArrayList<ModelNode>();
                ListIterator<ModelNode> it = ret.get(STEPS).asList().listIterator();
                while (it.hasNext()) {
                    final ModelNode subOperation = it.next();
                    list.add(transform(context, subOperation));
                }
                ret.get(STEPS).set(list);
                return ret;
            } else {
                if (matches(operation)) {
                    return newOperation.clone();
                } else {
                    return operation;
                }
            }
        }

        protected boolean matches(final ModelNode operation) {
            return REDEPLOY_LINKS.equals(operation.get(OP).asString())
                    && operation.get(ADDRESS).asList().size() > 1;
        }
    }
}
