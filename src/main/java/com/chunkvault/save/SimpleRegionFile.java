package com.chunkvault.save;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Minimal, from-scratch writer for the vanilla Anvil ".mca" region file format.
 *
 * Deliberately NOT using net.minecraft.world.level.chunk.storage.RegionFile:
 * that class's constructor signature has changed multiple times across
 * versions (plain File -> Path + Path -> RegionStorageInfo + Path + Path),
 * and getting it wrong is a guessing game without the real jar in hand.
 *
 * The on-disk format itself, by contrast, has been stable since Minecraft
 * 1.2 (2012) and is fully documented:
 *
 *   - 8 KiB header: 1024 x (3-byte sector offset + 1-byte sector count)
 *   - 8 KiB timestamp table: 1024 x 4-byte unix timestamps
 *   - chunk payloads, each: 4-byte big-endian length, 1-byte compression
 *     type (2 = zlib/deflate, which is what we use), then that many bytes
 *     of compressed NBT data. Payloads are padded to a multiple of the
 *     4096-byte "sector" size.
 *
 * One instance handles a single 32x32-chunk region (one .mca file).
 */
public final class SimpleRegionFile implements AutoCloseable {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_SECTORS = 2; // offset table + timestamp table

    private final RandomAccessFile raf;
    private final int[] sectorOffset = new int[1024]; // packed: offset<<8 | count
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
            sectorOffset[i] = packed;
            int offset = packed >>> 8;
            int count = packed & 0xFF;
            if (offset > 0 && count > 0) {
                maxSector = Math.max(maxSector, offset + count);
            }
        }
        nextFreeSector = maxSector;
    }

    /** local chunk coords: 0..31 within this region */
    public synchronized void writeChunk(int localX, int localZ, byte[] uncompressedNbt) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed, deflater)) {
            dos.write(uncompressedNbt);
        } finally {
            deflater.end();
        }
        byte[] payload = compressed.toByteArray();

        int totalLen = payload.length + 1; // + compression-type byte
        int sectors = ceilDiv(totalLen + 4, SECTOR_BYTES); // +4 for the length field itself

        int sectorStart = nextFreeSector;
        nextFreeSector += sectors;

        raf.seek((long) sectorStart * SECTOR_BYTES);
        raf.writeInt(totalLen);
        raf.writeByte(2); // zlib
        raf.write(payload);

        long padded = (long) sectors * SECTOR_BYTES;
        long written = 4L + totalLen;
        long pad = padded - written;
        if (pad > 0) {
            raf.write(new byte[(int) pad]);
        }

        int index = localZ * 32 + localX;
        int packed = (sectorStart << 8) | (sectors & 0xFF);
        sectorOffset[index] = packed;

        raf.seek((long) index * 4);
        raf.writeInt(packed);

        raf.seek((long) (HEADER_SECTORS == 2 ? SECTOR_BYTES : 0) + (long) index * 4);
        raf.writeInt((int) (System.currentTimeMillis() / 1000L));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
