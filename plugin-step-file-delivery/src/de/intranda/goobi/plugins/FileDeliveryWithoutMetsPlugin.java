package de.intranda.goobi.plugins;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
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

import net.xeoh.plugins.base.annotations.PluginImplementation;

import org.apache.log4j.Logger;
import org.goobi.production.cli.helper.WikiFieldHelper;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;

import de.intranda.goobi.plugins.utils.ArchiveUtils;

import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.HttpClientHelper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.encryption.MD5;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;

@PluginImplementation
public class FileDeliveryWithoutMetsPlugin implements IStepPlugin, IPlugin {
    private static final Logger logger = Logger.getLogger(FileDeliveryWithoutMetsPlugin.class);

    private String pluginname = "FileDeliveryWithoutMets";
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
            createMessages(Helper.getTranslation(process.getTitel() + ": delivery failed, email address is missing or empty."), null);

            return false;
        }

        if (format == null || format.isEmpty()) {
            createMessages(Helper.getTranslation(process.getTitel() + ": delivery failed, format is missing or empty."), null);
            return false;
        }

        File deliveryFile = null;
        MD5 md5 = new MD5(process.getTitel());
        String imagesFolderName = "";

        if (format.equalsIgnoreCase("PDF")) {

           
            try {
                imagesFolderName = process.getImagesDirectory() + "pimped_pdf";
                File pdffolder = new File(imagesFolderName);
                if (!pdffolder.exists() && !pdffolder.mkdir()) {
                    createMessages(Helper.getTranslation(process.getTitel() + ": delivery failed, pdf folder is missing."), null);
                    return false;
                }
                File[] listOfFiles = pdffolder.listFiles(pdffilter);

                if (listOfFiles == null || listOfFiles.length == 0) {
                    deliveryFile = new File(pdffolder, process.getTitel() + ".pdf");

                    // - PDF erzeugen

                    String contentServerUrl =
                            "http://localhost:8080/goobi" + "/cs/cs?action=pdf&resolution=150&convertToGrayscale&folder="
                                    + process.getImagesTifDirectory(true) + "&targetFileName=" + process.getTitel() + ".pdf";

                    URL goobiContentServerUrl = new URL(contentServerUrl);
                    OutputStream fos = new FileOutputStream(deliveryFile);

                    HttpClientHelper.getStreamFromUrl(fos, goobiContentServerUrl.toString());

                    fos.close();
                    
                    
                }

            } catch (SwapException e1) {
                logger.error(process.getTitel() + ": " + e1);
            } catch (DAOException e1) {
                logger.error(process.getTitel() + ": " + e1);
            } catch (IOException e1) {
                logger.error(process.getTitel() + ": " + e1);
            } catch (InterruptedException e1) {
                logger.error(process.getTitel() + ": " + e1);
            }

        } else {
            try {
                imagesFolderName = process.getImagesTifDirectory(false);
            } catch (SwapException e) {
                createMessages(process.getTitel() + ": " + Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (DAOException e) {
                createMessages(process.getTitel() + ": " + Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (IOException e) {
                createMessages(process.getTitel() + ": " + Helper.getTranslation("PluginErrorIOError"), e);
                return false;
            } catch (InterruptedException e) {
                createMessages(process.getTitel() + ": " + Helper.getTranslation("PluginErrorIOError"), e);
                return false;
            }
        }

        File compressedFile =
                new File(ConfigurationHelper.getInstance().getTemporaryFolder() + System.currentTimeMillis() + md5.getMD5() + "_"
                        + process.getTitel() + ".zip");

        List<Path> filenames = NIOFileUtils.listFiles(imagesFolderName, NIOFileUtils.DATA_FILTER);
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
            logger.error("Failed to zip files to archive for " + process.getTitel() + ". Aborting.");
            return false;
        }

        logger.info("Validating zip-archive");
        byte[] origArchiveAfterZipChecksum = null;
        try {
            origArchiveAfterZipChecksum = ArchiveUtils.createChecksum(compressedFile);
        } catch (NoSuchAlgorithmException e) {
            logger.error(process.getTitel() + ": " + "Failed to validate zip archive: " + e.toString() + ". Aborting.");
            return false;
        } catch (IOException e) {
            logger.error(process.getTitel() + ": " + "Failed to validate zip archive: " + e.toString() + ". Aborting.");
            return false;
        }

        if (ArchiveUtils.validateZip(compressedFile, true, imageFolder, filenames.size())) {
            logger.info("Zip archive for " + process.getTitel() + " is valid");
        } else {
            logger.error(process.getTitel() + ": " + "Zip archive for " + process.getTitel() + " is corrupted. Aborting.");
            return false;
        }
        // ////////Done validating archive

        // ////////copying archive file and validating copy
        logger.info("Copying zip archive for " + process.getTitel() + " to archive");
        try {
            ArchiveUtils.copyFile(compressedFile, destFile);
            // validation
            if (!MessageDigest.isEqual(origArchiveAfterZipChecksum, ArchiveUtils.createChecksum(destFile))) {
                logger.error(process.getTitel() + ": " + "Error copying archive file to archive: Copy is not valid. Aborting.");
                return false;
            }
        } catch (IOException e) {
            logger.error(process.getTitel() + ": " + "Error validating copied archive. Aborting.");
            return false;
        } catch (NoSuchAlgorithmException e) {
            logger.error(process.getTitel() + ": " + "Error validating copied archive. Aborting.");
            return false;
        }
        logger.info("Zip archive copied to " + destFile.getAbsolutePath() + " and found to be valid.");

        //
        //        // - an anderen Ort kopieren
        //        String destination = ConfigPlugins.getPluginConfig(this).getString("destinationFolder", "/opt/digiverso/pdfexport/");
        String donwloadServer = ConfigPlugins.getPluginConfig(this).getString("donwloadServer", "http://leiden01.intranda.com/goobi/");
        String downloadUrl = donwloadServer + destFile.getName();

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
            createMessages(process.getTitel() + ": " + Helper.getTranslation("fehlerNichtSpeicherbar"), e);
            return false;
        }

        // - mail versenden

        String[] mail = { mailAddress };
        try {
            postMail(mail, downloadUrl);
        } catch (UnsupportedEncodingException e) {
            createMessages("PluginErrorMailError", e);
            return false;
        } catch (MessagingException e) {
            createMessages("PluginErrorMailError", e);
            return false;
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

    private void createMessages(String message, Exception e) {
        if (e != null) {
            Helper.setFehlerMeldung(message, e);
            ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(process.getWikifield(), "error", message), process.getId());
            logger.error(message, e);
        } else {
            Helper.setFehlerMeldung(message);
            ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(process.getWikifield(), "error", message), process.getId());
            logger.error(message);
        }

    }

    public void postMail(String recipients[], String downloadUrl) throws MessagingException, UnsupportedEncodingException {
        boolean debug = false;

        String SMTP_SERVER = ConfigPlugins.getPluginConfig(this).getString("SMTP_SERVER", "mail.intranda.com");
        String SMTP_USER = ConfigPlugins.getPluginConfig(this).getString("SMTP_USER", "TODO");
        String SMTP_PASSWORD = ConfigPlugins.getPluginConfig(this).getString("SMTP_PASSWORD", "TODO");
        String SMTP_USE_STARTTLS = ConfigPlugins.getPluginConfig(this).getString("SMTP_USE_STARTTLS", "0");
        String SMTP_USE_SSL = ConfigPlugins.getPluginConfig(this).getString("SMTP_USE_SSL", "1");
        String SENDER_ADDRESS = ConfigPlugins.getPluginConfig(this).getString("SENDER_ADDRESS", "TODO");

        String MAIL_SUBJECT =
                ConfigPlugins.getPluginConfig(this).getString("MAIL_SUBJECT",
                        "Leiden University – Digitisation Order Special Collections University Library");
        String MAIL_TEXT = ConfigPlugins.getPluginConfig(this).getString("MAIL_BODY", "{0}");
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
        session.setDebug(debug);
        Message msg = new MimeMessage(session);

        InternetAddress addressFrom = new InternetAddress(SENDER_ADDRESS);
        msg.setFrom(addressFrom);
        InternetAddress[] addressTo = new InternetAddress[recipients.length];
        for (int i = 0; i < recipients.length; i++) {
            addressTo[i] = new InternetAddress(recipients[i]);
        }
        msg.setRecipients(Message.RecipientType.TO, addressTo);

        // Optional : You can also set your custom headers in the Email if you
        // Want
        // msg.addHeader("MyHeaderName", "myHeaderValue");

        msg.setSubject(MAIL_SUBJECT);

        MimeBodyPart messagePart = new MimeBodyPart();
        messagePart.setText(MAIL_TEXT, "utf-8");
        messagePart.setHeader("Content-Type", "text/plain; charset=\"utf-8\"");
        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(messagePart);

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
