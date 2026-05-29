package de.intranda.goobi.plugins.utils;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchiveUtilsTest {

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void untarFileRejectsTarSlipEntry() throws IOException {
        // Arrange: TAR mit einem Path-Traversal-Eintrag
        File tarFile = tmpFolder.newFile("malicious.tar");
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(Files.newOutputStream(tarFile.toPath()))) {
            byte[] content = "evil".getBytes();
            TarArchiveEntry entry = new TarArchiveEntry("../../evil.txt");
            entry.setSize(content.length);
            tos.putArchiveEntry(entry);
            tos.write(content);
            tos.closeArchiveEntry();
        }

        File destDir = tmpFolder.newFolder("dest");

        // Act + Assert: IOException erwartet
        try {
            ArchiveUtils.untarFile(tarFile, destDir);
            fail("Tar Slip: IOException erwartet, aber keine geworfen");
        } catch (IOException e) {
            assertTrue("Fehlermeldung soll auf Tar Slip hinweisen",
                    e.getMessage() != null && e.getMessage().toLowerCase().contains("tar slip"));
        }

        // Sicherstellen, dass die Datei nicht außerhalb von destDir angelegt wurde
        File escaped = new File(tmpFolder.getRoot(), "evil.txt");
        assertTrue("evil.txt darf nicht außerhalb von destDir existieren", !escaped.exists());
    }

    @Test
    public void untarFileExtractsLegitimateEntries() throws IOException {
        // Arrange: TAR mit normalem Eintrag
        File tarFile = tmpFolder.newFile("normal.tar");
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(Files.newOutputStream(tarFile.toPath()))) {
            byte[] content = "hello".getBytes();
            TarArchiveEntry entry = new TarArchiveEntry("subdir/file.txt");
            entry.setSize(content.length);
            tos.putArchiveEntry(entry);
            tos.write(content);
            tos.closeArchiveEntry();
        }

        File destDir = tmpFolder.newFolder("dest2");

        // Act
        ArchiveUtils.untarFile(tarFile, destDir);

        // Assert
        File extracted = new File(destDir, "subdir/file.txt");
        assertTrue("Legitimer Eintrag soll extrahiert werden", extracted.exists());
    }
}
