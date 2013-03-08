package de.intranda.goobi.plugins;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;
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

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.goobi.production.cli.helper.WikiFieldHelper;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;

import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import de.schlichtherle.io.DefaultArchiveDetector;
import de.sub.goobi.Beans.Prozess;
import de.sub.goobi.Beans.Prozesseigenschaft;
import de.sub.goobi.Beans.Schritt;
import de.sub.goobi.Export.download.ExportMets;
import de.sub.goobi.Metadaten.MetadatenVerifizierungWithoutHibernate;
import de.sub.goobi.Persistence.ProzessDAO;
import de.sub.goobi.Persistence.apache.ProcessManager;
import de.sub.goobi.Persistence.apache.StepObject;
import de.sub.goobi.config.ConfigMain;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.encryption.MD5;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;

@PluginImplementation
public class FileDeliveryPlugin implements IStepPlugin, IPlugin {
    private static final Logger logger = Logger.getLogger(FileDeliveryPlugin.class);

    private String pluginname = "FileDelivery";
    // private Schritt step;
    private Prozess process;
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

    @Override
    public String getDescription() {
        return pluginname;
    }

    @Override
    public void initialize(Schritt step, String returnPath) {
        // this.step = step;
        this.process = step.getProzess();
        this.returnPath = returnPath;
    }

    @Override
    public void initialize(StepObject stepobject, String returnPath) {
        try {
            // this.step = new SchrittDAO().get(stepobject.getId());
            this.process = new ProzessDAO().get(stepobject.getProcessId());
        } catch (DAOException e) {

        }
        this.returnPath = returnPath;
    }

    @Override
    public boolean execute() {
        String mailAddress = "";
        String format = "";
        for (Prozesseigenschaft pe : process.getEigenschaftenList()) {
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
            MetadatenVerifizierungWithoutHibernate mv = new MetadatenVerifizierungWithoutHibernate();
            if (!mv.validate(process)) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), null);
                return false;
            }
            String tempfolder = ConfigMain.getParameter("tempfolder", "/opt/digiverso/goobi/temp/");

            // - umbenennen in unique Namen
            deliveryFile = new File(tempfolder, System.currentTimeMillis() + md5.getMD5() + "_" + process.getTitel() + ".pdf");
            String metsfile = tempfolder + process.getTitel() + "_mets.xml";
            // - PDF erzeugen
            GetMethod method = null;
            try {
                ExportMets em = new ExportMets();

                em.startExport(process, tempfolder);

                URL goobiContentServerUrl = null;
                String contentServerUrl = ConfigMain.getParameter("goobiContentServerUrl");

                Integer contentServerTimeOut = ConfigMain.getIntParameter("goobiContentServerTimeOut", 60000);
                if ((contentServerUrl == null) || (contentServerUrl.length() == 0)) {
                    contentServerUrl = ConfigPlugins.getPluginConfig(this).getString("contentServerUrl");
                    ;
                }
                goobiContentServerUrl = new URL(contentServerUrl + metsfile);

                HttpClient httpclient = new HttpClient();
                logger.debug("Retrieving: " + goobiContentServerUrl.toString());
                method = new GetMethod(goobiContentServerUrl.toString());

                method.getParams().setParameter("http.socket.timeout", contentServerTimeOut);
                int statusCode = httpclient.executeMethod(method);
                if (statusCode != HttpStatus.SC_OK) {
                    logger.error("HttpStatus nicht ok", null);
                    createMessages(Helper.getTranslation("PluginErrorPDFCreationError"), null);
                    return false;
                }

                InputStream inStream = method.getResponseBodyAsStream();
                BufferedInputStream bis = new BufferedInputStream(inStream);
                FileOutputStream fos = new FileOutputStream(deliveryFile);
                byte[] bytes = new byte[8192];
                int count = bis.read(bytes);
                while ((count != -1) && (count <= 8192)) {
                    fos.write(bytes, 0, count);
                    count = bis.read(bytes);
                }
                if (count != -1) {
                    fos.write(bytes, 0, count);
                }
                fos.close();
                bis.close();
                FileUtils.deleteQuietly(new File(metsfile));

            } catch (PreferencesException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (WriteException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (DocStructHasNoTypeException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (MetadataTypeNotAllowedException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (ExportFileException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (UghHelperException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (ReadException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (SwapException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (DAOException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (TypeNotAllowedForParentException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (IOException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (InterruptedException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } finally {
                if (method != null) {
                    method.releaseConnection();
                }
            }
        } else {
            de.schlichtherle.io.File.setDefaultArchiveDetector(new DefaultArchiveDetector("tar.bz2|tar.gz|zip"));
            de.schlichtherle.io.File zipFile =
                    new de.schlichtherle.io.File(ConfigMain.getParameter("tempfolder") + System.currentTimeMillis() + md5.getMD5() + "_"
                            + process.getTitel() + ".zip");
            try {
                String imagesFolderName = process.getImagesTifDirectory(false);
                File imageFolder = new File(imagesFolderName);
                if (!imageFolder.exists() || !imageFolder.isDirectory()) {
                    return false;
                }
                java.io.File[] filenames = imageFolder.listFiles(Helper.dataFilter);
                if ((filenames == null) || (filenames.length == 0)) {
                    return false;
                }

                List<de.schlichtherle.io.File> images = new ArrayList<de.schlichtherle.io.File>();
                for (java.io.File imagefile : filenames) {
                    images.add(new de.schlichtherle.io.File(imagefile));
                }

                for (de.schlichtherle.io.File image : images) {
                    image.copyTo(new File(zipFile, image.getName()));
                }

                de.schlichtherle.io.File.umount();

                deliveryFile = new File(zipFile.getAbsolutePath(), zipFile.getName());

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
        String donwloadServer = ConfigPlugins.getPluginConfig(this).getString("donwloadServer", "http://leiden01.intranda.com/goobi/");
        String downloadUrl = donwloadServer + deliveryFile.getName();
        try {
            FileUtils.copyFileToDirectory(deliveryFile, new File(destination));
            FileUtils.deleteQuietly(deliveryFile);
        } catch (IOException e) {
            createMessages(Helper.getTranslation("PluginErrorIOError"), e);
            return false;
        }

        // - Name/Link als Property speichern

        boolean matched = false;
        for (Prozesseigenschaft pe : process.getEigenschaftenList()) {
            if (pe.getTitel().equals(PROPERTYTITLE)) {
                pe.setWert(downloadUrl);
                matched = true;
                break;
            }
        }

        if (!matched) {
            Prozesseigenschaft pe = new Prozesseigenschaft();
            pe.setTitel(PROPERTYTITLE);
            pe.setWert(downloadUrl);
            process.getEigenschaften().add(pe);
            pe.setProzess(process);
        }

        try {
            new ProzessDAO().save(process);
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
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Schritt getStep() {
        // TODO
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

        InternetAddress addressFrom = new InternetAddress(SENDER_ADDRESS, "Robert Sehr");
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

}
