package com.winlator.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.junit.Test;

public class Wow64CpuUnixTransitionPatchTest {
    private static final String FIXTURE_PROPERTY = "wow64cpu.fixture";

    private File fixture() {
        String fixturePath = System.getProperty(FIXTURE_PROPERTY);
        assertTrue("Set -D" + FIXTURE_PROPERTY +
            " to the Wine 10.10 wow64cpu.dll fixture.",
            fixturePath != null && !fixturePath.isEmpty());
        return new File(fixturePath);
    }

    @Test
    public void appliesIdempotentlyAndRestoresByteForByte() throws Exception {
        File fixture = fixture();
        byte[] original = Files.readAllBytes(fixture.toPath());
        assertTrue(Wow64CpuUnixTransitionPatch.isOriginal(original));

        File directory = Files.createTempDirectory("wow64cpu-patch-test").toFile();
        File prefixDll = new File(directory, "wow64cpu.dll");
        Files.copy(fixture.toPath(), prefixDll.toPath(),
            StandardCopyOption.REPLACE_EXISTING);

        assertEquals(Wow64CpuUnixTransitionPatch.Result.APPLIED,
            Wow64CpuUnixTransitionPatch.configure(fixture, prefixDll, true));
        assertTrue(Wow64CpuUnixTransitionPatch.isPatched(
            Files.readAllBytes(prefixDll.toPath())));
        assertEquals(Wow64CpuUnixTransitionPatch.Result.ALREADY_APPLIED,
            Wow64CpuUnixTransitionPatch.configure(fixture, prefixDll, true));
        assertEquals(Wow64CpuUnixTransitionPatch.Result.RESTORED,
            Wow64CpuUnixTransitionPatch.configure(fixture, prefixDll, false));
        assertArrayEquals(original, Files.readAllBytes(prefixDll.toPath()));
    }

    @Test
    public void acceptsOnlyPeMetadataDriftAndPreservesIt() throws Exception {
        File fixture = fixture();
        byte[] materialized = Files.readAllBytes(fixture.toPath());
        materialized[0x88] ^= 0x7f;
        materialized[0x89] ^= 0x23;
        materialized[0xd8] ^= 0x55;
        assertTrue(Wow64CpuUnixTransitionPatch.isOriginal(materialized));

        File directory = Files.createTempDirectory("wow64cpu-metadata-test").toFile();
        File prefixDll = new File(directory, "wow64cpu.dll");
        Files.write(prefixDll.toPath(), materialized);
        assertEquals(Wow64CpuUnixTransitionPatch.Result.APPLIED,
            Wow64CpuUnixTransitionPatch.configure(fixture, prefixDll, true));
        assertEquals(Wow64CpuUnixTransitionPatch.Result.RESTORED,
            Wow64CpuUnixTransitionPatch.configure(fixture, prefixDll, false));
        assertArrayEquals(materialized, Files.readAllBytes(prefixDll.toPath()));

        materialized[0x1000] ^= 0x01;
        assertFalse(Wow64CpuUnixTransitionPatch.isOriginal(materialized));
        Files.write(prefixDll.toPath(), materialized);
        assertEquals(Wow64CpuUnixTransitionPatch.Result.UNSUPPORTED,
            Wow64CpuUnixTransitionPatch.configure(fixture, prefixDll, true));
    }
}
