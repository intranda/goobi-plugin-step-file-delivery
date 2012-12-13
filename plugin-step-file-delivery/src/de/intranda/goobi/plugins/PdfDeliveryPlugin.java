package de.intranda.goobi.plugins;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;

import net.xeoh.plugins.base.annotations.PluginImplementation;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
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

import de.sub.goobi.Beans.Prozess;
import de.sub.goobi.Beans.Schritt;
import de.sub.goobi.Export.download.ExportMets;
import de.sub.goobi.Metadaten.MetadatenVerifizierungWithoutHibernate;
import de.sub.goobi.Persistence.ProzessDAO;
import de.sub.goobi.Persistence.apache.ProcessManager;
import de.sub.goobi.Persistence.apache.StepObject;
import de.sub.goobi.config.ConfigMain;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.encryption.MD5;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;

@PluginImplementation
public class PdfDeliveryPlugin implements IStepPlugin, IPlugin {
	private static final Logger logger = Logger.getLogger(PdfDeliveryPlugin.class);

	private String pluginname = "PdfDelivery";
	// private Schritt step;
	private Prozess process;
	private String returnPath;
	
	
	// TODO generate value
	private String internalServletPath = "http://localhost/Goobi19";

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
	public void execute() {
		// - Metadaten validieren

		
		// TODO sicherstellen das filegroup local erzeugt und in im gcs für pdf eingestellt wurde
		MetadatenVerifizierungWithoutHibernate mv = new MetadatenVerifizierungWithoutHibernate();
		if (!mv.validate(process)) {
			createMessage(Helper.getTranslation("InvalidMetadata"), null);
		}

		
		// - PDF erzeugen
		GetMethod method = null;
		try {
			ExportMets em = new ExportMets();
			String tempfolder = ConfigMain.getParameter("", "/opt/digiverso/goobi/temp/");

			em.startExport(process, tempfolder);
			String metsfile = tempfolder + process.getTitel() + "_mets.xml";

			URL goobiContentServerUrl = null;
			String contentServerUrl = ConfigMain.getParameter("goobiContentServerUrl");

			MD5 md5 = new MD5(process.getTitel());
			// - umbenennen in unique Namen
			File finalPdf = new File(tempfolder, md5.getMD5() + "_" + process.getTitel() + ".pdf");
			Integer contentServerTimeOut = ConfigMain.getIntParameter("goobiContentServerTimeOut", 60000);
			if (contentServerUrl == null || contentServerUrl.length() == 0) {
				contentServerUrl = this.internalServletPath + "/gcs/gcs?action=pdf&metsFile=";
			}
			goobiContentServerUrl = new URL(contentServerUrl + metsfile);

			HttpClient httpclient = new HttpClient();
			logger.debug("Retrieving: " + goobiContentServerUrl.toString());
			method = new GetMethod(goobiContentServerUrl.toString());

			method.getParams().setParameter("http.socket.timeout", contentServerTimeOut);
			int statusCode = httpclient.executeMethod(method);
			if (statusCode != HttpStatus.SC_OK) {
				logger.error("HttpStatus nicht ok", null);
				logger.debug("Response is:\n" + method.getResponseBodyAsString());
				return;
			}

			InputStream inStream = method.getResponseBodyAsStream();
			BufferedInputStream bis = new BufferedInputStream(inStream);
			FileOutputStream fos = new FileOutputStream(finalPdf);
			byte[] bytes = new byte[8192];
			int count = bis.read(bytes);
			while (count != -1 && count <= 8192) {
				fos.write(bytes, 0, count);
				count = bis.read(bytes);
			}
			if (count != -1) {
				fos.write(bytes, 0, count);
			}
			fos.close();
			bis.close();

			// TODO individuelle Fehlermeldungen
		} catch (PreferencesException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (WriteException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (DocStructHasNoTypeException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (MetadataTypeNotAllowedException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (ExportFileException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (UghHelperException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (ReadException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (SwapException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (DAOException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (TypeNotAllowedForParentException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (IOException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} catch (InterruptedException e) {
			createMessage(Helper.getTranslation("InvalidMetadata"), e);
			return;
		} finally {
			if (method != null) {
				method.releaseConnection();
			}
		}
		
		// - an anderen Ort kopieren
		// - Name/Link als Property speichern
		// - mail versenden

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

	private void createMessage(String message, Exception e) {
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

}
