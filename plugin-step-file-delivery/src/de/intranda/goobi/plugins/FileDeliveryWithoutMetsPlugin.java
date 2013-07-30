package de.intranda.goobi.plugins;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
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

import de.schlichtherle.io.DefaultArchiveDetector;
import de.sub.goobi.Beans.Prozess;
import de.sub.goobi.Beans.Prozesseigenschaft;
import de.sub.goobi.Beans.Schritt;
import de.sub.goobi.Persistence.ProzessDAO;
import de.sub.goobi.Persistence.apache.ProcessManager;
import de.sub.goobi.Persistence.apache.StepObject;
import de.sub.goobi.config.ConfigMain;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.encryption.MD5;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;

@PluginImplementation
public class FileDeliveryWithoutMetsPlugin implements IStepPlugin, IPlugin {
    private static final Logger logger = Logger.getLogger(FileDeliveryWithoutMetsPlugin.class);

    private String pluginname = "FileDeliveryWithoutMets";
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
        if (mailAddress == null || mailAddress.length() == 0) {
            createMessages(Helper.getTranslation("Delivery failed, email address is missing or empty."), null);

            return false;
        }

        if (format == null || format.isEmpty()) {
            createMessages(Helper.getTranslation("Delivery failed, format is missing or empty."), null);
            return false;
        }

        File deliveryFile = null;
        MD5 md5 = new MD5(process.getTitel());
        String imagesFolderName = "";

        if (format.equalsIgnoreCase("PDF")) {

            GetMethod method = null;
            try {
                imagesFolderName = process.getImagesDirectory() + "pimped_pdf";
                File pdffolder = new File(imagesFolderName);
                if (!pdffolder.exists() && !pdffolder.mkdir()) {
                    createMessages(Helper.getTranslation("Delivery failed, pdf folder is missing."), null);
                    return false;
                }
                File[] listOfFiles = pdffolder.listFiles(pdffilter);

                if (listOfFiles == null || listOfFiles.length == 0) {
                    deliveryFile = new File(pdffolder, process.getTitel() + ".pdf");

                    //                  String metsfile = tempfolder + process.getTitel() + "_mets.xml";
                    // - PDF erzeugen

                    URL goobiContentServerUrl = null;
                    String contentServerUrl = ConfigPlugins.getPluginConfig(this).getString("contentServerUrl");

                    if (contentServerUrl == null || contentServerUrl.length() == 0) {
                        contentServerUrl = "http://localhost:8080/goobi" + "/cs/cs?action=pdf&resolution=150&convertToGrayscale&images=";
                    }
                    String url = "";
                    //                FilenameFilter filter = tiffilter;
                    File imagesDir = new File(process.getImagesTifDirectory(true));
                    File[] meta = imagesDir.listFiles(tiffilter);
                    ArrayList<String> filenames = new ArrayList<String>();
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
                    Integer contentServerTimeOut = ConfigMain.getIntParameter("goobiContentServerTimeOut", 60000);

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
                }

            } catch (SwapException e1) {
                logger.error(e1);
            } catch (DAOException e1) {
                logger.error(e1);
            } catch (IOException e1) {
                logger.error(e1);
            } catch (InterruptedException e1) {
                logger.error(e1);
            } finally {
                if (method != null) {
                    method.releaseConnection();
                }
            }

        } else {
            try {
                imagesFolderName = process.getImagesTifDirectory(false);
            } catch (SwapException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (DAOException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            } catch (IOException e) {
                createMessages(Helper.getTranslation("PluginErrorIOError"), e);
                return false;
            } catch (InterruptedException e) {
                createMessages(Helper.getTranslation("PluginErrorIOError"), e);
                return false;
            }
        }

        de.schlichtherle.io.File.setDefaultArchiveDetector(new DefaultArchiveDetector("tar.bz2|tar.gz|zip"));
        de.schlichtherle.io.File zipFile =
                new de.schlichtherle.io.File(ConfigMain.getParameter("tempfolder") + System.currentTimeMillis() + md5.getMD5() + "_"
                        + process.getTitel() + ".zip");
        try {

            de.schlichtherle.io.File imageFolder = new de.schlichtherle.io.File(imagesFolderName);
            if (!imageFolder.exists() || !imageFolder.isDirectory()) {
                return false;
            }
            String[] filenames = imageFolder.list(Helper.dataFilter);
            if ((filenames == null) || (filenames.length == 0)) {
                return false;
            }

            List<de.schlichtherle.io.File> images = new ArrayList<de.schlichtherle.io.File>();
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

        } catch (IOException e) {
            createMessages(Helper.getTranslation("PluginErrorIOError"), e);
            return false;
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
        return null;
    }

    @Override
    public Schritt getStep() {
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

}
