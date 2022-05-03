package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
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

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.goobi.beans.LogEntry;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;

import de.schlichtherle.io.DefaultArchiveDetector;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.HttpClientHelper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.encryption.MD5;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.metadaten.MetadatenVerifizierung;
import de.sub.goobi.persistence.managers.ProcessManager;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.exceptions.DocStructHasNoTypeException;

@PluginImplementation
public class FileDeliveryWithMetsPlugin implements IStepPlugin, IPlugin {
    private static final Logger logger = Logger.getLogger(FileDeliveryWithMetsPlugin.class);

    private String pluginname = "FileDeliveryWithMets";
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
        if ((mailAddress == null) || (mailAddress.length() == 0) || (format == null) || (format.length() == 0)) {
            return false;
        }

        File deliveryFile = null;
        MD5 md5 = new MD5(process.getTitel());
        if (format.equalsIgnoreCase("PDF")) {

            // TODO sicherstellen das filegroup PDF erzeugt und in im gcs für pdf eingestellt wurde
            MetadatenVerifizierung mv = new MetadatenVerifizierung();
            if (!mv.validate(process)) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), null);
                return false;
            }
            String tempfolder = ConfigurationHelper.getInstance().getTemporaryFolder();

            // - umbenennen in unique Namen
            deliveryFile = new File(tempfolder, System.currentTimeMillis() + md5.getMD5() + "_" + process.getTitel() + ".pdf");
            String metsfile = tempfolder + process.getTitel() + "_mets.xml";
            // - PDF erzeugen

            try {
                URL goobiContentServerUrl = null;
                String contentServerUrl = ConfigPlugins.getPluginConfig(this).getString("contentServerUrl");

                if (contentServerUrl == null || contentServerUrl.length() == 0) {
                    contentServerUrl = "http://localhost:8080/goobi" + "/cs/cs?action=pdf&images=";
                }
                String url = "";
                //                FilenameFilter filter = tiffilter;
                File imagesDir = new File(process.getImagesTifDirectory(true));
                File[] meta = imagesDir.listFiles(tiffilter);
                ArrayList<String> filenames = new ArrayList<>();
                for (File data : meta) {
                    String file = "";
                    file += data.toURI().toURL();
                    filenames.add(file);
                }
                Collections.sort(filenames);
                for (String f : filenames) {
                    url = url + f + "$";
                }
                String imageString = url.substring(0, url.length() - 1);
                String targetFileName = "&targetFileName=" + process.getTitel() + ".pdf";
                goobiContentServerUrl = new URL(contentServerUrl + imageString + targetFileName);

                OutputStream fos = new FileOutputStream(deliveryFile);

                HttpClientHelper.getStreamFromUrl(fos, goobiContentServerUrl.toString());

                fos.close();

                FileUtils.deleteQuietly(new File(metsfile));

            } catch (DocStructHasNoTypeException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (SwapException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (DAOException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (IOException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (InterruptedException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            }
        } else {
            de.schlichtherle.io.File.setDefaultArchiveDetector(new DefaultArchiveDetector("tar.bz2|tar.gz|zip"));
            de.schlichtherle.io.File zipFile = new de.schlichtherle.io.File(ConfigurationHelper.getInstance().getTemporaryFolder() + System
                    .currentTimeMillis() + md5.getMD5() + "_" + process.getTitel() + ".zip");
            try {

                String imagesFolderName = process.getImagesTifDirectory(false);
                de.schlichtherle.io.File imageFolder = new de.schlichtherle.io.File(imagesFolderName);
                if (!imageFolder.exists() || !imageFolder.isDirectory()) {
                    return false;
                }
                List<String> filenames = StorageProvider.getInstance().list(imagesFolderName, NIOFileUtils.DATA_FILTER);
                //                String[] filenames = imageFolder.list(Helper.dataFilter);
                //                if ((filenames == null) || (filenames.length == 0)) {
                //                    return false;
                //                }

                List<de.schlichtherle.io.File> images = new ArrayList<>();
                for (String imagefileName : filenames) {
                    de.schlichtherle.io.File imagefile = new de.schlichtherle.io.File(imageFolder, imagefileName);
                    images.add(new de.schlichtherle.io.File(imagefile));
                }

                for (de.schlichtherle.io.File image : images) {
                    image.copyTo(new de.schlichtherle.io.File(zipFile + java.io.File.separator + image.getName()));
                }
                zipFile.createNewFile();
                de.schlichtherle.io.File.umount();

                deliveryFile = new File(zipFile.getAbsolutePath());

            } catch (SwapException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (DAOException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (IOException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (InterruptedException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            }

        }

        // - an anderen Ort kopieren
        String destination = ConfigPlugins.getPluginConfig(this).getString("destinationFolder", "/opt/digiverso/pdfexport/");
        String downloadServer = ConfigPlugins.getPluginConfig(this).getString("downloadServer", "http://leiden01.intranda.com/goobi/");
        String downloadUrl = downloadServer + deliveryFile.getName();
        try {
            FileUtils.copyFileToDirectory(deliveryFile, new File(destination));
            FileUtils.deleteQuietly(deliveryFile);
        } catch (IOException e) {
            createMessages(Helper.getTranslation("PluginErrorIOError"), e);
            return false;
        }

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
            createMessages(Helper.getTranslation("fehlerNichtSpeicherbar"), e);
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
            logger.error(message, e);
            Helper.setFehlerMeldung(message, e);
        } else {
            Helper.setFehlerMeldung(message);
            logger.error(message);
        }

        LogEntry logEntry = new LogEntry();
        logEntry.setContent(message);
        logEntry.setCreationDate(new Date());
        logEntry.setProcessId(process.getId());
        logEntry.setType(LogType.ERROR);
        logEntry.setUserName("webapi");
        ProcessManager.saveLogEntry(logEntry);
    }

    public void postMail(String recipients[], String downloadUrl) throws MessagingException, UnsupportedEncodingException {
        boolean debug = false;

        String SMTP_SERVER = ConfigPlugins.getPluginConfig(this).getString("SMTP_SERVER", "mail.intranda.com");
        String SMTP_USER = ConfigPlugins.getPluginConfig(this).getString("SMTP_USER", "TODO");
        String SMTP_PASSWORD = ConfigPlugins.getPluginConfig(this).getString("SMTP_PASSWORD", "TODO");
        String SMTP_USE_STARTTLS = ConfigPlugins.getPluginConfig(this).getString("SMTP_USE_STARTTLS", "0");
        String SMTP_USE_SSL = ConfigPlugins.getPluginConfig(this).getString("SMTP_USE_SSL", "1");
        String SENDER_ADDRESS = ConfigPlugins.getPluginConfig(this).getString("SENDER_ADDRESS", "TODO");

        String MAIL_SUBJECT = ConfigPlugins.getPluginConfig(this).getString("MAIL_SUBJECT",
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

    @Override
    public String getPagePath() {
        return null;
    }

}
