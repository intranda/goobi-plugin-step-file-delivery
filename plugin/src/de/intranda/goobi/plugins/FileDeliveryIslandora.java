package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
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
import de.sub.goobi.helper.HttpClientHelper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
public class FileDeliveryIslandora implements IStepPlugin, IPlugin {
    private static final Logger logger = Logger.getLogger(FileDeliveryIslandora.class);

    private String pluginname = "FileDeliveryIslandora";
    // private Schritt step;
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
        // this.step = step;
        this.process = step.getProzess();
        this.returnPath = returnPath;
    }

    @Override
    public boolean execute() {
        String mailAddress = "";
        String format = "";
        for (Processproperty pe : process.getEigenschaftenList()) {
            if (pe.getTitel().equalsIgnoreCase("email")) {
                mailAddress = pe.getWert();
            } else if (pe.getTitel().equalsIgnoreCase("format")) {
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

        if (format.equalsIgnoreCase("PDF")) {

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

                    String contentServerUrl = "http://localhost:8080/goobi/api/process/pdf/" + process.getId() + "/full.pdf?resolution=150&convertToGrayscale&targetFileName=" + process.getTitel() + ".pdf";

                    URL goobiContentServerUrl = new URL(contentServerUrl);
                    OutputStream fos = new FileOutputStream(deliveryFile);

                    HttpClientHelper.getStreamFromUrl(fos, goobiContentServerUrl.toString());

                    fos.close();

                }

            } catch (SwapException e1) {
                logger.error(process.getTitel() + ": " + e1);
            } catch (IOException e1) {
                logger.error(process.getTitel() + ": " + e1);
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

        logger.debug("Found " + filenames.size() + " files.");

        byte[] origArchiveChecksum = null;
        try {
            origArchiveChecksum = ArchiveUtils.zipFiles(filenames, compressedFile);
        } catch (IOException e) {
            createMessages("Failed to zip files to archive for " + process.getTitel() + ". Aborting.", e, compressedFile);
            return false;
        }

        logger.info("Validating zip-archive");
        byte[] origArchiveAfterZipChecksum = null;
        try {
            origArchiveAfterZipChecksum = ArchiveUtils.createChecksum(compressedFile);
        } catch (NoSuchAlgorithmException e) {
            createMessages("Failed to zip files to archive for " + process.getTitel() + ". Aborting.", e, compressedFile);

            return false;
        } catch (IOException e) {
            createMessages("Failed to zip files to archive for " + process.getTitel() + ". Aborting.", e, compressedFile);

            return false;
        }

        if (ArchiveUtils.validateZip(compressedFile, true, imageFolder, filenames.size())) {
            logger.info("Zip archive for " + process.getTitel() + " is valid");
        } else {
            createMessages("Failed to zip files to archive for " + process.getTitel() + ". Aborting.", null, compressedFile);
            return false;
        }
        // ////////Done validating archive

        // ////////copying archive file and validating copy
        logger.info("Copying zip archive for " + process.getTitel() + " to archive");
        try {
            ArchiveUtils.copyFile(compressedFile, destFile);
            // validation
            if (!MessageDigest.isEqual(origArchiveAfterZipChecksum, ArchiveUtils.createChecksum(destFile))) {
                createMessages(process.getTitel() + ": " + "Error copying archive file to archive: Copy is not valid. Aborting.", null,
                        compressedFile);
                return false;
            }
        } catch (IOException e) {
            createMessages(process.getTitel() + ": " + "Error validating copied archive. Aborting.", e, compressedFile);

            return false;
        } catch (NoSuchAlgorithmException e) {
            createMessages(process.getTitel() + ": " + "Error validating copied archive. Aborting.", e, compressedFile);
            return false;
        }
        logger.info("Zip archive copied to " + destFile.getAbsolutePath() + " and found to be valid.");

        //
        //        // - an anderen Ort kopieren
        //        String destination = ConfigPlugins.getPluginConfig(this).getString("destinationFolder", "/opt/digiverso/pdfexport/");
        String downloadServer = ConfigPlugins.getPluginConfig(this).getString("downloadServer", "http://leiden01.intranda.com/goobi/");
        String downloadUrl = downloadServer + destFile.getName();

        // - Name/Link als Property speichern

        boolean matched = false;
        for (Processproperty pe : process.getEigenschaftenList()) {
            if (pe.getTitel().equals(PROPERTYTITLE)) {
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

        String[] mail = { mailAddress };
        try {
            postMail(mail, downloadUrl);
        } catch (UnsupportedEncodingException e) {
            createMessages("PluginErrorMailError", e, compressedFile);
            return false;
        } catch (MessagingException e) {
            createMessages("PluginErrorMailError", e, compressedFile);
            return false;
        }
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
            logger.error(message, e);
            Helper.setFehlerMeldung(message, e);
        } else {
            Helper.setFehlerMeldung(message);
            logger.error(message);
        }
        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message, "automatic");

        if (tempFile != null && tempFile.exists()) {
            FileUtils.deleteQuietly(tempFile);
        }

    }

    public void postMail(String recipients[], String downloadUrl) throws MessagingException, UnsupportedEncodingException {

        @SuppressWarnings("deprecation")
        XMLConfiguration config = ConfigPlugins.getPluginConfig(this);

        String SMTP_SERVER = config.getString("SMTP_SERVER", "mail.intranda.com");
        String SMTP_USER = config.getString("SMTP_USER", "TODO");
        String SMTP_PASSWORD = config.getString("SMTP_PASSWORD", "TODO");
        String SMTP_USE_STARTTLS = config.getString("SMTP_USE_STARTTLS", "0");
        String SMTP_USE_SSL = config.getString("SMTP_USE_SSL", "1");
        String SENDER_ADDRESS = config.getString("SENDER_ADDRESS", "TODO");

        String MAIL_SUBJECT = config.getString("MAIL_SUBJECT", "Leiden University – Digitisation Order Special Collections University Library");
        String MAIL_TEXT = config.getString("MAIL_BODY", "{0}");
        MAIL_TEXT = MAIL_TEXT.replace("{0}", downloadUrl);

        // Set the host smtp address
        Properties props = new Properties();
        if ((SMTP_USE_STARTTLS != null) && SMTP_USE_STARTTLS.equals("1")) {
            props.setProperty("mail.transport.protocol", "smtp");
            props.setProperty("mail.smtp.auth", "true");
            props.setProperty("mail.smtp.port", "25");
            props.setProperty("mail.smtp.host", SMTP_SERVER);
            props.setProperty("mail.smtp.ssl.trust", "*");
            props.setProperty("mail.smtp.starttls.enable", "true");
            props.setProperty("mail.smtp.starttls.required", "true");
        } else if ((SMTP_USE_SSL != null) && SMTP_USE_SSL.equals("1")) {
            props.setProperty("mail.transport.protocol", "smtp");
            props.setProperty("mail.smtp.host", SMTP_SERVER);
            props.setProperty("mail.smtp.auth", "true");
            props.setProperty("mail.smtp.port", "465");
            props.setProperty("mail.smtp.ssl.enable", "true");
            props.setProperty("mail.smtp.ssl.trust", "*");

        } else {
            props.setProperty("mail.transport.protocol", "smtp");
            props.setProperty("mail.smtp.auth", "true");
            props.setProperty("mail.smtp.port", "25");
            props.setProperty("mail.smtp.host", SMTP_SERVER);
        }

        Session session = Session.getDefaultInstance(props, null);
        Message msg = new MimeMessage(session);

        InternetAddress addressFrom = new InternetAddress(SENDER_ADDRESS);
        msg.setFrom(addressFrom);
        InternetAddress[] addressTo = new InternetAddress[recipients.length];
        for (int i = 0; i < recipients.length; i++) {
            addressTo[i] = new InternetAddress(recipients[i]);
        }
        msg.setRecipients(Message.RecipientType.TO, addressTo);

        // create mail
        MimeMultipart multipart = new MimeMultipart();

        msg.setSubject(MAIL_SUBJECT);
        MimeBodyPart messageHtmlPart = new MimeBodyPart();
        messageHtmlPart.setText(MAIL_TEXT, "utf-8");
        messageHtmlPart.setHeader("Content-Type", "text/html; charset=\"utf-8\"");
        multipart.addBodyPart(messageHtmlPart);

        msg.setContent(multipart);
        msg.setSentDate(new Date());

        Transport transport = session.getTransport();
        transport.connect(SMTP_USER, SMTP_PASSWORD);
        transport.sendMessage(msg, msg.getRecipients(Message.RecipientType.TO));
        transport.close();
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
