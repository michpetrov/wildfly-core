/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.manualmode.management;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import jakarta.inject.Inject;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.repository.PathUtil;
import org.jboss.as.test.integration.security.common.CoreUtils;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.dmr.ModelNode;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.core.testrunner.ServerControl;
import org.wildfly.core.testrunner.ServerController;
import org.wildfly.core.testrunner.WildFlyRunner;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
@RunWith(WildFlyRunner.class)
@ServerControl(manual = true)
public class ReadOnlyModeTestCase {

    @Inject
    private ServerController container;

    @Test
    public void testConfigurationNotUpdated() throws Exception {
        container.startReadOnly();

        ModelNode address = PathAddress.pathAddress("system-property", "read-only").toModelNode();
        try (ModelControllerClient client = container.getClient().getControllerClient()) {
            ModelNode op = Operations.createAddOperation(address);
            op.get("value").set(true);
            CoreUtils.applyUpdate(op, client);
            Assert.assertTrue(Operations.readResult(client.execute(Operations.createReadAttributeOperation(address, "value"))).asBoolean());
            container.reload();
            Assert.assertTrue(Operations.readResult(client.execute(Operations.createReadAttributeOperation(address, "value"))).asBoolean());
        }

        container.stop();
        container.startReadOnly();
        try (ModelControllerClient client = container.getClient().getControllerClient()) {
            Assert.assertTrue(Operations.getFailureDescription(client.execute(Operations.createReadAttributeOperation(address, "value"))).asString().contains("WFLYCTL0216"));
        }
    }

    @Test
    public void testReadOnlyConfigurationDirectory() throws Exception {
        final Path jbossHome = Paths.get(System.getProperty("jboss.home"));
        final Path configDir = jbossHome.resolve("standalone").resolve("configuration");
        final Path standaloneTmpDir = jbossHome.resolve("standalone").resolve("tmp");
        final Path osTmpDir = TestSuiteEnvironment.isWindows() ? new File("target", "tmp").toPath().toAbsolutePath() : Paths.get("/tmp");
        if(Files.notExists(osTmpDir)) {
            Files.createDirectories(osTmpDir);
        }
        final Path roConfigDir = Files.createTempDirectory(osTmpDir, "wildfly-test-suite-");
        PathUtil.copyRecursively(configDir, roConfigDir, true);

        if (TestSuiteEnvironment.isWindows()) {
            UserPrincipal owner = Files.getFileAttributeView(configDir, FileOwnerAttributeView.class).getOwner();
            AclEntry entry = AclEntry.newBuilder()
                    .setPrincipal(owner)
                    .setType(AclEntryType.DENY)
                    .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA)
                    .build();
            AclFileAttributeView view = Files.getFileAttributeView(roConfigDir, AclFileAttributeView.class);
            List<AclEntry> acl = view.getAcl();
            acl.add(0, entry);
            view.setAcl(acl);
        } else {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.getFileAttributeView(roConfigDir, PosixFileAttributeView.class).setPermissions(perms);
        }
        assertFalse(roConfigDir.toString() + " is writeable", Files.isWritable(roConfigDir));

        try {
            container.startReadOnly(roConfigDir);
            assertTrue("standalone_xml_history not found in " + standaloneTmpDir.toString(), Files.exists(standaloneTmpDir.resolve("standalone_xml_history")));

            ModelNode result;
            PathAddress address = PathAddress.pathAddress(PathElement.pathElement("subsystem", "elytron")).append("security-domain", "ApplicationDomain");
            try (ModelControllerClient client = container.getClient().getControllerClient()) {
                ModelNode op = Operations.createWriteAttributeOperation(address.toModelNode(), "security-event-listener", "local-audit");
                result = client.execute(op);

                Assert.assertTrue("Operation " + op.toString() + " failed with result " + result.toString(), Operations.isSuccessfulOutcome(result));
                Assert.assertTrue("Server it is expected to be in reload-required.", result.get("response-headers").get("process-state").asString().equals("reload-required"));

                container.reload();
                result = Operations.readResult(client.execute(Operations.createReadAttributeOperation(address.toModelNode(), "security-event-listener")));
                Assert.assertTrue("'security-event-listener' is expected to be 'local-audit'", result.asString().equals("local-audit"));
            }

            container.stop();
            container.startReadOnly(roConfigDir);
            try (ModelControllerClient client = container.getClient().getControllerClient()) {
                result = Operations.readResult(client.execute(Operations.createReadAttributeOperation(address.toModelNode(), "security-event-listener")));
                Assert.assertTrue("'security-event-listener' is expected to be 'undefined'", result.asString().equals("undefined"));
            }

        } finally {
            if (TestSuiteEnvironment.isWindows()) {
                UserPrincipal owner = Files.getFileAttributeView(configDir, FileOwnerAttributeView.class).getOwner();
                AclEntry entry = AclEntry.newBuilder()
                        .setPrincipal(owner)
                        .setType(AclEntryType.ALLOW)
                        .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA)
                        .build();
                AclFileAttributeView view = Files.getFileAttributeView(roConfigDir, AclFileAttributeView.class);
                List<AclEntry> acl = view.getAcl();
                acl.add(0, entry);
                view.setAcl(acl);
            } else {
                Set<PosixFilePermission> perms = new HashSet<>();
                perms.add(PosixFilePermission.OWNER_WRITE);
                perms.add(PosixFilePermission.GROUP_WRITE);
                perms.add(PosixFilePermission.OTHERS_WRITE);
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_READ);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_READ);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.getFileAttributeView(roConfigDir, PosixFileAttributeView.class).setPermissions(perms);
            }
            PathUtil.deleteRecursively(roConfigDir);
        }
    }

    @After
    public void stopContainer() {
        container.stop();
    }
}
