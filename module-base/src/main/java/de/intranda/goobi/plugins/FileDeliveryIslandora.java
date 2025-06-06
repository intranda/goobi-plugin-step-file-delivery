package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.goobi.api.mail.SendMail;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;

import de.intranda.goobi.plugins.utils.ArchiveUtils;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import io.goobi.workflow.api.connection.HttpUtils;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@Log4j2
@PluginImplementation
public class FileDeliveryIslandora implements IStepPlugin, IPlugin {

    private static final long serialVersionUID = -7680783972841493378L;

    private String pluginname = "FileDeliveryIslandora";
    private Step step;
    private Process process;
    private String returnPath;

    private static final String PROPERTYTITLE = "PDFURL";

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String getTitle() {
        return pluginname;
    }

    public String getDescription() {
        return pluginname;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.process = step.getProzess();
        this.returnPath = returnPath;
    }

    @Override
    public boolean execute() {
        String mailAddress = "";
        String format = "";
        for (GoobiProperty pe : process.getEigenschaftenList()) {
            if ("email".equalsIgnoreCase(pe.getTitel())) {
                mailAddress = pe.getWert();
            } else if ("format".equalsIgnoreCase(pe.getTitel())) {
                format = pe.getWert();
            }
        }
        if (mailAddress == null || mailAddress.length() == 0) {
            createMessages(Helper.getTranslation(process.getTitel() + ": delivery failed, email address is missing or empty."), null, null);

            return false;
        }

        if (format == null || format.isEmpty()) {
            createMessages(Helper.getTranslation(process.getTitel() + ": delivery failed, format is missing or empty."), null, null);
            return false;
        }

        File deliveryFile = null;
        String imagesFolderName = "";

        if ("PDF".equalsIgnoreCase(format)) {

            try {
                imagesFolderName = process.getImagesDirectory() + "customer_tif";
                File pdffolder = new File(imagesFolderName);
                if (!pdffolder.exists() && !pdffolder.mkdir()) {
                    createMessages(Helper.getTranslation(process.getTitel() + ": delivery failed, pdf folder is missing."), null, null);
                    return false;
                }
                File[] listOfFiles = pdffolder.listFiles(pdffilter);

                if (listOfFiles == null || listOfFiles.length == 0) {
                    deliveryFile = new File(pdffolder, process.getTitel() + ".pdf");

                    // - PDF erzeugen

                    String contentServerUrl = "http://localhost:8080/goobi/api/process/pdf/" + process.getId()
                    + "/full.pdf?resolution=150&convertToGrayscale&imageSource=file://" + imagesFolderName + "&targetFileName="
                    + process.getTitel() + ".pdf";

                    URL goobiContentServerUrl = new URL(contentServerUrl);
                    OutputStream fos = new FileOutputStream(deliveryFile);

                    HttpUtils.getStreamFromUrl(fos, goobiContentServerUrl.toString());

                    fos.close();

                }

            } catch (SwapException | IOException e1) {
                log.error(process.getTitel() + ": " + e1);
            }

        } else {
            try {
                imagesFolderName = process.getImagesDirectory() + "customer_tif";
            } catch (SwapException e) {
                createMessages(process.getTitel() + ": " + Helper.getTranslation("PluginErrorInvalidMetadata"), e, null);
                return false;
            } catch (IOException e) {
                createMessages(process.getTitel() + ": " + Helper.getTranslation("PluginErrorIOError"), e, null);
                return false;
            }
        }

        File compressedFile =
                new File(ConfigurationHelper.getInstance().getTemporaryFolder() + System.currentTimeMillis() + "_" + process.getTitel() + ".zip");

        List<Path> filenames = StorageProvider.getInstance().listFiles(imagesFolderName, NIOFileUtils.DATA_FILTER);
        File imageFolder = new File(imagesFolderName);
        //        File[] filenames = imageFolder.listFiles(Helper.dataFilter);
        //        if ((filenames == null) || (filenames.length == 0)) {
        //            return false;
        //        }
        File destFile =
                new File(ConfigPlugins.getPluginConfig(this).getString("destinationFolder", "/opt/digiverso/pdfexport/"), compressedFile.getName());

        log.debug("Found " + filenames.size() + " files.");

        byte[] origArchiveChecksum = null;
        try {
            origArchiveChecksum = ArchiveUtils.zipFiles(filenames, compressedFile);
        } catch (IOException e) {
            createMessages("Failed to zip files to archive for " + process.getTitel() + ". Aborting.", e, compressedFile);
            return false;
        }

        log.info("Validating zip-archive");
        byte[] origArchiveAfterZipChecksum = null;
        try {
            origArchiveAfterZipChecksum = ArchiveUtils.createChecksum(compressedFile);
        } catch (NoSuchAlgorithmException | IOException e) {
            createMessages("Failed to zip files to archive for " + process.getTitel() + ". Aborting.", e, compressedFile);

            return false;
        }

        if (ArchiveUtils.validateZip(compressedFile, true, imageFolder, filenames.size())) {
            log.info("Zip archive for " + process.getTitel() + " is valid");
        } else {
            createMessages("Failed to zip files to archive for " + process.getTitel() + ". Aborting.", null, compressedFile);
            return false;
        }
        // ////////Done validating archive

        // ////////copying archive file and validating copy
        log.info("Copying zip archive for " + process.getTitel() + " to archive");
        try {
            ArchiveUtils.copyFile(compressedFile, destFile);
            // validation
            if (!MessageDigest.isEqual(origArchiveAfterZipChecksum, ArchiveUtils.createChecksum(destFile))) {
                createMessages(process.getTitel() + ": " + "Error copying archive file to archive: Copy is not valid. Aborting.", null,
                        compressedFile);
                return false;
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            createMessages(process.getTitel() + ": " + "Error validating copied archive. Aborting.", e, compressedFile);
            return false;
        }
        log.info("Zip archive copied to " + destFile.getAbsolutePath() + " and found to be valid.");

        //
        //        // - an anderen Ort kopieren
        //        String destination = ConfigPlugins.getPluginConfig(this).getString("destinationFolder", "/opt/digiverso/pdfexport/");
        String downloadServer = ConfigPlugins.getPluginConfig(this).getString("downloadServer", "http://leiden01.intranda.com/goobi/");
        String downloadUrl = downloadServer + destFile.getName();

        // - Name/Link als Property speichern

        boolean matched = false;
        for (GoobiProperty pe : process.getEigenschaftenList()) {
            if (PROPERTYTITLE.equals(pe.getTitel())) {
                pe.setWert(downloadUrl);
                matched = true;
                break;
            }
        }

        if (!matched) {
            Processproperty pe = new Processproperty();
            pe.setTitel(PROPERTYTITLE);
            pe.setWert(downloadUrl);
            process.getEigenschaften().add(pe);
            pe.setProzess(process);
        }

        try {
            ProcessManager.saveProcess(process);
        } catch (DAOException e) {
            createMessages(process.getTitel() + ": " + Helper.getTranslation("fehlerNichtSpeicherbar"), e, compressedFile);
            return false;
        }

        // - mail versenden
        postMail(mailAddress, downloadUrl);

        if (compressedFile != null && compressedFile.exists()) {
            FileUtils.deleteQuietly(compressedFile);
        }
        return true;
    }

    @Override
    public String cancel() {
        return returnPath;

    }

    @Override
    public String finish() {
        return returnPath;

    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public Step getStep() {
        return null;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    private void createMessages(String message, Exception e, File tempFile) {
        if (e != null) {
            log.error(message, e);
            Helper.setFehlerMeldung(message, e);
        } else {
            Helper.setFehlerMeldung(message);
            log.error(message);
        }
        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message, "automatic");

        if (tempFile != null && tempFile.exists()) {
            FileUtils.deleteQuietly(tempFile);
        }

    }

    public void postMail(String recipient, String downloadUrl)  {

        @SuppressWarnings("deprecation")
        XMLConfiguration config = ConfigPlugins.getPluginConfig(this);

        String subject = config.getString("MAIL_SUBJECT", "Leiden University – Digitisation Order Special Collections University Library");
        String mailBody = config.getString("MAIL_BODY", "{0}");
        mailBody = mailBody.replace("{0}", downloadUrl);

        VariableReplacer replacer = new VariableReplacer(null, null, process, step);
        mailBody = replacer.replace(mailBody);

        SendMail.getInstance().sendMailToUser(mailBody, subject, recipient);

    }

    private static FilenameFilter tiffilter = new FilenameFilter() {

        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".tif") || name.endsWith(".TIF");
        }
    };

    private static FilenameFilter pdffilter = new FilenameFilter() {

        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".pdf") || name.endsWith(".PDF");
        }
    };

    @Override
    public String getPagePath() {
        return null;
    }

}
