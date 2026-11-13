/*
 * Adapted in part from KramerTech/gen5-RearDashcam (MIT License, Copyright (c) 2026 KramerTech).
 * Pure-Java /proc/pmap parser for the Gen5W reserved "rearcamera" physical memory region.
 */
package fitcamx.rearcam;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;

public final class PmapParser {
    public static final int RING_SLOTS = 4;
    public static final String PMAP_PATH = "/proc/pmap";
    public static final String REGION_NAME = "rearcamera";

    private PmapParser() {}

    /**
     * Reads /proc/pmap and returns { physicalAddress, byteLength } for the "rearcamera" region,
     * or null if no valid region is present.
     */
    public static long[] findRearCameraRegion() throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(PMAP_PATH));
        try {
            return parsePmapReader(reader, REGION_NAME);
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {}
        }
    }

    public static long[] parsePmapText(String pmapContent, String regionName) throws IOException {
        if (pmapContent == null) return null;
        return parsePmapReader(new BufferedReader(new StringReader(pmapContent)), regionName);
    }

    public static long[] parsePmapReader(BufferedReader reader, String regionName) throws IOException {
        String target = (regionName != null) ? regionName : REGION_NAME;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] fields = trimmed.split("\\s+");
            if (fields.length >= 3 && target.equals(fields[2])
                    && fields[0].startsWith("0x") && fields[1].startsWith("0x")) {
                try {
                    long base = new java.math.BigInteger(fields[0].substring(2), 16).longValue();
                    long size = new java.math.BigInteger(fields[1].substring(2), 16).longValue();
                    if (size > 0) {
                        return new long[] { base, size };
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    public static boolean fitsRing(long regionSizeBytes, int width, int height) {
        if (width <= 0 || height <= 0 || regionSizeBytes <= 0) return false;
        long needed = (long) width * (long) height * 4L * (long) RING_SLOTS;
        return needed <= regionSizeBytes;
    }
}
