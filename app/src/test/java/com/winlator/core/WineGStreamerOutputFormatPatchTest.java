package com.winlator.core;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.junit.Test;

public class WineGStreamerOutputFormatPatchTest {
    private File fixture() { String path = System.getProperty("winegstreamer.fixture"); assertTrue(path != null && !path.isEmpty()); return new File(path); }
    @Test public void appliesIdempotentlyAndRestoresByteForByte() throws Exception {
        File fixture = fixture(); byte[] original = Files.readAllBytes(fixture.toPath()); assertTrue(WineGStreamerOutputFormatPatch.isOriginal(original));
        File prefix = new File(Files.createTempDirectory("winegstreamer-test").toFile(), "winegstreamer.dll"); Files.copy(fixture.toPath(), prefix.toPath(), StandardCopyOption.REPLACE_EXISTING);
        assertEquals(WineGStreamerOutputFormatPatch.Result.APPLIED, WineGStreamerOutputFormatPatch.configure(fixture, prefix, true));
        assertEquals(WineGStreamerOutputFormatPatch.Result.ALREADY_APPLIED, WineGStreamerOutputFormatPatch.configure(fixture, prefix, true));
        assertEquals(WineGStreamerOutputFormatPatch.Result.RESTORED, WineGStreamerOutputFormatPatch.configure(fixture, prefix, false));
        assertArrayEquals(original, Files.readAllBytes(prefix.toPath()));
    }
}
