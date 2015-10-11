/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.transaction.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Map;

import org.lealone.common.util.DataUtils;
import org.lealone.storage.fs.FilePath;
import org.lealone.storage.fs.FilePathDisk;
import org.lealone.storage.fs.FilePathEncrypt;
import org.lealone.storage.fs.FilePathNio;
import org.lealone.storage.fs.FileUtils;

/**
 * The default storage mechanism of the AOStorage. This implementation persists
 * data to a file. The file storage is responsible to persist data and for free
 * space management.
 * 
 * @author H2 Group
 * @author zhh
 */
public class LogFileStorage {

    /**
     * The number of read operations.
     */
    protected long readCount;

    /**
     * The number of read bytes.
     */
    protected long readBytes;

    /**
     * The number of write operations.
     */
    protected long writeCount;

    /**
     * The number of written bytes.
     */
    protected long writeBytes;

    /**
     * The file name.
     */
    protected String fileName;

    /**
     * Whether this storage is read-only.
     */
    protected boolean readOnly;

    /**
     * The file size (cached).
     */
    protected long fileSize;

    /**
     * The file.
     */
    protected FileChannel file;

    /**
     * The encrypted file (if encryption is used).
     */
    protected FileChannel encryptedFile;

    /**
     * The file lock.
     */
    protected FileLock fileLock;

    @Override
    public String toString() {
        return fileName;
    }

    /**
     * Read from the file.
     *
     * @param pos the write position
     * @param len the number of bytes to read
     * @return the byte buffer
     */
    public ByteBuffer readFully(long pos, int len) {
        ByteBuffer dst = ByteBuffer.allocate(len);
        DataUtils.readFully(file, pos, dst);
        readCount++;
        readBytes += len;
        return dst;
    }

    /**
     * Write to the file.
     *
     * @param pos the write position
     * @param src the source buffer
     */
    public void writeFully(long pos, ByteBuffer src) {
        int len = src.remaining();
        fileSize = Math.max(fileSize, pos + len);
        DataUtils.writeFully(file, pos, src);
        writeCount++;
        writeBytes += len;
    }

    /**
     * Try to open the file.
     *
     * @param fileName the file name
     * @param readOnly whether the file should only be opened in read-only mode,
     *            even if the file is writable
     * @param encryptionKey the encryption key, or null if encryption is not
     *            used
     */
    public void open(String fileName, Map<String, Object> config) {
        if (file != null) {
            return;
        }
        char[] encryptionKey = (char[]) config.get("encryptionKey");
        boolean readOnly = config.containsKey("readOnly");

        if (fileName != null) {
            FilePath p = FilePath.get(fileName);
            // if no explicit scheme was specified, NIO is used
            if (p instanceof FilePathDisk && !fileName.startsWith(p.getScheme() + ":")) {
                // ensure the NIO file system is registered
                FilePathNio.class.getName();
                fileName = "nio:" + fileName;
            }
        }
        this.fileName = fileName;
        FilePath f = FilePath.get(fileName);
        FilePath parent = f.getParent();
        if (parent != null && !parent.exists()) {
            throw DataUtils.newIllegalArgumentException("Directory does not exist: {0}", parent);
        }
        if (f.exists() && !f.canWrite()) {
            readOnly = true;
        }
        this.readOnly = readOnly;
        try {
            file = f.open(readOnly ? "r" : "rw");
            if (encryptionKey != null) {
                byte[] key = FilePathEncrypt.getPasswordBytes(encryptionKey);
                encryptedFile = file;
                file = new FilePathEncrypt.FileEncrypt(fileName, key, file);
            }
            try {
                if (readOnly) {
                    fileLock = file.tryLock(0, Long.MAX_VALUE, true);
                } else {
                    fileLock = file.tryLock();
                }
            } catch (OverlappingFileLockException e) {
                throw DataUtils.newIllegalStateException(DataUtils.ERROR_FILE_LOCKED, "The file is locked: {0}",
                        fileName, e);
            }
            if (fileLock == null) {
                throw DataUtils.newIllegalStateException(DataUtils.ERROR_FILE_LOCKED, "The file is locked: {0}",
                        fileName);
            }
            fileSize = file.size();
        } catch (IOException e) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_READING_FAILED, "Could not open file {0}",
                    fileName, e);
        }
    }

    /**
     * Close this storage.
     */
    public void close() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (file != null)
                file.close();
        } catch (Exception e) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_WRITING_FAILED, "Closing failed for file {0}",
                    fileName, e);
        } finally {
            file = null;
        }
    }

    /**
     * Flush all changes.
     */
    public void sync() {
        try {
            file.force(true);
        } catch (IOException e) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_WRITING_FAILED, "Could not sync file {0}",
                    fileName, e);
        }
    }

    /**
     * Get the file size.
     *
     * @return the file size
     */
    public long size() {
        return fileSize;
    }

    /**
     * Truncate the file.
     *
     * @param size the new file size
     */
    public void truncate(long size) {
        try {
            writeCount++;
            file.truncate(size);
            fileSize = Math.min(fileSize, size);
        } catch (IOException e) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_WRITING_FAILED,
                    "Could not truncate file {0} to size {1}", fileName, size, e);
        }
    }

    /**
     * Get the file instance in use.
     * <p>
     * The application may read from the file (for example for online backup),
     * but not write to it or truncate it.
     *
     * @return the file
     */
    public FileChannel getFile() {
        return file;
    }

    /**
     * Get the encrypted file instance, if encryption is used.
     * <p>
     * The application may read from the file (for example for online backup),
     * but not write to it or truncate it.
     *
     * @return the encrypted file, or null if encryption is not used
     */
    public FileChannel getEncryptedFile() {
        return encryptedFile;
    }

    /**
     * Get the number of write operations since this storage was opened.
     * For file based storages, this is the number of file write operations.
     *
     * @return the number of write operations
     */
    public long getWriteCount() {
        return writeCount;
    }

    /**
     * Get the number of written bytes since this storage was opened.
     *
     * @return the number of write operations
     */
    public long getWriteBytes() {
        return writeBytes;
    }

    /**
     * Get the number of read operations since this storage was opened.
     * For file based storages, this is the number of file read operations.
     *
     * @return the number of read operations
     */
    public long getReadCount() {
        return readCount;
    }

    /**
     * Get the number of read bytes since this storage was opened.
     *
     * @return the number of write operations
     */
    public long getReadBytes() {
        return readBytes;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Get the file name.
     *
     * @return the file name
     */
    public String getFileName() {
        return fileName;
    }

    public void delete() {
        FileUtils.delete(fileName);
    }
}
