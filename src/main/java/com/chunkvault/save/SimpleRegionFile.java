package com.chunkvault.save;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public final class SimpleRegionFile implements AutoCloseable {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_SECTORS = 2;

    private final RandomAccessFile raf;
    private int nextFreeSector = HEADER_SECTORS;

    public SimpleRegionFile(Path file) throws IOException {
        boolean isNew = !file.toFile().exists();
        this.raf = new RandomAccessFile(file.toFile(), "rw");
        if (isNew) {
            raf.setLength(HEADER_SECTORS * (long) SECTOR_BYTES);
        } else {
            loadExistingHeader();
        }
    }

    private void loadExistingHeader() throws IOException {
        raf.seek(0);
        int maxSector = HEADER_SECTORS;
        for (int i = 0; i < 1024; i++) {
            int packed = raf.readInt();
            int offset = packed >>> 8;
            int count = packed & 0xFF;
            if (offset > 0 && count > 0) {
                maxSector = Math.max(maxSector, offset + count);
            }
        }
        nextFreeSector = maxSector;
    }

    public synchronized void writeChunk(int localX, int localZ, byte[] uncompressedNbt) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed, deflater)) {
            dos.write(uncompressedNbt);
        } finally {
            deflater.end();
        }
        byte[] payload = compressed.toByteArray();

        int totalLen = payload.length + 1;
        int sectors = (totalLen + 4 + SECTOR_BYTES - 1) / SECTOR_BYTES;

        int sectorStart = nextFreeSector;
        nextFreeSector += sectors;

        raf.seek((long) sectorStart * SECTOR_BYTES);
        raf.writeInt(totalLen);
        raf.writeByte(2); // zlib
        raf.write(payload);

        long pad = (long) sectors * SECTOR_BYTES - (4L + totalLen);
        if (pad > 0) {
            raf.write(new byte[(int) pad]);
        }

        int index = localZ * 32 + localX;
        int packed = (sectorStart << 8) | (sectors & 0xFF);

        raf.seek((long) index * 4);
        raf.writeInt(packed);

        raf.seek(SECTOR_BYTES + (long) index * 4);
        raf.writeInt((int) (System.currentTimeMillis() / 1000L));
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
