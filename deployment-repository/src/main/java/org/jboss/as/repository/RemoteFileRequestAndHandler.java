/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.repository;

import static org.jboss.as.protocol.mgmt.ProtocolUtils.expectHeader;

import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.protocol.mgmt.ActiveOperation;
import org.jboss.as.protocol.mgmt.FlushableDataOutput;
import org.jboss.as.protocol.mgmt.ManagementRequestContext;
import org.jboss.as.protocol.mgmt.ManagementResponseHeader;
import org.jboss.as.protocol.mgmt.RequestProcessingException;
import org.jboss.logging.BasicLogger;


/**
 * Common protocol code for getting files from master->slave HC and HC->server.
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
public abstract class RemoteFileRequestAndHandler {

    private final RemoteFileProtocolIdMapper protocol;
    private final Executor asyncExecutor;

    protected RemoteFileRequestAndHandler(RemoteFileProtocolIdMapper protocol) {
        this(protocol, null);
    }

    protected RemoteFileRequestAndHandler(RemoteFileProtocolIdMapper protocol, Executor asyncExecutor) {
        this.protocol = protocol;
        this.asyncExecutor = asyncExecutor;
    }

    public void sendRequest(FlushableDataOutput output, byte rootId, String filePath) throws IOException{
        output.writeByte(protocol.paramRootId());
        output.writeByte(rootId);
        output.writeByte(protocol.paramFilePath());
        output.writeUTF(filePath);

    }

    public void handleResponse(DataInput input, File localPath, BasicLogger log, ActiveOperation.ResultHandler<File> resultHandler, ManagementRequestContext<Void> context)
            throws IOException, CannotCreateLocalDirectoryException, DidNotReadEntireFileException{
        expectHeader(input, protocol.paramNumFiles());
        int numFiles = input.readInt();
        log.debugf("Received %d files for %s", numFiles, localPath);
        switch (numFiles) {
            case -1: { // Not found on DC
                break;
            }
            case 0: { // Found on DC, but was an empty dir
                if (!localPath.mkdirs()) {
                    throw new CannotCreateLocalDirectoryException(localPath);
                }
                break;
            }
            default: { // Found on DC
                for (int i = 0; i < numFiles; i++) {
                    expectHeader(input, protocol.fileStart());
                    expectHeader(input, protocol.paramFilePath());
                    final String path = input.readUTF();
                    expectHeader(input, protocol.paramFileSize());
                    final long length = input.readLong();
                    log.debugf("Received file [%s] of length %d", path, length);
                    final File file = new File(localPath, path);
                    if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                        throw new CannotCreateLocalDirectoryException(localPath.getParentFile());
                    }
                    if(length == 0L) {
                        file.mkdir();
                    } else {
                        long totalRead = 0;
                        try (OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(file))) {
                            final byte[] buffer = new byte[8192];
                            while (totalRead < length) {
                                int len = Math.min((int) (length - totalRead), buffer.length);
                                input.readFully(buffer, 0, len);
                                fileOut.write(buffer, 0, len);
                                totalRead += len;
                            }
                        }
                        if (totalRead != length) {
                            throw new DidNotReadEntireFileException((length - totalRead));
                        }
                    }
                    expectHeader(input, protocol.fileEnd());
                }
            }
        }
        resultHandler.done(localPath);
    }

    public void handleRequest(final DataInput input, final RootFileReader reader,
                              final ActiveOperation.ResultHandler<Void> resultHandler,
                              final ManagementRequestContext<Void> context) throws IOException {
        expectHeader(input, protocol.paramRootId());
        final byte rootId = input.readByte();
        expectHeader(input, protocol.paramFilePath());
        final String filePath = input.readUTF();

        ManagementRequestContext.AsyncTask<Void> task = new ManagementRequestContext.AsyncTask<Void>() {
            @Override
            public void execute(ManagementRequestContext<Void> context) throws RequestProcessingException, IOException {
                final File localPath = reader.readRootFile(rootId, filePath);
                FlushableDataOutput output = context.writeMessage(ManagementResponseHeader.create(context.getRequestHeader()));
                try {
                    writeResponse(localPath, output);
                    output.close();
                    resultHandler.done(null); // call stack (AsyncTaskRunner created by ManagementRequestContext) handles failures
                } finally {
                    StreamUtils.safeClose(output);
                }
            }
        };

        if (asyncExecutor == null) {
            context.executeAsync(task);
        } else {
            context.executeAsync(task, asyncExecutor);
        }
    }

    private void writeResponse(final File localPath, final FlushableDataOutput output) throws IOException {
        output.writeByte(protocol.paramNumFiles());
        if (localPath == null || !localPath.exists()) {
            output.writeInt(-1);
        } else if (localPath.isFile()) {
            output.writeInt(1);
            writeFile(localPath, localPath, output);
        } else {
            final List<File> childFiles = getChildFiles(localPath);
            output.writeInt(childFiles.size());
            for (File child : childFiles) {
                writeFile(localPath, child, output);
            }
        }
    }

    private List<File> getChildFiles(final File base) {
        final List<File> childFiles = new ArrayList<>();
        getChildFiles(base, childFiles);
        return childFiles;
    }

    private void getChildFiles(final File base, final List<File> childFiles) {
        for (File child : base.listFiles()) {
            if (child.isFile() || isEmpty(child)) {
                childFiles.add(child);
            } else {
                getChildFiles(child, childFiles);
            }
        }
    }

    private boolean isEmpty(File file) {
        return file.isDirectory() && (file.list() == null || file.list().length == 0);
    }

    private String getRelativePath(final File parent, final File child) {
        return child.getAbsolutePath().substring(parent.getAbsolutePath().length()+1);
    }

    private void writeFile(final File localPath, final File file, final FlushableDataOutput output) throws IOException {
        output.writeByte(protocol.fileStart());
        output.writeByte(protocol.paramFilePath());
        output.writeUTF(getRelativePath(localPath, file));
        output.writeByte(protocol.paramFileSize());
        if (file.isDirectory()) {
            output.writeLong(0L);
            output.writeByte(protocol.fileEnd());
            return;
        } else {
            output.writeLong(file.length());
        }
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
        output.writeByte(protocol.fileEnd());
    }

    /**
     * Maps the expected protocol codes to the actual protocol
     */
    public interface RemoteFileProtocolIdMapper {
        byte paramRootId();
        byte paramNumFiles();
        byte fileStart();
        byte paramFilePath();
        byte paramFileSize();
        byte fileEnd();
    }

    /**
     * Reads the root file being got
     */
    public interface RootFileReader {
        File readRootFile(byte rootId, String filePath) throws RequestProcessingException;
    }

    /**
     *  Indicates a directory could not be created
     */
    public static class CannotCreateLocalDirectoryException extends Exception {
        private static final long serialVersionUID = 1L;
        final File dir;

        private CannotCreateLocalDirectoryException(File dir) {
            this.dir = dir;
        }

        public File getDir() {
            return dir;
        }
    }

    /**
     *  Indicates a file was not completely read
     */
    public static class DidNotReadEntireFileException extends Exception {
        private static final long serialVersionUID = 1L;
        final long missing;

        private DidNotReadEntireFileException(long missing) {
            this.missing = missing;
        }

        public long getMissing() {
            return missing;
        }
    }
}
