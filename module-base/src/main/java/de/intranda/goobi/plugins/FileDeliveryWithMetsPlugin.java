package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
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

import de.schlichtherle.io.DefaultArchiveDetector;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.metadaten.MetadatenVerifizierung;
import de.sub.goobi.persistence.managers.ProcessManager;
import io.goobi.workflow.api.connection.HttpUtils;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.exceptions.DocStructHasNoTypeException;

@Log4j2
@PluginImplementation
public class FileDeliveryWithMetsPlugin implements IStepPlugin, IPlugin {

    private static final long serialVersionUID = -4885456174798006968L;
    private String pluginname = "FileDeliveryWithMets";
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
        if ((mailAddress == null) || (mailAddress.length() == 0) || (format == null) || (format.length() == 0)) {
            return false;
        }

        File deliveryFile = null;
        if ("PDF".equalsIgnoreCase(format)) {

            // TODO sicherstellen das filegroup PDF erzeugt und in im gcs für pdf eingestellt wurde
            MetadatenVerifizierung mv = new MetadatenVerifizierung();
            if (!mv.validate(process)) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), null);
                return false;
            }
            String tempfolder = ConfigurationHelper.getInstance().getTemporaryFolder();

            // - umbenennen in unique Namen
            deliveryFile = new File(tempfolder, System.currentTimeMillis() + "_" + process.getTitel() + ".pdf");
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

                HttpUtils.getStreamFromUrl(fos, goobiContentServerUrl.toString());

                fos.close();

                FileUtils.deleteQuietly(new File(metsfile));

            } catch (DocStructHasNoTypeException | SwapException | IOException e) {
                createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
                return false;
            }
        } else {
            de.schlichtherle.io.File.setDefaultArchiveDetector(new DefaultArchiveDetector("tar.bz2|tar.gz|zip"));
            de.schlichtherle.io.File zipFile = new de.schlichtherle.io.File(
                    ConfigurationHelper.getInstance().getTemporaryFolder() + System.currentTimeMillis() + "_" + process.getTitel() + ".zip");
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

            } catch (SwapException | IOException e) {
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
            createMessages(Helper.getTranslation("fehlerNichtSpeicherbar"), e);
            return false;
        }

        // - mail versenden

        postMail(mailAddress, downloadUrl);

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
            log.error(message, e);
            Helper.setFehlerMeldung(message, e);
        } else {
            Helper.setFehlerMeldung(message);
            log.error(message);
        }
        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message, "automatic");
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

    @Override
    public String getPagePath() {
        return null;
    }

}
