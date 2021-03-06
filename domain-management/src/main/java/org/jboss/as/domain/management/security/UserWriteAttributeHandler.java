/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.management.security;

import static org.jboss.as.controller.security.CredentialReference.applyCredentialReferenceUpdateToRuntime;
import static org.jboss.as.controller.security.CredentialReference.handleCredentialReferenceUpdate;
import static org.jboss.as.controller.security.CredentialReference.rollbackCredentialStoreUpdate;

import org.jboss.as.controller.AbstractWriteAttributeHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 * Attribute write step handler for a user resource in the users-in-domain-config authentication mechanism.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class UserWriteAttributeHandler extends AbstractWriteAttributeHandler<Void> {

    public UserWriteAttributeHandler() {
        super(UserResourceDefinition.PASSWORD, UserResourceDefinition.CREDENTIAL_REFERENCE);
    }

    @Override
    protected void finishModelStage(OperationContext context, ModelNode operation, String attributeName, ModelNode newValue,
                                    ModelNode oldValue, Resource resource) throws OperationFailedException {
        super.finishModelStage(context, operation, attributeName, newValue, oldValue, resource);
        if (attributeName.equals(UserResourceDefinition.CREDENTIAL_REFERENCE.getName())) {
            handleCredentialReferenceUpdate(context, resource.getModel().get(attributeName), attributeName);
        }
    }

    @Override
    protected boolean applyUpdateToRuntime(OperationContext context, ModelNode operation, String attributeName, ModelNode resolvedValue, ModelNode currentValue, HandbackHolder<Void> handbackHolder) throws OperationFailedException {
        ManagementUtil.updateUserDomainCallbackHandler(context, operation, false);
        if (attributeName.equals(UserResourceDefinition.CREDENTIAL_REFERENCE.getName())) {
            return applyCredentialReferenceUpdateToRuntime(context, operation, resolvedValue, currentValue, attributeName);
        }
        return false;
    }

    @Override
    protected void revertUpdateToRuntime(OperationContext context, ModelNode operation, String attributeName, ModelNode valueToRestore, ModelNode valueToRevert, Void handback) throws OperationFailedException {
        if (attributeName.equals(UserResourceDefinition.CREDENTIAL_REFERENCE.getName())) {
            rollbackCredentialStoreUpdate(UserResourceDefinition.CREDENTIAL_REFERENCE, context, valueToRevert);
        }
        ManagementUtil.updateUserDomainCallbackHandler(context, operation, true);
    }
}
