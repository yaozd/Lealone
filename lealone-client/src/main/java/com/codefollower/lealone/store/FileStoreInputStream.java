/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.lealone.store;

import java.io.IOException;
import java.io.InputStream;

import com.codefollower.lealone.constant.Constants;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.tools.CompressTool;
import com.codefollower.lealone.util.DataUtils;

/**
 * An input stream that is backed by a file store.
 */
public class FileStoreInputStream extends InputStream {

    private FileStore store;
    private final Data page;
    private int remainingInBuffer;
    private final CompressTool compress;
    private boolean endOfFile;
    private final boolean alwaysClose;

    public FileStoreInputStream(FileStore store, DataHandler handler, boolean compression, boolean alwaysClose) {
        this.store = store;
        this.alwaysClose = alwaysClose;
        if (compression) {
            compress = CompressTool.getInstance();
        } else {
            compress = null;
        }
        page = Data.create(handler, Constants.FILE_BLOCK_SIZE);
        try {
            if (store.length() <= FileStore.HEADER_LENGTH) {
                close();
            } else {
                fillBuffer();
            }
        } catch (IOException e) {
            throw DbException.convertIOException(e, store.name);
        }
    }

    public int available() {
        return remainingInBuffer <= 0 ? 0 : remainingInBuffer;
    }

    public int read(byte[] buff) throws IOException {
        return read(buff, 0, buff.length);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        int read = 0;
        while (len > 0) {
            int r = readBlock(b, off, len);
            if (r < 0) {
                break;
            }
            read += r;
            off += r;
            len -= r;
        }
        return read == 0 ? -1 : read;
    }

    private int readBlock(byte[] buff, int off, int len) throws IOException {
        fillBuffer();
        if (endOfFile) {
            return -1;
        }
        int l = Math.min(remainingInBuffer, len);
        page.read(buff, off, l);
        remainingInBuffer -= l;
        return l;
    }

    private void fillBuffer() throws IOException {
        if (remainingInBuffer > 0 || endOfFile) {
            return;
        }
        page.reset();
        store.openFile();
        if (store.length() == store.getFilePointer()) {
            close();
            return;
        }
        store.readFully(page.getBytes(), 0, Constants.FILE_BLOCK_SIZE);
        page.reset();
        remainingInBuffer = page.readInt();
        if (remainingInBuffer < 0) {
            close();
            return;
        }
        page.checkCapacity(remainingInBuffer);
        // get the length to read
        if (compress != null) {
            page.checkCapacity(Data.LENGTH_INT);
            page.readInt();
        }
        page.setPos(page.length() + remainingInBuffer);
        page.fillAligned();
        int len = page.length() - Constants.FILE_BLOCK_SIZE;
        page.reset();
        page.readInt();
        store.readFully(page.getBytes(), Constants.FILE_BLOCK_SIZE, len);
        page.reset();
        page.readInt();
        if (compress != null) {
            int uncompressed = page.readInt();
            byte[] buff = DataUtils.newBytes(remainingInBuffer);
            page.read(buff, 0, remainingInBuffer);
            page.reset();
            page.checkCapacity(uncompressed);
            CompressTool.expand(buff, page.getBytes(), 0);
            remainingInBuffer = uncompressed;
        }
        if (alwaysClose) {
            store.closeFile();
        }
    }

    public void close() {
        if (store != null) {
            try {
                store.close();
                endOfFile = true;
            } finally {
                store = null;
            }
        }
    }

    protected void finalize() {
        close();
    }

    public int read() throws IOException {
        fillBuffer();
        if (endOfFile) {
            return -1;
        }
        int i = page.readByte() & 0xff;
        remainingInBuffer--;
        return i;
    }

}
