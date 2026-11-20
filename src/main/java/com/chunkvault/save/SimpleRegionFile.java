package com.chunkvault.save;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public final class SimpleRegionFile implements AutoCloseable {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_SECTORS = 2;

    private final RandomAccessFile raf;
    private final int[] offsets = new int[1024];
    private final BitSet usedSectors = new BitSet();

    public SimpleRegionFile(Path file) throws IOException {
        boolean isNew = !file.toFile().exists();
        this.raf = new RandomAccessFile(file.toFile(), "rw");
        usedSectors.set(0, HEADER_SECTORS);
        if (isNew || raf.length() < HEADER_SECTORS * (long) SECTOR_BYTES) {
            raf.setLength(HEADER_SECTORS * (long) SECTOR_BYTES);
        } else {
            loadExistingHeader();
        }
    }

    private void loadExistingHeader() throws IOException {
        raf.seek(0);
        for (int i = 0; i < 1024; i++) {
            int packed = raf.readInt();
            offsets[i] = packed;
            int offset = packed >>> 8;
            int count = packed & 0xFF;
            if (offset >= HEADER_SECTORS && count > 0) {
                usedSectors.set(offset, offset + count);
            }
        }
    }

    private int allocateSectors(int count) {
        int runStart = HEADER_SECTORS;
        while (true) {
            int freeStart = usedSectors.nextClearBit(runStart);
            int nextUsed = usedSectors.nextSetBit(freeStart);
            if (nextUsed == -1 || nextUsed - freeStart >= count) {
                usedSectors.set(freeStart, freeStart + count);
                return freeStart;
            }
            runStart = nextUsed + 1;
        }
    }

    public synchronized boolean deleteChunk(int localX, int localZ) throws IOException {
        int index = localZ * 32 + localX;
        int oldPacked = offsets[index];
        if (oldPacked == 0) {
            return false;
        }
        int oldOffset = oldPacked >>> 8;
        int oldCount = oldPacked & 0xFF;
        if (oldOffset >= HEADER_SECTORS && oldCount > 0) {
            usedSectors.clear(oldOffset, oldOffset + oldCount);
        }
        offsets[index] = 0;
        raf.seek((long) index * 4);
        raf.writeInt(0);
        raf.seek(SECTOR_BYTES + (long) index * 4);
        raf.writeInt(0);
        return true;
    }

    public synchronized boolean hasAnyChunks() {
        for (int offset : offsets) {
            if (offset != 0) {
                return true;
            }
        }
        return false;
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
        if (sectors > 255) {
            throw new IOException("Chunk exceeds maximum region sector count: " + sectors);
        }

        int index = localZ * 32 + localX;
        int oldPacked = offsets[index];
        int oldOffset = oldPacked >>> 8;
        int oldCount = oldPacked & 0xFF;

        int sectorStart;
        boolean reuseInPlace = oldOffset >= HEADER_SECTORS && oldCount >= sectors;
        if (reuseInPlace) {
            sectorStart = oldOffset;
        } else {
            sectorStart = allocateSectors(sectors);
        }

        raf.seek((long) sectorStart * SECTOR_BYTES);
        raf.writeInt(totalLen);
        raf.writeByte(2);
        raf.write(payload);

        long pad = (long) sectors * SECTOR_BYTES - (4L + totalLen);
        if (pad > 0) {
            raf.write(new byte[(int) pad]);
        }

        int packed = (sectorStart << 8) | (sectors & 0xFF);
        offsets[index] = packed;

        raf.seek((long) index * 4);
        raf.writeInt(packed);

        raf.seek(SECTOR_BYTES + (long) index * 4);
        raf.writeInt((int) (System.currentTimeMillis() / 1000L));

        if (reuseInPlace) {
            if (oldCount > sectors) {
                usedSectors.clear(oldOffset + sectors, oldOffset + oldCount);
            }
        } else if (oldOffset >= HEADER_SECTORS && oldCount > 0) {
            usedSectors.clear(oldOffset, oldOffset + oldCount);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            long minLength = Math.max(HEADER_SECTORS, usedSectors.length()) * (long) SECTOR_BYTES;
            if (raf.length() > minLength) {
                raf.setLength(minLength);
            }
        } finally {
            raf.close();
        }
    }
}
