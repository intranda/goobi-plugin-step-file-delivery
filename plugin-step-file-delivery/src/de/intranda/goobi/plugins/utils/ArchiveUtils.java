/*************************************************************************
 * 
 * Copyright intranda GmbH
 * 
 * ************************* CONFIDENTIAL ********************************
 * 
 * [2003] - [2013] intranda GmbH, Bertha-von-Suttner-Str. 9, 37085 Göttingen, Germany 
 * 
 * All Rights Reserved.
 * 
 * NOTICE: All information contained herein is protected by copyright. 
 * The source code contained herein is proprietary of intranda GmbH. 
 * The dissemination, reproduction, distribution or modification of 
 * this source code, without prior written permission from intranda GmbH, 
 * is expressly forbidden and a violation of international copyright law.
 * 
 *************************************************************************/
package de.intranda.goobi.plugins.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import org.apache.log4j.Logger;

/**
 * Utility methods for creating, extracting and verifying Tar- und Zip-Archives
 * 
 * @author florian
 * 
 */
public class ArchiveUtils {

    private static final Logger logger = Logger.getLogger(ArchiveUtils.class);

    /**
     * Unzip a gzip file and write the result into file dest
     * 
     * @param source
     * @param dest
     * @return
     * @throws IOException
     */
    public static File unGzipFile(File source, File dest) throws IOException {

        FileInputStream fis = null;
        BufferedInputStream bis = null;
        GZIPInputStream gis = null;
        FileOutputStream fos = null;

        try {
            fis = new FileInputStream(source);
            bis = new BufferedInputStream(fis);
            gis = new GZIPInputStream(bis);
            fos = new FileOutputStream(dest);

            byte[] buf = new byte[1024];
            int len;
            while ((len = gis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        } finally {
            if (fis != null) {
                fis.close();
            }
            if (bis != null) {
                bis.close();
            }
            if (gis != null) {
                gis.close();
            }
            if (fos != null) {
                fos.close();
            }
        }
        return dest;
    }

    /**
     * Unzip a zip archive and write results into Array of Strings
     * 
     * @param source
     * @return
     * @throws IOException
     */
    public static ArrayList<File> unzipFile(File source, File destDir) throws IOException {
        ArrayList<File> fileList = new ArrayList<File>();

        if (!destDir.isDirectory())
            destDir.mkdirs();

        ZipInputStream in = null;
        try {
            in = new ZipInputStream((new BufferedInputStream(new FileInputStream(source))));
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                File tempFile = new File(destDir, entry.getName());
                fileList.add(tempFile);
                tempFile.getParentFile().mkdirs();
                tempFile.createNewFile();
                logger.debug("Unzipping file " + entry.getName() + " from archive " + source.getName() + " to " + tempFile.getAbsolutePath());
                BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile));

                int size;
                byte[] buffer = new byte[2048];
                while ((size = in.read(buffer, 0, buffer.length)) != -1) {
                    out.write(buffer, 0, size);
                }
                // for (int c = in.read(); c != -1; c = in.read()) {
                // out.write(c);
                // }
                if (entry != null)
                    in.closeEntry();
                if (out != null) {
                    out.flush();
                    out.close();
                }
            }
        } catch (FileNotFoundException e) {
            logger.debug("Encountered FileNotFound Exception, probably due to trying to extract a directory. Ignoring");
        } catch (IOException e) {
            logger.error(e.toString(), e);
        } finally {
            if (in != null)
                in.close();
        }

        return fileList;
    }

    /**
     * Create a tar archive and write results into Array of Strings. Returns the MD5 checksum as byte-Array
     * 
     * @param source
     * @return
     * @throws IOException
     */
    public static byte[] tarFiles(HashMap<File, String> fileMap, File tarFile) throws IOException {

        MessageDigest checksum = null;
        boolean gzip = false;
        if (tarFile.getName().endsWith(".gz")) {
            gzip = true;
        }

        if (tarFile == null || fileMap == null || fileMap.size() == 0) {
            return null;
        }

        tarFile.getParentFile().mkdirs();

        TarArchiveOutputStream tos = null;
        FileOutputStream fos = null;
        BufferedOutputStream bos = null;
        GZIPOutputStream zip = null;
        try {
            fos = new FileOutputStream(tarFile, true);
            bos = new BufferedOutputStream(fos);
            if (gzip) {
                zip = new GZIPOutputStream(bos);
            }
            try {
                checksum = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                logger.error("No checksum algorithm \"MD5\". Disabling checksum creation");
                checksum = null;
            }
            if (gzip) {
                tos = new TarArchiveOutputStream(zip);
            } else {
                tos = new TarArchiveOutputStream(bos);
            }
            for (File file : fileMap.keySet()) {
                logger.debug("Adding file " + file.getAbsolutePath() + " to tarfile " + tarFile.getAbsolutePath());
                tarFile(file, fileMap.get(file), tos, checksum);
            }
        } catch (FileNotFoundException e) {
            logger.debug("Encountered FileNotFound Exception, probably due to trying to archive a directory. Ignoring");
        } catch (IOException e) {
            logger.error(e.toString(), e);
        } finally {
            if (tos != null) {
                tos.close();
            }
            if (fos != null) {
                tos.close();
            }
            if (bos != null) {
                tos.close();
            }
            if (zip != null) {
                tos.close();
            }
        }

        return checksum.digest();
    }

    private static void tarFile(File file, String path, TarArchiveOutputStream tos, MessageDigest checksum) throws IOException {

        if (file == null) {
            logger.error("Attempting to add nonexisting file to zip archive. Ignoring entry.");
            return;
        }

        if (file.isFile()) {

            FileInputStream fis = new FileInputStream(file);
            BufferedInputStream bis = new BufferedInputStream(fis);

            TarArchiveEntry entry = new TarArchiveEntry(file, path);
            //			ArchiveEntry entry = tos.createArchiveEntry(file, path);

            if (tos != null) {
                tos.putArchiveEntry(entry);
                int size;
                byte[] buffer = new byte[2048];
                while ((size = bis.read(buffer, 0, buffer.length)) != -1) {
                    tos.write(buffer, 0, size);
                    if (checksum != null && size > 0) {
                        checksum.update(buffer, 0, size);
                    }
                }
                tos.closeArchiveEntry();
            }

            bis.close();
        } else if (file.isDirectory()) {
            if (tos != null) {
                ArchiveEntry dirEntry = tos.createArchiveEntry(file, path + File.separator);
                if (dirEntry != null) {
                    tos.putArchiveEntry(dirEntry);
                    tos.closeArchiveEntry();
                }
            }
            File[] subfiles = file.listFiles();
            if (subfiles != null && subfiles.length > 0) {
                for (File subFile : subfiles) {
                    tarFile(subFile, path + File.separator + subFile.getName(), tos, checksum);
                }
            }
        } else {
            logger.warn("File " + file.getAbsolutePath() + " doesn't seem to exist and cannot be added to zip archive.");
        }
    }

    /**
     * Unzip a tar archive and write results into Array of Strings
     * 
     * @param source
     * @return
     * @throws IOException
     */
    public static ArrayList<File> untarFile(File source, File destDir) throws IOException {
        ArrayList<File> fileList = new ArrayList<File>();

        if (!destDir.isDirectory())
            destDir.mkdirs();

        boolean isGzip = false;
        if (source.getName().endsWith(".gz")) {
            isGzip = true;
        }

        GZIPInputStream zip = null;
        TarArchiveInputStream in = null;
        BufferedInputStream bis = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(source);
            bis = new BufferedInputStream(fis);
            if (isGzip) {
                zip = new GZIPInputStream(bis);
                in = new TarArchiveInputStream(zip);
            } else {
                in = new TarArchiveInputStream(bis);
            }
            ArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                File tempFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    tempFile.mkdirs();
                    continue;
                }
                fileList.add(tempFile);
                tempFile.getParentFile().mkdirs();
                //				tempFile.createNewFile();
                logger.debug("Untaring file " + entry.getName() + " from archive " + source.getName() + " to " + tempFile.getAbsolutePath());
                BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile));

                int size;
                byte[] buffer = new byte[2048];
                while ((size = in.read(buffer, 0, buffer.length)) != -1) {
                    out.write(buffer, 0, size);
                }
                if (out != null) {
                    out.flush();
                    out.close();
                }
            }
        } catch (FileNotFoundException e) {
            logger.debug("Encountered FileNotFound Exception, probably due to trying to extract a directory. Ignoring");
        } finally {
            if (in != null)
                in.close();
        }

        return fileList;
    }

    /**
     * Crude Tar-Archive validator which extracts all archive entries to check their validity
     * 
     * @param tarFile
     * @param createTempFile
     * @param origFilesParent
     * @return
     */
    public static boolean validateTar(File tarFile, boolean createTempFile, File origFilesParent) {

        boolean isGzip = false;
        if (tarFile.getName().endsWith(".gz")) {
            isGzip = true;
        }

        GZIPInputStream zip = null;
        TarArchiveInputStream in = null;
        BufferedInputStream bis = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(tarFile);
            bis = new BufferedInputStream(fis);
            if (isGzip) {
                zip = new GZIPInputStream(bis);
                in = new TarArchiveInputStream(zip);
            } else {
                in = new TarArchiveInputStream(bis);
            }

            //			in = new TarArchiveInputStream((new BufferedInputStream(new FileInputStream(tarFile))));
            TarArchiveEntry entry;
            File tempFile = null;
            while ((entry = in.getNextTarEntry()) != null) {
                File f = new File(entry.getName());
                tempFile = new File(tarFile.getParentFile(), f.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                logger.debug("Testing file " + entry.getName() + " from archive " + tarFile.getName());
                BufferedOutputStream out = null;
                if (createTempFile) {
                    if (!tempFile.isFile()) {
                        tempFile.createNewFile();
                    }
                    out = new BufferedOutputStream(new FileOutputStream(tempFile));
                }

                int size;
                byte[] buffer = new byte[2048];
                while ((size = in.read(buffer, 0, buffer.length)) != -1) {
                    if (createTempFile) {
                        out.write(buffer, 0, size);
                    }
                }
                if (out != null) {
                    out.flush();
                    out.close();
                }

                if (createTempFile && (tempFile == null || !tempFile.isFile())) {
                    logger.debug("Found corrupted archive entry: Unable to create file");
                    return false;
                }

                //check checksum of file
                try {
                    if (createTempFile && origFilesParent != null) {
                        File origFile = new File(origFilesParent, entry.getName());
                        if (origFile == null || !origFile.isFile()) {
                            logger.debug("Unable to find orig file for entry " + entry.getName());
                            continue;
                        }
                        logger.debug("Testing entry against original file " + origFile.getAbsolutePath());
                        byte[] tempFileChecksum = createMD5Checksum(tempFile);
                        byte[] origFileChecksum = createMD5Checksum(origFile);
                        if (!MessageDigest.isEqual(tempFileChecksum, origFileChecksum)) {
                            logger.debug("Found corrupted archive entry: Checksums don't match");
                            return false;
                        }

                    }
                } catch (NoSuchAlgorithmException e) {
                    logger.error("Unable to check file: Unknown algorithm");
                } finally {
                    if (tempFile != null && tempFile.isFile()) {
                        tempFile.delete();
                    }
                }

            }
            //		} catch (FileNotFoundException e) {
            //			logger.debug("Encountered FileNotFound Exception, probably due to trying to extract a directory. Ignoring");
        } catch (IOException e) {
            logger.debug("Found corrupted archive entry");
            return false;
        } finally {
            try {
                if (in != null)
                    in.close();
                if (zip != null)
                    zip.close();
                if (bis != null)
                    bis.close();
                if (fis != null)
                    fis.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return true;
    }

    /**
     * Returns the MD5-Checksum of a file
     * 
     * @param file
     * @return
     * @throws NoSuchAlgorithmException
     * @throws IOException
     */
    public static byte[] createMD5Checksum(File file) throws NoSuchAlgorithmException, IOException {
        InputStream fis = new FileInputStream(file);

        byte[] buffer = new byte[1024];
        MessageDigest complete = MessageDigest.getInstance("MD5");
        int numRead;
        do {
            numRead = fis.read(buffer);
            if (numRead > 0) {
                complete.update(buffer, 0, numRead);
            }
        } while (numRead != -1);
        fis.close();
        return complete.digest();
    }

    public static String getMD5Checksum(File file) throws NoSuchAlgorithmException, IOException {
        byte[] b = createMD5Checksum(file);
        String result = "";

        for (int i = 0; i < b.length; i++) {
            result += Integer.toString((b[i] & 0xff) + 0x100, 16).substring(1);
        }
        return result;
    }

    public static String convertChecksumToHex(byte[] checksum) {
        String result = "";

        for (int i = 0; i < checksum.length; i++) {
            result += Integer.toString((checksum[i] & 0xff) + 0x100, 16).substring(1);
        }
        return result;
    }

    public static String getRelativePath(File file, File relativeParent) {
        String path = "";

        while (!relativeParent.getAbsolutePath().contentEquals(file.getAbsolutePath())) {
            String filename = file.getName();
            if (!file.isFile()) {
                filename = filename.concat("/");
            }
            path = filename.concat(path);
            file = file.getParentFile();
            if (file == null) {
                break;
            }
        }
        return path;
    }

    public static byte[] zipFiles(File[] sourceFiles, File zipFile) throws IOException {

        MessageDigest checksum = null;

        if (zipFile == null || sourceFiles == null || sourceFiles.length == 0) {
            return null;
        }

        zipFile.getParentFile().mkdirs();

        ZipOutputStream zos = null;
        try {
            FileOutputStream fos = new FileOutputStream(zipFile, true);
            try {
                checksum = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                logger.error("No checksum algorithm \"MD5\". Disabling checksum creation");
                checksum = null;
            }
            zos = new ZipOutputStream(fos);
            for (File file : sourceFiles) {
                logger.debug("Adding file " + file.getAbsolutePath() + " to zipfile " + zipFile.getAbsolutePath());
                zipFile(file, "", zos, checksum);
            }
        } finally {
            if (zos != null) {
                zos.close();
            }
        }

        return checksum.digest();
    }

    private static void zipFile(File file, String path, ZipOutputStream zos, MessageDigest checksum) throws IOException {

        if (file == null) {
            logger.error("Attempting to add nonexisting file to zip archive. Ignoring entry.");
            return;
        }

        if (file.isFile()) {

            FileInputStream fis = new FileInputStream(file);
            BufferedInputStream bis = new BufferedInputStream(fis);

            zos.putNextEntry(new ZipEntry(path + file.getName()));
            int size;
            byte[] buffer = new byte[2048];
            while ((size = bis.read(buffer, 0, buffer.length)) != -1) {
                zos.write(buffer, 0, size);
                if (checksum != null && size > 0) {
                    checksum.update(buffer, 0, size);
                }
            }

            if (zos != null) {
                zos.closeEntry();
            }
            if (bis != null) {
                bis.close();
            }
        } else if (file.isDirectory()) {
            ZipEntry entry = new ZipEntry(path + file.getName() + File.separator);
            zos.putNextEntry(entry);
            if (zos != null && entry != null) {
                zos.closeEntry();
            }
            File[] subfiles = file.listFiles();
            if (subfiles != null && subfiles.length > 0) {
                for (File subFile : subfiles) {
                    zipFile(subFile, path + file.getName() + File.separator, zos, checksum);
                }
            }
        } else {
            logger.warn("File " + file.getAbsolutePath() + " doesn't seem to exist and cannot be added to zip archive.");
        }
    }

    public static boolean validateZip(File zipFile, boolean createTempFile, File origFilesParent, int supposedFiles) {

        File tempFile = null;
        ZipEntry entry = null;
        ZipInputStream in = null;
        int counter = 0;
        try {
            in = new ZipInputStream((new BufferedInputStream(new FileInputStream(zipFile))));


            while ((entry = in.getNextEntry()) != null) {
                counter++;
                File f = new File(entry.getName());
                tempFile = new File(zipFile.getParentFile(), f.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                logger.debug("Testing file " + entry.getName() + " from archive " + zipFile.getName());
                BufferedOutputStream out = null;
                if (createTempFile) {
                    if (!tempFile.isFile()) {
                        tempFile.createNewFile();
                    }
                    out = new BufferedOutputStream(new FileOutputStream(tempFile));
                }

                int size;
                byte[] buffer = new byte[2048];
                while ((size = in.read(buffer, 0, buffer.length)) != -1) {
                    if (createTempFile) {
                        out.write(buffer, 0, size);
                    }
                }
                if (out != null) {
                    out.flush();
                    out.close();
                }

                if (createTempFile && (tempFile == null || !tempFile.isFile())) {
                    logger.debug("Found corrupted archive entry: Unable to create file");
                    return false;
                }

                //check checksum of file
                try {
                    if (createTempFile && origFilesParent != null) {
                        File origFile = new File(origFilesParent, entry.getName());
                        if (origFile == null || !origFile.isFile()) {
                            logger.debug("Unable to find orig file for entry " + entry.getName());
                            continue;
                        }
                        logger.debug("Testing entry against original file " + origFile.getAbsolutePath());
                        byte[] tempFileChecksum = createChecksum(tempFile);
                        byte[] origFileChecksum = createChecksum(origFile);
                        if (!MessageDigest.isEqual(tempFileChecksum, origFileChecksum)) {
                            logger.debug("Found corrupted archive entry: Checksums don't match");
                            return false;
                        }

                    }
                } catch (NoSuchAlgorithmException e) {
                    logger.error("Unable to check file: Unknown algorithm");
                } finally {
                    if (tempFile != null && tempFile.isFile()) {
                        tempFile.delete();
                    }
                }
            }
            //      } catch (FileNotFoundException e) {
            //          logger.debug("Encountered FileNotFound Exception, probably due to trying to extract a directory. Ignoring");
        } catch (IOException e) {
            logger.debug("Found corrupted archive entry");
            return false;
        } finally {
            try {
                if (in != null)
                    in.close();
            } catch (IOException e) {
                logger.debug("Cannot close archive entry");
                return false;
            }
        }

        if (counter == supposedFiles) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Creates the MD5-checksum as byte-Array of the given file
     * 
     * @param filename
     * @return
     * @throws NoSuchAlgorithmException
     * @throws IOException
     * @throws Exception
     */
    public static byte[] createChecksum(File file) throws NoSuchAlgorithmException, IOException {
        InputStream fis = new FileInputStream(file);

        byte[] buffer = new byte[1024];
        MessageDigest complete = MessageDigest.getInstance("MD5");
        int numRead;
        do {
            numRead = fis.read(buffer);
            if (numRead > 0) {
                complete.update(buffer, 0, numRead);
            }
        } while (numRead != -1);
        fis.close();
        return complete.digest();
    }

    /**
     * Copies the content of file source to file dest
     * 
     * @param source
     * @param dest
     * @throws IOException
     */
    public static void copyFile(File source, File dest) throws IOException {

        if (!dest.exists()) {
            dest.createNewFile();
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(dest);

            // Transfer bytes from in to out
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } finally {
            in.close();
            out.close();
        }
    }

}
